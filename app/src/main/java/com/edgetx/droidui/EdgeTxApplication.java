package com.edgetx.droidui;

import android.app.Application;
import android.content.Context;
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
}
