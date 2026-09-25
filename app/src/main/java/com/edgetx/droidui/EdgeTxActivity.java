package com.edgetx.droidui;

import android.Manifest;
import android.app.NativeActivity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * The launcher activity: Android's {@link NativeActivity}, plus the two things that can only be
 * done from an activity - asking for a permission that needs a dialog, and telling the user what
 * a permission it cannot ask for is costing.
 *
 * <p>The app itself still runs from NativeActivity - this subclass only adds {@code onCreate},
 * {@code onResume}, {@code onPause} and {@code onRequestPermissionsResult} - so nothing about the
 * native side changes; the manifest names this class instead of {@code android.app.NativeActivity}
 * and everything after that (lib_name meta-data, the callbacks) is inherited.
 *
 * <p>The permission it asks for is the one Android 10 needs for the simulated SD card. Android 11
 * and later use "All files access", which the user grants once in system settings (see
 * {@link EdgeTxApplication}), but Android 10 has no such thing: the card in
 * {@code /storage/emulated/0/EdgeTX} is written with {@code WRITE_EXTERNAL_STORAGE} there, and a
 * runtime permission can only be requested from an activity. On the RC Pro (Android 10) that is
 * what puts the card where a file manager can reach it, the way it already is on every other
 * remote. Android 11+ returns immediately: the permission is not even declared for those versions
 * (maxSdkVersion 29 in the manifest), and cannot be requested.
 *
 * <p>The other two are one that needs a dialog ({@code UsbManager.requestPermission}, asked for
 * again below until it is granted - see {@link RcRawJoystick}) and one that cannot be asked for at
 * all: {@code READ_LOGS} is signature-level and adb-only, so all this activity can do about it is
 * say on the screen that it is missing and what that costs.
 */
public class EdgeTxActivity extends NativeActivity {

    private static final String TAG = "EdgeTXUI";

    /** Both runtime permissions this app asks for go through this one request. */
    private static final int REQUEST_PERMISSIONS = 1;

    /** How often to put the USB dialog back up while the permission is missing. */
    private static final long USB_ASK_MS = 8000;

    /** The dialog right after the window appears, not one interval later. */
    private static final long USB_FIRST_ASK_MS = 2500;

    /**
     * How many times to ask before leaving it alone until the app comes to the front again. Five
     * is enough that a dialog missed once is not the end of it, and few enough that a deliberate
     * "no" is not answered with a dialog every eight seconds for as long as the UI is up.
     */
    private static final int USB_ASKS = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int usbAsks;

    /** Said once per process, so the reminder does not repeat at every resume. */
    private static boolean sToldAboutLogs;

    /**
     * Puts the USB permission dialog back up until the raw joystick is claimed.
     *
     * <p>Its reader is the only source of the 400 Hz sticks, the dials, the return button's real
     * long press, and the round and photo buttons on the RC Pro - and the permission behind it
     * exists only as a dialog: there is no settings screen for it, and the dialog is shown once
     * per request. Asking only from {@code onResume} therefore meant that a dialog dismissed
     * while the firmware was on screen never came back (the user's "the virtual joystick
     * permission only pops up when the app is installed"), and with it the reader stayed off for
     * the rest of the session.
     */
    private final Runnable usbAsk = new Runnable() {
        @Override
        public void run() {
            if (RcRawJoystick.ownsInputs()) {
                return;
            }
            if (usbAsks >= USB_ASKS) {
                Log.i(TAG, "raw joystick: still no USB permission after " + usbAsks
                        + " asks, leaving it until the app comes to the front again");
                return;
            }
            usbAsks++;
            RcRawJoystick.start(EdgeTxActivity.this);
            handler.postDelayed(this, USB_ASK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The two runtime permissions this app has, and neither one has a settings screen that
        // would do: the storage one Android 10 needs for the simulated card (see the class note),
        // and the location one the remote's own GPS is read with (see RcGps). Only what is
        // missing is asked for, so every start after the first asks for nothing.
        final java.util.List<String> wanted = new java.util.ArrayList<>(2);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not look up the runtime permissions", t);
            return;
        }

        if (wanted.isEmpty()) {
            return;
        }
        try {
            Log.i(TAG, "asking for " + wanted);
            requestPermissions(wanted.toArray(new String[0]), REQUEST_PERMISSIONS);
        } catch (Throwable t) {
            // Never let a permission prompt take the app down: without storage the card simply
            // stays in the app's own directory, and without location the firmware shows no fix.
            Log.w(TAG, "could not ask for the runtime permissions", t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The raw joystick reports need a USB permission, and that one can only be granted
        // through a dialog: asked for from the Application, before any window exists, the
        // system refuses it without showing anything (measured on the RC Pro, where the
        // photo and record buttons are in those reports and nowhere else). Here the window
        // is there, so the dialog is real. The call is idempotent: with the permission
        // already held it only makes sure the reader is running.
        usbAsks = 0;
        handler.removeCallbacks(usbAsk);
        handler.postDelayed(usbAsk, USB_FIRST_ASK_MS);

        // Both are idempotent, and both need something this activity has just made sure of: a
        // window for the USB dialog, and the location permission for the remote's GPS.
        RcGps.start(this);

        tellAboutMissingLogAccess();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // A request made without a window is refused without a dialog, so do not ask while the
        // firmware is not the thing on screen; onResume starts the asking again.
        handler.removeCallbacks(usbAsk);
    }

    /**
     * The one grant this app cannot ask for anywhere, said where the user can see it.
     *
     * <p>{@code READ_LOGS} is signature|privileged and adb-only ({@code pm grant}), and it is
     * stored per installation, so a reinstall takes it away again. Without it the remote's own
     * button log is unread (see {@link RcDpadLog}) and the landing, pause and C1..C3 buttons are
     * down to the DJI SDK alone - which loses them in waves while the DJI app holds the remote's
     * data channel. That is the "these switches only work sometimes" report, and until now the
     * only sign of it was a line in logcat. A toast costs nothing and is the difference between
     * a user who can fix it and one who cannot.
     */
    private void tellAboutMissingLogAccess() {
        if (sToldAboutLogs) {
            return;
        }
        try {
            if (checkSelfPermission(Manifest.permission.READ_LOGS)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            sToldAboutLogs = true;
            Toast.makeText(this, "READ_LOGS not granted: landing, pause and C1..C3 will miss "
                            + "presses.\nRun once per install: adb shell pm grant "
                            + getPackageName() + " android.permission.READ_LOGS",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.w(TAG, "could not tell the user about READ_LOGS", t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSIONS || permissions == null) {
            return;
        }
        for (int i = 0; i < permissions.length; i++) {
            final boolean granted = results != null && i < results.length
                    && results[i] == PackageManager.PERMISSION_GRANTED;
            if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])) {
                // The card's root is decided when the link starts, which has already happened by
                // the time this answer arrives - so a grant takes effect on the next start, and
                // saying so is the point of this line.
                Log.i(TAG, "sdcard: WRITE_EXTERNAL_STORAGE " + (granted
                        ? "granted - the card moves to /storage/emulated/0/EdgeTX on the next start"
                        : "denied - the card stays in the app's own directory"));
            } else if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])) {
                Log.i(TAG, "gps: ACCESS_FINE_LOCATION " + (granted
                        ? "granted - the remote's own GPS is read from now on"
                        : "denied - EdgeTX's own GPS stays empty"));
                if (granted) {
                    // The listener could not start before the grant, so this is the moment.
                    RcGps.start(this);
                }
            }
        }
    }
}
