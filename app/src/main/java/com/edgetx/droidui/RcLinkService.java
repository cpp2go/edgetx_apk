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
import java.util.concurrent.atomic.AtomicBoolean;

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
 *   <li>a screen wake lock is held on top of that, so the display stays on - and
 *       with it the firmware's own screen - for as long as the process lives,
 *       whether or not the UI is attached.
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
 *   <li>The ongoing notification is the way back in: tapping it reopens the UI, and
 *       its "Stop EdgeTX" action is the one way to end the link on purpose.
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

    /**
     * Holds the firmware back (or releases it). The activity's own native loop starts the
     * firmware whenever it finds it stopped - that is the fallback that keeps a UI without a
     * service from staying black - so a Java-side "do not start yet" would be pointless
     * without this: the firmware would come up with the card root it cannot use afterwards.
     */
    static native void nativeHoldLink(boolean hold);

    /** Holds the firmware back until the card has a place the user can reach. */
    static void holdLink(boolean hold) {
        try {
            nativeHoldLink(hold);
        } catch (Throwable t) {
            Log.w(TAG, "link: could not set the start gate", t);
        }
    }

    private PowerManager.WakeLock mWakeLock;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mDjiStopRequests;
    private boolean mExitProcessOnDestroy;
    /** Set once the service is being stopped on purpose: the DJI Fly killer has nothing left to do. */
    private volatile boolean mStopRequested;
    /** Set when the service stops but the firmware has to keep running (see promoteToForeground). */
    private boolean mKeepFirmwareOnStop;
    /** True while a start is in flight, so a redelivered start command does not spawn a second one. */
    private final AtomicBoolean mStartRequested = new AtomicBoolean();
    private final Runnable mDjiFlyGuard = new Runnable() {
        @Override
        public void run() {
            if (mStopRequested) {
                return;
            }
            stopDjiFlyInBackground();
            mHandler.postDelayed(this, DJI_FLY_CHECK_MS);
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
            mStopRequested = true;
            mHandler.removeCallbacks(mDjiFlyGuard);
            stopForegroundNow();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (EdgeTxApplication.isManuallyClosed(this)) {
            Log.i(TAG, "link: ignoring automatic service restart after manual close");
            mExitProcessOnDestroy = true;
            mStopRequested = true;
            mHandler.removeCallbacks(mDjiFlyGuard);
            stopForegroundNow();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // Booting the firmware seeds the simulated SD card first, which takes
        // seconds on the very first run: keep that off the main thread, where the
        // notification and the activity's lifecycle are handled.
        //
        // One start is enough: link_start() is idempotent, so a second thread would
        // only queue up on the lock the first one holds while it seeds the card.
        if (mStartRequested.compareAndSet(false, true)) {
            final Thread starter = new Thread(this::startLink, "rc-link-start");
            starter.setDaemon(true);
            starter.start();
        }

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
        mStopRequested = true;
        mHandler.removeCallbacks(mDjiFlyGuard);
        releaseWakeLock();

        final boolean stopFirmware = !mKeepFirmwareOnStop;
        final boolean exitProcess = mExitProcessOnDestroy;
        // link_stop() takes the same lock link_start() holds while it waits for the RC's
        // controls and seeds the card, and then joins the link thread: seconds of work,
        // none of it on the main thread.
        final Thread stopper = new Thread(() -> {
            if (stopFirmware) {
                try {
                    nativeStopLink();
                } catch (Throwable t) {
                    Log.w(TAG, "link: could not stop the firmware", t);
                }
            }
            if (exitProcess) {
                try {
                    // The notification goes away as part of this teardown; give the
                    // system that moment before the process disappears.
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                Log.i(TAG, "link: stopping the EdgeTX process to release the DJI SDK");
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, "rc-link-stop");
        stopper.setDaemon(true);
        stopper.start();

        super.onDestroy();
    }

    // ---------------------------------------------------------------- helpers --

    private void startLink() {
        boolean ok = false;
        try {
            // The card goes to the external files directory when the device has
            // one: /sdcard/Android/data/<pkg>/files/sdcard. No permission is
            // needed for it, and it can be edited over adb:
            //   adb push main.lua /sdcard/Android/data/com.edgetx.droidui/files/sdcard/SCRIPTS/
            final File external = getExternalFilesDir(null);
            ok = nativeStartLink(getFilesDir().getAbsolutePath(),
                    external != null ? external.getAbsolutePath() : null, getAssets());
            Log.i(TAG, "link: service start, firmware "
                    + (ok ? "running" : "FAILED to start"));
        } catch (Throwable t) {
            Log.w(TAG, "link: native start failed", t);
        }
        if (!ok) {
            // Nothing came up: let the next start command (a sticky redelivery, or the
            // activity's fallback) try again instead of being swallowed as a duplicate.
            mStartRequested.set(false);
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
            return;
        } catch (Throwable t) {
            Log.w(TAG, "link: startForeground failed", t);
        }

        // Started by startForegroundService() and never made foreground is the one state
        // the platform does not tolerate: the timeout that follows throws in this process
        // and the catches above cannot prevent it. Stopping the service removes the record
        // that timeout waits on. The firmware is left running on purpose - stopping it
        // would leave the activity with a simulator that cannot be started a second time
        // in the same process - so the activity's own native loop owns it from here, which
        // is exactly how the app worked before this service existed.
        mKeepFirmwareOnStop = true;
        stopSelf();
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

        // Tapping the notification is the way back into the UI while the link keeps
        // running: the activity attaches to the firmware that is already up.
        final Intent open = new Intent(this, EdgeTxActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        builder.setContentIntent(PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        final Intent stop = new Intent(this, RcLinkService.class).setAction(ACTION_STOP_LINK);
        final PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(R.drawable.ic_rc_link, "Stop EdgeTX", stopIntent);
        return builder.build();
    }

    /** Drops the ongoing notification; the flag is only spelled differently before API 33. */
    @SuppressWarnings("deprecation")
    private void stopForegroundNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
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
     * Keeps the display on for as long as this process lives.
     *
     * <p>A partial lock only kept the CPU running and let the panel go dark; the firmware's
     * screen is the whole point of the remote, and it has to stay readable whether or not the
     * activity is attached. {@code FLAG_KEEP_SCREEN_ON} covers that for a window, but the
     * requirement here is the process lifetime and a service has no window - so this is the
     * lock that does it. Deprecated since API 17 in favour of that flag, and still the only
     * way to hold the display on without one.
     */
    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        try {
            final PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power == null) {
                return;
            }
            mWakeLock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "edgetx:rc_link");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
            Log.i(TAG, "link: screen wake lock held, the display stays on with the process");
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
