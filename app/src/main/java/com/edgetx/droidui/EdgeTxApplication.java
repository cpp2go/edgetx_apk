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
import android.provider.Settings;
import android.util.Log;

import java.io.File;

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
    private static final String LINK_PREFS = "link";
    private static final String PREF_MANUAL_CLOSED = "manualClosed";

    /** The notification that leads to the "All files access" page. */
    private static final int SD_ACCESS_NOTIFICATION_ID = 1;

    private static boolean sRuntimeStarted;

    /**
     * Set when the runtime was held back because the card had no user-visible place. The
     * activity clears it once the grant is there (or once the user has said no).
     */
    private static boolean sWaitingForCardAccess;

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

        if (isManuallyClosed(this)) {
            Log.i(TAG, "link: previously closed by the user; waiting for a manual app launch");
            return;
        }

        if (!cardDirectoryUsable(this)) {
            // The firmware picks its card root when it starts, and it cannot be moved to
            // another one afterwards: stopping and starting the link re-initialises the
            // simulator in the same process, and that second initialisation never finishes
            // (it stops at the boot splash). So the start is held back instead, until the
            // grant that puts the card in /storage/emulated/0/EdgeTX is there - see
            // EdgeTxActivity, which starts the runtime again once it is.
            sWaitingForCardAccess = true;
            RcLinkService.holdLink(true);
            notifySdAccess(sdAccessSettings(this));
            Log.i(TAG, "sdcard: waiting for the card permission before starting the link");
            return;
        }

        startRuntime();
    }

    /**
     * Called from the activity on every launch: starts the runtime, unless the card has
     * nowhere user-visible to live yet.
     */
    static void startFromActivity(Context context) {
        final EdgeTxApplication app = (EdgeTxApplication) context.getApplicationContext();
        setManuallyClosed(app, false);

        if (!sRuntimeStarted && !cardDirectoryUsable(app)) {
            // The same hold as in onCreate: the firmware picks its card root when it starts
            // and cannot be moved to another one afterwards.
            sWaitingForCardAccess = true;
            RcLinkService.holdLink(true);
            app.askForSdCardAccess();
            Log.i(TAG, "sdcard: waiting for the card permission before starting the link");
            return;
        }

        sWaitingForCardAccess = false;
        app.startRuntime();
    }

    /**
     * Starts the runtime even though the card has no user-visible place: the user was asked
     * and did not grant it (Android 10's dialog answered with "no", or the settings page left
     * without a grant). Booting with the card in the app's own directory is better than not
     * booting at all, and the notification stays up so the grant can still be given later.
     */
    static void startWithoutCardAccess(Context context) {
        final EdgeTxApplication app = (EdgeTxApplication) context.getApplicationContext();
        sWaitingForCardAccess = false;
        app.startRuntime();
    }

    /** True while the firmware is held back waiting for the card permission. */
    static boolean isWaitingForCardAccess() {
        return sWaitingForCardAccess;
    }

    /**
     * Whether the card can be created where the user can reach it: "All files access" on
     * Android 11 and later, the legacy storage permission on Android 10, and nothing at all
     * on the versions before it.
     */
    static boolean cardDirectoryUsable(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return Environment.isExternalStorageManager();
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return true;
            }
            return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            Log.w(TAG, "sdcard: could not look up the card permission", t);
            return true;
        }
    }

    static boolean isManuallyClosed(Context context) {
        return context.getSharedPreferences(LINK_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_MANUAL_CLOSED, false);
    }

    static void setManuallyClosed(Context context, boolean closed) {
        final boolean saved = context.getSharedPreferences(LINK_PREFS, MODE_PRIVATE)
                .edit().putBoolean(PREF_MANUAL_CLOSED, closed).commit();
        if (!saved) {
            Log.w(TAG, "link: could not persist manual-close state");
        }
    }

    private synchronized void startRuntime() {
        if (sRuntimeStarted) return;
        sRuntimeStarted = true;

        // The card has a place now (or the user declined): let the firmware come up.
        RcLinkService.holdLink(false);

        askForSdCardAccess();
        RcBattery.start(this);
        // The remote's own GPS, for EdgeTX's own position. Needs ACCESS_FINE_LOCATION, which only
        // the activity can ask for - this call is the one that works once that has been granted.
        RcGps.start(this);
        // Diagnostic only, and only while files/hid_probe exists: asks whether DJI's own protocol
        // library is reachable from this app (see RcVendorProtocol).
        RcVendorProtocol.start(this);
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
    private boolean inUsbAttachProcess() {
        // API 28 has the process name; the remotes run Android 10 or later, and an older
        // device that still installs this app simply starts normally.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;

        // getProcessName() returns the *full* name - "com.edgetx.droidui:usbattach" -
        // not the ":usbattach" suffix the manifest declares. Comparing against the
        // suffix never matched, so this process started the whole runtime as well:
        // two RF module bridges, two SDK registrations and two battery readers, with
        // both module bridges fighting over the same USB serial devices.
        final String process = Application.getProcessName();
        return process != null && process.equals(getPackageName() + USB_ATTACH_PROCESS);
    }

    /**
     * The simulated SD card lives in {@code /storage/emulated/0/EdgeTX} so the models,
     * sounds and radio.yml can be edited with a file manager. Writing there needs
     * "All files access" ({@code MANAGE_EXTERNAL_STORAGE}), which has no runtime dialog:
     * the only way to it is {@link Settings#ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION}.
     *
     * <p>That page is opened from {@link EdgeTxActivity}, where a window exists and the
     * start is not refused as a background activity start; here the notification that
     * leads to the same page is posted, and the card is moved the moment the grant
     * arrives. Without the grant the firmware keeps the card in the app's own directory,
     * which no file manager can reach - so it is asked for again on every launch until
     * it is granted, not once per installation.
     */
    private void askForSdCardAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;  // legacy external storage was handed to apps on request
        }

        try {
            final NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            if (Environment.isExternalStorageManager()) {
                if (manager != null) manager.cancel(SD_ACCESS_NOTIFICATION_ID);
                Log.i(TAG, "sdcard: all-files access granted, the card lives in /storage/emulated/0/EdgeTX");
                return;
            }

            notifySdAccess(sdAccessSettings(this));

            // The page itself is opened from EdgeTxActivity, and the card is moved there
            // too, once the user comes back with the grant (see resumeCardDirectory).
            Log.i(TAG, "sdcard: all-files access missing, the card stays in the app directory until it is granted");
        } catch (Throwable t) {
            // Some vendor builds have no such settings page; the app then simply keeps
            // its card in the app directory.
            Log.w(TAG, "sdcard: could not ask for all-files access", t);
        }
    }

    /** The system page on which "All files access" is granted. */
    static Intent sdAccessSettings(Context context) {
        return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
    }

    /**
     * The card directory the user sees, derived the same way as {@code user_sd_root()} in
     * native_main.cpp: {@code /storage/emulated/0/Android/data/<pkg>/files} minus the part
     * from {@code /Android/} on, plus {@code /EdgeTX}. {@code null} when the device has no
     * shared storage, which is the case prepare_sd_root() also gives up on.
     */
    static File cardDirectory(Context context) {
        final File external = context.getExternalFilesDir(null);
        if (external == null) return null;
        final String path = external.getAbsolutePath();
        final int pos = path.indexOf("/Android/");
        if (pos < 0) return null;
        return new File(path.substring(0, pos) + "/EdgeTX");
    }

    /** Takes the permission notification out of the shade once it is no longer needed. */
    static void cancelSdAccessNotification(Context context) {
        try {
            final NotificationManager manager =
                    (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(SD_ACCESS_NOTIFICATION_ID);
        } catch (Throwable t) {
            Log.w(TAG, "sdcard: could not clear the permission notification", t);
        }
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

            manager.notify(SD_ACCESS_NOTIFICATION_ID, builder
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
