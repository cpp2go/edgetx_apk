package com.edgetx.droidui;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

/**
 * Application subclass that gives the optional DJI Mobile SDK somewhere to start.
 *
 * <p>The UI itself is a pure {@code android.app.NativeActivity}; this class exists
 * only so the SDK can be initialised before the native code runs. Every
 * SDK-specific step sits behind a reflection guard, so this class loads and runs
 * fine when the SDK is not part of the build (the default).
 */
public class EdgeTxApplication extends Application {
    private static final String TAG = "EdgeTXUI";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // MSDK 5.10+ ships a class relocator that has to be installed before any
        // SDK class is touched. Not present unless dji.msdk=true.
        try {
            Class<?> helper = Class.forName("com.cySdkyc.clx.Helper");
            helper.getMethod("install", Application.class).invoke(null, this);
            Log.i(TAG, "dji: sdk helper installed");
        } catch (Throwable t) {
            Log.i(TAG, "dji: sdk helper not present (" + t.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        askForSdCardAccess();
        RcBattery.start(this);
        // The squelch, beeps and voice prompts are the firmware's own audio; this only
        // gives it an output device (see RcAudio).
        RcAudio.start(this);
        // The external RF module lives on a USB serial port (see RcModuleSerial).
        // Started here rather than from the activity so the permission prompt and
        // the port are ready before the firmware opens its module port.
        RcModuleSerial.start(this);
        // The firmware itself is owned by a foreground service, not by the activity:
        // that is what keeps stick/switch data flowing to the module after the UI is
        // gone. Started here for the same reason as the bridge above - it has to be
        // up before the activity attaches to it.
        RcLinkService.start(this);
        // The bridge only loads when the SDK is packaged; without it this throws
        // NoClassDefFoundError, which we deliberately swallow.
        try {
            Class<?> bridge = Class.forName("com.edgetx.droidui.DjiMsdkBridge");
            bridge.getMethod("init", Application.class).invoke(null, this);
        } catch (Throwable t) {
            Log.i(TAG, "dji: sdk bridge inactive (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * The simulated SD card lives in {@code /storage/emulated/0/EdgeTX} so the models,
     * sounds and radio.yml can be edited with a file manager. Writing there needs
     * "All files access" ({@code MANAGE_EXTERNAL_STORAGE}); without it the card stays in
     * the app's own directory, which is why this asks for it - once, at start-up, since
     * there is no in-app dialog for this particular permission.
     */
    private void askForSdCardAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;  // legacy external storage was handed to apps on request
        }

        try {
            if (Environment.isExternalStorageManager()) {
                Log.i(TAG, "sdcard: all-files access granted, the card lives in /storage/emulated/0/EdgeTX");
                return;
            }

            SharedPreferences prefs = getSharedPreferences("link", MODE_PRIVATE);
            if (prefs.getBoolean("sdAccessAsked", false)) {
                // Asked before and still not granted: keep quiet, the firmware logs the
                // directory it fell back to.
                return;
            }
            prefs.edit().putBoolean("sdAccessAsked", true).apply();

            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.i(TAG, "sdcard: asking for all-files access");
        } catch (Throwable t) {
            // Some vendor builds have no such settings page; the app then simply keeps
            // its card in the app directory.
            Log.w(TAG, "sdcard: could not ask for all-files access", t);
        }
    }
}
