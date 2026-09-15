package com.edgetx.droidui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

/**
 * The remote controller's own battery, read from Android rather than from the DJI SDK.
 *
 * <p>The RC is an Android device, so {@link BatteryManager} reports the pack directly -
 * voltage in mV, percentage, temperature and, importantly, whether it is charging. The
 * MSDK's {@code KeyBatteryInfo} covers only part of that (it has no charging field at
 * all), so this is the source of truth; the SDK's version is logged alongside it for
 * comparison.
 *
 * <p>ACTION_BATTERY_CHANGED is sticky, so registering also hands back the current values
 * immediately, and it is broadcast on every change afterwards.
 */
final class RcBattery {
    private static final String TAG = "EdgeTXUI";

    static {
        // This runs from Application.onCreate, i.e. before NativeActivity loads
        // libedgetx_ui.so, so the library has to be pulled in here or the call below
        // fails with UnsatisfiedLinkError. Loading it twice is a no-op.
        try {
            System.loadLibrary("edgetx_ui");
        } catch (Throwable t) {
            Log.w(TAG, "battery: could not load edgetx_ui", t);
        }
    }

    /** Millivolts, percent and charging, on their way to the firmware. */
    static native void nativeOnBattery(int millivolts, int percent, boolean charging);

    private static boolean sRegistered;
    private static int sLastMv = -1;
    private static int sLastPercent = -1;
    private static boolean sLastCharging;

    private RcBattery() {}

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            apply(intent);
        }
    };

    static void start(Context context) {
        if (sRegistered) {
            return;
        }
        sRegistered = true;
        try {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            // Non-null return: the sticky broadcast carrying the values right now.
            final Intent current = context.registerReceiver(RECEIVER, filter);
            if (current != null) {
                apply(current);
            }
        } catch (Throwable t) {
            Log.w(TAG, "battery: could not listen", t);
        }
    }

    private static void apply(Intent intent) {
        if (intent == null) {
            return;
        }
        try {
            final int mV = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            final int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            final int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            final int percent = scale > 0 ? (level * 100) / scale : level;
            final boolean charging =
                    status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;

            // Only the transitions, so a chatty broadcast does not flood the log.
            if (mV != sLastMv || percent != sLastPercent || charging != sLastCharging) {
                sLastMv = mV;
                sLastPercent = percent;
                sLastCharging = charging;
                Log.i(TAG, "battery: " + mV + " mV, " + percent + "%, "
                        + (charging ? "charging" : "not charging")
                        + ", status=" + status + " plugged=" + plugged
                        + " temp=" + (temperature / 10.0f) + "C");
            }

            nativeOnBattery(mV, percent, charging);
        } catch (Throwable t) {
            Log.w(TAG, "battery: dispatch failed", t);
        }
    }
}
