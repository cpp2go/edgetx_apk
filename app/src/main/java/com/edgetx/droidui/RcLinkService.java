package com.edgetx.droidui;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;

/**
 * Keeps the EdgeTX link alive after the UI goes away.
 *
 * <p>The activity is only a viewer: the firmware, the RF module and the
 * whole stick/switch path live in this process. Android tears a process down as
 * soon as its last activity is gone unless something holds it, so without this
 * service pressing Back or swiping the app away stopped the mixer, and the RF
 * module stopped receiving frames.
 *
 * <p>This service is that something. It is a foreground service (see
 * {@code android:foregroundServiceType} in the manifest), so:
 *
 * <ul>
 *   <li>the process is not killed when the task is removed, which is what makes
 *       the stick data keep reaching the module,
 *   <li>the process is not frozen by the cached-app freezer either,
 *   <li>a partial wake lock is held on top of that, so the stream also continues
 *       with the screen off.
 * </ul>
 *
 * <p>What survives the UI and what does not:
 *
 * <ul>
 *   <li>DJI SDK input (sticks, dials, the 5-way, switches) keeps arriving: those
 *       callbacks come from Java inside this process. On the RC Plus 2 this is
 *       the source of the sticks.
 *   <li>Android input events (a gamepad, or any controller whose events Android
 *       dispatches) do <b>not</b> survive: only a focused window receives them,
 *       and a service has no window. Stick data that comes that way stops at the
 *       last value the firmware saw.
 *   <li>Touch, and the UI itself, obviously stop until the activity is back.
 * </ul>
 *
 * <p>Reopening the app does not restart anything: the activity attaches to the
 * firmware that is already running, so models stay selected and channels keep
 * their values.
 */
public final class RcLinkService extends Service {
    private static final String TAG = "EdgeTXUI";

    private static final String CHANNEL_ID = "rc_link";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_STOP_LINK = "com.edgetx.droidui.action.STOP_LINK";
    private static final String DJI_FLY_PACKAGE = "dji.go.v5";
    private static final long DJI_FLY_CHECK_MS = 5000;

    static {
        // Normally already loaded by NativeActivity / RcModuleSerial; loading it
        // here as well is a no-op and keeps this class usable on its own.
        try {
            System.loadLibrary("edgetx_ui");
        } catch (Throwable t) {
            Log.w(TAG, "link: could not load edgetx_ui", t);
        }
    }

    // --------------------------------------------------------------- natives --

    /**
     * Starts the firmware, the module bridge and the timer that keeps
     * feeding the firmware after the UI is gone. Idempotent.
     *
     * @param filesDir         the app's internal files directory, used for the
     *                         simulated SD card when there is no external one.
     * @param externalFilesDir the app's external files directory, or null. The
     *                         simulated SD card is put there when it exists, so it
     *                         can be edited with adb or a file manager instead of
     *                         being hidden in the app's private storage.
     * @param assets           the APK assets holding the bundled SD-card content.
     */
    private static native boolean nativeStartLink(String filesDir, String externalFilesDir,
                                                  AssetManager assets);

    /** Stops the firmware and gives up the process's reason to stay alive. */
    private static native void nativeStopLink();

    /** True while the firmware is up. */
    private static native boolean nativeLinkRunning();

    private PowerManager.WakeLock mWakeLock;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mDjiStopRequests;
    private boolean mExitProcessOnDestroy;
    private final Runnable mDjiFlyGuard = new Runnable() {
        @Override
        public void run() {
            if (EdgeTxApplication.isManuallyClosed(RcLinkService.this)) {
                return;
            }
            stopDjiFlyInBackground();
            if (!EdgeTxApplication.isManuallyClosed(RcLinkService.this)) {
                mHandler.postDelayed(this, DJI_FLY_CHECK_MS);
            }
        }
    };

    // ------------------------------------------------------------- lifecycle --

    /**
     * Starts (or re-uses) the service. Safe to call from anywhere in the app: a
     * failure to start the foreground service is logged and ignored, because the
     * app is still perfectly usable without it - it just will not survive its
     * own UI going away.
     */
    public static void start(Context context) {
        if (EdgeTxApplication.isManuallyClosed(context)) {
            Log.i(TAG, "link: start ignored because the user closed EdgeTX");
            return;
        }
        final Intent intent = new Intent(context, RcLinkService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "link: could not start the service, retrying as a plain start", t);
            try {
                context.startService(intent);
            } catch (Throwable t2) {
                Log.w(TAG, "link: no service; the link stops with the activity", t2);
            }
        }
    }

    static void stopForManualHandoff(Context context) {
        final Intent intent = new Intent(context, RcLinkService.class).setAction(ACTION_STOP_LINK);
        try {
            context.startService(intent);
        } catch (Throwable t) {
            Log.w(TAG, "link: could not stop for manual handoff", t);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        promoteToForeground();
        acquireWakeLock();
        if (!EdgeTxApplication.isManuallyClosed(this)) {
            mHandler.post(mDjiFlyGuard);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_LINK.equals(intent.getAction())) {
            Log.i(TAG, "link: explicit stop requested; DJI Fly monitor stopped");
            EdgeTxApplication.setManuallyClosed(this, true);
            mExitProcessOnDestroy = true;
            mHandler.removeCallbacks(mDjiFlyGuard);
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (EdgeTxApplication.isManuallyClosed(this)) {
            Log.i(TAG, "link: ignoring automatic service restart after manual close");
            mHandler.removeCallbacks(mDjiFlyGuard);
            mExitProcessOnDestroy = true;
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // Booting the firmware seeds the simulated SD card first, which takes
        // seconds on the very first run: keep that off the main thread, where the
        // notification and the activity's lifecycle are handled.
        final Thread starter = new Thread(this::startLink, "rc-link-start");
        starter.setDaemon(true);
        starter.start();

        // Let Android restart the service (and with it the link) after a crash or
        // a low-memory kill.
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiping the app away removes the task, not this service: by design the
        // firmware keeps running, because the RF module still needs its frames.
        Log.i(TAG, "link: task removed, the link stays up");
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "link: service destroyed");
        mHandler.removeCallbacks(mDjiFlyGuard);
        try {
            nativeStopLink();
        } catch (Throwable t) {
            Log.w(TAG, "link: could not stop the firmware", t);
        }
        releaseWakeLock();
        super.onDestroy();
        if (mExitProcessOnDestroy) {
            mHandler.postDelayed(() -> {
                Log.i(TAG, "link: stopping the EdgeTX process to release the DJI SDK");
                android.os.Process.killProcess(android.os.Process.myPid());
            }, 250);
        }
    }

    // ---------------------------------------------------------------- helpers --

    private void startLink() {
        try {
            // The card goes to the external files directory when the device has
            // one: /sdcard/Android/data/<pkg>/files/sdcard. No permission is
            // needed for it, and it can be edited over adb:
            //   adb push main.lua /sdcard/Android/data/com.edgetx.droidui/files/sdcard/SCRIPTS/
            final File external = getExternalFilesDir(null);
            final boolean ok = nativeStartLink(getFilesDir().getAbsolutePath(),
                    external != null ? external.getAbsolutePath() : null, getAssets());
            Log.i(TAG, "link: service start, firmware "
                    + (ok ? "running" : "FAILED to start"));
        } catch (Throwable t) {
            Log.w(TAG, "link: native start failed", t);
        }
    }

    /**
     * {@code FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE} is the accurate type (the
     * service exists to keep the RF module fed), but on a target that
     * requires the type's prerequisite - Android 14 and later for apps targeting
     * API 34 - it is rejected unless a USB device permission happens to be held.
     * {@code specialUse} has no prerequisite and is the declared fallback.
     */
    private void promoteToForeground() {
        final Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "link: connectedDevice type refused, trying specialUse", t);
            }
            try {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "link: specialUse type refused, trying untyped", t);
            }
        }

        try {
            startForeground(NOTIFICATION_ID, notification);
        } catch (Throwable t) {
            Log.w(TAG, "link: startForeground failed", t);
        }
    }

    private Notification buildNotification() {
        final Notification.Builder builder =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(this, CHANNEL_ID)
                        : new Notification.Builder(this);

        builder.setSmallIcon(R.drawable.ic_rc_link)
                .setContentTitle("EdgeTX link running")
            .setContentText("Sticks and switches keep going to the RF module")
                .setOngoing(true)
                .setShowWhen(false);
        final Intent stop = new Intent(this, RcLinkService.class).setAction(ACTION_STOP_LINK);
        int stopFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            stopFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        final PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, stopFlags);
        builder.addAction(R.drawable.ic_rc_link, "Stop EdgeTX", stopIntent);
        return builder.build();
    }

    private void stopDjiFlyInBackground() {
        try {
            final ActivityManager manager =
                    (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            manager.killBackgroundProcesses(DJI_FLY_PACKAGE);
            mDjiStopRequests++;
            if (mDjiStopRequests <= 3 || mDjiStopRequests % 12 == 0) {
                Log.i(TAG, "link: requested DJI Fly background stop (check "
                        + mDjiStopRequests + "); ground use only");
            }
        } catch (Throwable t) {
            Log.w(TAG, "link: could not request DJI Fly background stop", t);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        final NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        final NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "RC link", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the EdgeTX link to the RF module alive");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    /**
     * Keeps the CPU from suspending with the screen off. Without it the firmware's
     * threads are frozen the moment the RC's display goes dark, and the module
     * stops getting frames long before the app "was closed".
     */
    private void acquireWakeLock() {
        try {
            final PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power == null) {
                return;
            }
            mWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "edgetx:rc_link");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
            Log.i(TAG, "link: partial wake lock held");
        } catch (Throwable t) {
            Log.w(TAG, "link: no wake lock", t);
        }
    }

    private void releaseWakeLock() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        } catch (Throwable t) {
            Log.w(TAG, "link: could not release the wake lock", t);
        } finally {
            mWakeLock = null;
        }
    }
}
