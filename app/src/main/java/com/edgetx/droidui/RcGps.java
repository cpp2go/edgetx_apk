package com.edgetx.droidui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * The remote controller's own GPS, read from Android rather than from the DJI SDK.
 *
 * <p>EdgeTX has a place for the radio's own position - {@code gpsData}, the slot an internal GPS
 * module fills (see the simulator's {@code simulib.cpp}) - and this is where that position comes
 * from. The telemetry GPS that arrives over the RF link is the aircraft's and is a different
 * thing; nothing here touches it.
 *
 * <p>The SDK has a key for the same thing ({@code DJIRemoteControllerKey.KeyRcGPSInfo}) and it
 * reports as supported, but measured on the RC Pro it never delivers a single value - the same
 * story as {@code KeySoftSwitchMode} and {@code KeyFlightModeString} on that remote. Android's
 * own location stack, on the other hand, is already tracking the same chip (the remote's
 * {@code com.dpad.service} asks for {@code gps} updates every five minutes), so this reads it
 * there: no aircraft, no SDK silence to worry about.
 *
 * <p>Unlike {@code READ_LOGS} this permission is a runtime one, so it can be asked for from the
 * launcher activity (see {@link EdgeTxActivity}); without it the listener simply never starts and
 * the firmware shows no fix.
 */
final class RcGps implements LocationListener {

    private static final String TAG = "EdgeTXUI";

    static {
        // Started from Application.onCreate, i.e. before NativeActivity loads libedgetx_ui.so.
        try {
            System.loadLibrary("edgetx_ui");
        } catch (Throwable t) {
            Log.w(TAG, "gps: could not load edgetx_ui", t);
        }
    }

    /**
     * The position, in the firmware's units: 1e-6 degrees, 0.1 m, 0.1 m/s, 0.1 degrees,
     * satellites, and 0/1 for the fix. It becomes {@code gpsData} inside the simulator library.
     */
    static native void nativeSetGps(int latitude, int longitude, int altitude, int speed,
                                    int course, int satellites, boolean fix);

    /** How often Android is asked for an update, and how far one has to move to be worth one. */
    private static final long UPDATE_MS = 1000;
    private static final float UPDATE_M = 1.0f;

    /**
     * No update for this long means the fix is gone - the remote is inside, or the GPS was turned
     * off. Say so rather than keep showing a position from half a minute ago; Android's own "last
     * known location" is exactly what a navigation app wants here, but a radio showing where it
     * is standing is not.
     */
    private static final long STALE_MS = 30000;

    private static boolean sStarted;
    private static boolean sFix;
    private static int sLatitude;
    private static int sLongitude;
    private static int sSats;
    private static long sFixAt;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static final Runnable STALE_CHECK = new Runnable() {
        @Override
        public void run() {
            if (sFix && System.currentTimeMillis() - sFixAt >= STALE_MS) {
                sFix = false;
                Log.i(TAG, "gps: no update for " + STALE_MS + " ms, the fix is gone");
                nativeSetGps(0, 0, 0, 0, 0, 0, false);
            }
        }
    };

    private static final RcGps LISTENER = new RcGps();

    private RcGps() {}

    /** Called from {@code EdgeTxApplication.onCreate}, and again once the permission is granted. */
    static void start(Context context) {
        if (sStarted) {
            return;
        }
        try {
            if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                // The activity asks for it; the call below throws until that has happened.
                Log.i(TAG, "gps: no ACCESS_FINE_LOCATION, the remote's GPS stays unread");
                return;
            }
            final LocationManager manager =
                    (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (manager == null || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Log.i(TAG, "gps: no GPS provider on this device");
                return;
            }
            sStarted = true;
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_MS, UPDATE_M,
                    LISTENER, Looper.getMainLooper());
            Log.i(TAG, "gps: reading the remote's own position from Android");
        } catch (Throwable t) {
            // Never fatal: without a position the firmware simply shows no fix.
            Log.w(TAG, "gps: could not listen", t);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) {
            return;
        }
        try {
            final int latitude = (int) Math.round(location.getLatitude() * 1000000.0);
            final int longitude = (int) Math.round(location.getLongitude() * 1000000.0);
            final int altitude = (int) Math.round(location.getAltitude() * 10.0);      // 0.1 m
            final float metresPerSecond = location.hasSpeed() ? location.getSpeed() : 0;
            final int speed = Math.round(metresPerSecond * 10.0f);                      // 0.1 m/s
            final float bearing = location.hasBearing() ? location.getBearing() : 0;
            final int course = Math.round(bearing * 10.0f);                             // 0.1 deg
            final int sats = location.getExtras() == null ? 0
                    : location.getExtras().getInt("satellites", 0);

            if (sFix && latitude == sLatitude && longitude == sLongitude && sats == sSats) {
                // The same position again: Android repeats it while standing still, and there is
                // nothing to hand over.
                sFixAt = System.currentTimeMillis();
                return;
            }
            sLatitude = latitude;
            sLongitude = longitude;
            sSats = sats;
            sFixAt = System.currentTimeMillis();
            final boolean first = !sFix;
            sFix = true;
            if (first) {
                Log.i(TAG, "gps: the remote's own position has a fix");
            }
            Log.i(TAG, "gps: " + location.getLatitude() + ", " + location.getLongitude()
                    + " sats=" + sats + " acc=" + location.getAccuracy() + " m");
            nativeSetGps(latitude, longitude, altitude, speed, course, sats, true);

            HANDLER.removeCallbacks(STALE_CHECK);
            HANDLER.postDelayed(STALE_CHECK, STALE_MS);
        } catch (Throwable t) {
            Log.w(TAG, "gps: could not hand the position over", t);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {
        if (sFix) {
            sFix = false;
            Log.i(TAG, "gps: the GPS provider was turned off");
            nativeSetGps(0, 0, 0, 0, 0, 0, false);
        }
    }
}
