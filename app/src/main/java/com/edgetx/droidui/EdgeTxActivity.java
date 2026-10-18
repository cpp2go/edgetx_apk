package com.edgetx.droidui;

import android.Manifest;
import android.app.NativeActivity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
 * <p>The simulated card in {@code /storage/emulated/0/EdgeTX} is why most of this exists, and
 * every supported remote needs a different grant to write there. The RC Pro (Android 10) has
 * {@code WRITE_EXTERNAL_STORAGE}, a runtime dialog that only an activity can raise. The RC Plus and
 * RC Plus 2 (Android 11) have "All files access" ({@code MANAGE_EXTERNAL_STORAGE}), which has no
 * dialog at all - the only way to it is a system settings page, put up from here rather than from
 * the Application because a background activity start would be refused without showing anything.
 * Without the grant the firmware keeps the card in the app's own directory, where no file manager
 * can reach it ("after a fresh install there is no EdgeTX folder"), so both are asked for again on
 * later launches until they are given.
 *
 * <p>The other one is a permission that needs a dialog: {@code UsbManager.requestPermission},
 * asked for again below until it is granted - see {@link RcRawJoystick}. {@code READ_LOGS} is
 * signature-level and adb-only, so it is deliberately not mentioned on screen.
 */
public class EdgeTxActivity extends NativeActivity {

    private static final String TAG = "EdgeTXUI";

    /** Both runtime permissions this app asks for go through this one request. */
    private static final int REQUEST_PERMISSIONS = 1;

    /** How often to put the USB dialog back up while the permission is missing. */
    private static final long USB_ASK_MS = 8000;

    /** The dialog right after the window appears, not one interval later. */
    private static final long USB_FIRST_ASK_MS = 2500;

    /** Delay before the card permission page is put up, counted from the window appearing. */
    private static final long SD_ACCESS_ASK_MS = 1500;

    /**
     * How many times to ask before leaving it alone until the app comes to the front again. Five
     * is enough that a dialog missed once is not the end of it, and few enough that a deliberate
     * "no" is not answered with a dialog every eight seconds for as long as the UI is up.
     */
    private static final int USB_ASKS = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int usbAsks;

    /**
     * How long to wait before putting the SD-card permission page up again while it is still
     * missing. Long enough that declining once is not answered with the page again a moment
     * later, short enough that a user who is still looking for the reason gets another chance
     * without restarting the app.
     */
    private static final long SD_ACCESS_ASK_EVERY_MS = 5 * 60 * 1000;

    /** When the SD-card permission page was last put up, 0 = never in this launch. */
    private long sdAccessAskedAt;

    /** True while the storage dialog this app raised in {@code onCreate} is unanswered. */
    private boolean storageRequestPending;

    /**
     * Puts the SD-card permission page up while the card has nowhere user-visible to live.
     *
     * <p>What "the permission" is depends on the remote's Android version, and all three of the
     * supported ones need this:
     *
     * <ul>
     *   <li>RC Pro (Android 10) - {@code WRITE_EXTERNAL_STORAGE}, a normal runtime dialog, asked
     *       for in {@code onCreate}; when it is denied for good only the app's own permission
     *       page can turn it back on.
     *   <li>RC Plus and RC Plus 2 (Android 11) - {@code MANAGE_EXTERNAL_STORAGE} ("All files
     *       access"), which has no dialog at all: only a system settings page grants it.
     * </ul>
     *
     * <p>Without the grant the firmware keeps the card in the app's own directory, where no file
     * manager can see it - the "after a fresh install there is no EdgeTX folder" report. That is
     * why this is not a one-off: it comes back on the next launch (and every five minutes while
     * the app stays in front) until the card is where it belongs.
     */
    private final Runnable sdAccessAsk = new Runnable() {
        @Override
        public void run() {
            if (storageRequestPending || cardDirectoryReady()) {
                return;
            }
            final long now = System.currentTimeMillis();
            if (now - sdAccessAskedAt < SD_ACCESS_ASK_EVERY_MS) {
                return;
            }
            sdAccessAskedAt = now;
            try {
                Toast.makeText(EdgeTxActivity.this, Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                ? "请允许\"所有文件访问\"，EdgeTX 目录才会出现在 /storage/emulated/0/EdgeTX"
                                : "请允许存储权限，EdgeTX 目录才会出现在 /storage/emulated/0/EdgeTX",
                        Toast.LENGTH_LONG).show();

                final Intent page = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        ? EdgeTxApplication.sdAccessSettings(EdgeTxActivity.this)
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                startActivity(page);
                Log.i(TAG, "sdcard: showing the page that grants the card directory");
            } catch (Throwable t) {
                // Some vendor builds have no such page; the firmware then keeps the card in
                // the app's own directory, which still works.
                Log.w(TAG, "sdcard: could not open the card permission page", t);
            }
        }
    };

    /**
     * Whether the simulated card can be created where a user can reach it. Android 11 and later
     * gate that on "All files access"; Android 10 uses the legacy storage permission instead,
     * and older versions hand it out at install time.
     */
    private boolean cardDirectoryReady() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return Environment.isExternalStorageManager();
            }
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            Log.w(TAG, "sdcard: could not look up the card permission", t);
            return true;  // never keep asking because of an exception
        }
    }

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
        EdgeTxApplication.startFromActivity(this);
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
                storageRequestPending = true;
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

        resumeCardDirectory();
    }

    /**
     * Creates the card directory as soon as the grant is there, and asks for the grant while it
     * is not. Called from {@code onResume} so that the grant page is put up from a window the
     * system will actually honour, and again when the user comes back from that page.
     */
    private void resumeCardDirectory() {
        if (EdgeTxApplication.isWaitingForCardAccess()) {
            if (cardDirectoryReady()) {
                Log.i(TAG, "sdcard: granted, booting with /storage/emulated/0/EdgeTX");
                EdgeTxApplication.startFromActivity(this);
                return;
            }
            if (sdAccessAskedAt != 0) {
                // The page was put up in this launch and came back without a grant: boot with
                // the card in the app's own directory rather than not at all.
                Log.i(TAG, "sdcard: no grant, booting with the card in the app directory");
                EdgeTxApplication.startWithoutCardAccess(this);
                return;
            }
        } else if (cardDirectoryReady()) {
            return;
        }

        handler.removeCallbacks(sdAccessAsk);
        handler.postDelayed(sdAccessAsk, SD_ACCESS_ASK_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // A request made without a window is refused without a dialog, so do not ask while the
        // firmware is not the thing on screen; onResume starts the asking again.
        handler.removeCallbacks(usbAsk);
        // The grant page itself is what pauses this activity, so this is also what keeps it
        // from going up again the moment the user comes back from it.
        handler.removeCallbacks(sdAccessAsk);
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
                storageRequestPending = false;
                Log.i(TAG, "sdcard: WRITE_EXTERNAL_STORAGE " + (granted
                        ? "granted - the card is created in /storage/emulated/0/EdgeTX"
                        : "denied - the card stays in the app's own directory"));
                if (!granted) {
                    // Do not put the settings page on top of the dialog just answered: give it
                    // a few minutes, or the next launch.
                    sdAccessAskedAt = System.currentTimeMillis();
                    Toast.makeText(this, "存储权限被拒绝，EdgeTX 目录无法创建；可在应用权限中开启",
                            Toast.LENGTH_LONG).show();
                }
                // The firmware was held back until this answer (see EdgeTxApplication): boot
                // now, with the card in the user directory if the grant arrived.
                if (EdgeTxApplication.isWaitingForCardAccess()) {
                    if (granted) {
                        EdgeTxApplication.startFromActivity(this);
                    } else {
                        EdgeTxApplication.startWithoutCardAccess(this);
                    }
                }
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
