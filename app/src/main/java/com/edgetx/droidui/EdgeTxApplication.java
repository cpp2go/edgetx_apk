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
