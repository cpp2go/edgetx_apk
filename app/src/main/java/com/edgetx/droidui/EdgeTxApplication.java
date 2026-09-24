package com.edgetx.droidui;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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

    /** The process {@link UsbAttachActivity} runs in, and which must stay empty. */
    private static final String USB_ATTACH_PROCESS = ":usbattach";

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

        // Started because a USB device was attached (see UsbAttachActivity and the
        // device filter in the manifest): that is how Android recognises this app as
        // the joystick's own app, and it is worth a throwaway process - but not the
        // firmware, the SDK or an RF module coming up because a joystick appeared.
        if (inUsbAttachProcess()) {
            Log.i(TAG, "usb: attach-only process, nothing to start here");
            return;
        }

        askForSdCardAccess();
        RcBattery.start(this);
        // The squelch, beeps and voice prompts are the firmware's own audio; this only
        // gives it an output device (see RcAudio).
        RcAudio.start(this);
        // The RF module lives on a USB serial port (see RcModuleSerial).
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

        // The RC's sticks and dials, read straight off the joystick's USB interface: the
        // DJI SDK publishes them at 8-14 Hz while the device itself produces a new
        // position every 2.5 ms. The SDK stays as the fallback, see RcRawJoystick.
        RcRawJoystick.start(this);

        // The buttons the remote's own handler logs, which on the RC Pro is the reliable
        // source for the pause, landing and custom buttons. Inert without READ_LOGS, see
        // RcDpadLog - the SDK paths then behave exactly as they did before.
        RcDpadLog.start(this);
    }

    /** True in the process that exists only to carry the USB device filter. */
    private static boolean inUsbAttachProcess() {
        // API 28 has the process name; the RC is Android 11, and an older device that
        // still installs this app simply starts normally.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && USB_ATTACH_PROCESS.equals(Application.getProcessName());
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

            final Intent settings = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));

            // Opening the page straight away is what usually works, but an Application
            // context may not be allowed to start an activity at all (background
            // activity start restrictions). A notification is tappable in any case, so
            // post one as well - tapping it lands on the same page.
            try {
                startActivity(new Intent(settings).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                Log.w(TAG, "sdcard: could not open the settings page directly", t);
            }

            notifySdAccess(settings);
            Log.i(TAG, "sdcard: asking for all-files access");
            watchForSdAccess();
        } catch (Throwable t) {
            // Some vendor builds have no such settings page; the app then simply keeps
            // its card in the app directory.
            Log.w(TAG, "sdcard: could not ask for all-files access", t);
        }
    }

    /**
     * Moves the card to the user directory the moment the grant arrives, instead of
     * waiting for the next launch: the firmware picked its directory when it started,
     * so nothing changes until the link is restarted - and the whole point of asking is
     * that the card should be in /storage/emulated/0/EdgeTX from the start.
     */
    private void watchForSdAccess() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final long deadline = System.currentTimeMillis() + 10 * 60 * 1000;

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Environment.isExternalStorageManager()) {
                        Log.i(TAG, "sdcard: access granted, restarting the link to use "
                                + "/storage/emulated/0/EdgeTX");
                        stopService(new Intent(EdgeTxApplication.this, RcLinkService.class));
                        RcLinkService.start(EdgeTxApplication.this);
                        return;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "sdcard: could not move the card to the user directory", t);
                    return;
                }

                if (System.currentTimeMillis() < deadline) {
                    handler.postDelayed(this, 2000);
                }
            }
        }, 2000);
    }

    /**
     * Puts the permission request in the notification shade as well. The card works
     * without it (it then lives in the app's own directory), so this is an offer, not a
     * blocker; it disappears once the page has been opened.
     */
    private void notifySdAccess(Intent settings) {
        try {
            final String channelId = "link";
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(channelId,
                        "EdgeTX link", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("Keeps the firmware running and the SD card visible");
                manager.createNotificationChannel(channel);
            }

            PendingIntent open = PendingIntent.getActivity(this, 0, settings,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(this, channelId)
                    : new Notification.Builder(this);

            manager.notify(1, builder
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("允许 EdgeTX 访问存储")
                    .setContentText("点此授予\"所有文件访问\"，模拟 SD 卡会放到 /storage/emulated/0/EdgeTX")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build());
        } catch (Throwable t) {
            Log.w(TAG, "sdcard: could not post the permission notification", t);
        }
    }
}
