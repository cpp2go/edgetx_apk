package com.edgetx.droidui;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * The RC's sticks and dials, read straight off the internal joystick's USB interface.
 *
 * <p><b>Why this exists.</b> The sticks reach the app through the DJI SDK at 8-14 Hz - a
 * stick that moves continuously is published about every 90 ms, and gaps of 130-300 ms
 * (occasionally 600) were measured - while the very same hardware produces a new position
 * every 2.5 ms: 71 847 reports in 180 s, i.e. 400/s, read straight from the device. The
 * SDK rate is fixed inside DJI's service, so the only way to a responsive stick is to read
 * the device itself.
 *
 * <p>The other two routes were measured and are dead ends: the framework dispatches no
 * MotionEvent for the joystick at all (Android's input dispatcher holds key events only,
 * even with the app's window focused), and the kernel's input node is {@code root:input
 * 0660}, which an app cannot open without a signature-level permission. What an app can do
 * is take the USB interface away from the kernel's driver
 * ({@code claimInterface(iface, true)}) - the kernel driver is re-attached when the
 * connection is closed - and read the reports itself.
 *
 * <p><b>The report</b> (18 bytes, measured on the RC Plus 2):
 *
 * <pre>
 *  0     frame type, always 0x02 - the only type seen in 71 847 reports
 *  1     always 0x0E
 *  2-3   rolling counter, changes every frame
 *  4-15  six 16-bit little-endian axes, all centred on 1024 and spanning +-660,
 *        which is exactly what the SDK reports for the same controls once the
 *        centre is subtracted, so the two sources are interchangeable:
 *          4  left horizontal   6  left vertical
 *          8  right horizontal 10  right vertical
 *         12  left dial        14  right dial
 *  16-17 buttons, one bit each: 16 bit 1 (0x02) = RTN, 16 bit 2 (0x04) = the record button,
 *        17 bits 0..4 = the 5-way (up, down, left, right, centre - confirmed by
 *        pressing each in turn). The RC Pro sets two more bits in byte 16 - 0x08 (photo)
 *        and 0x01 (the round button) - which the RC Plus 2 never does, and both of those
 *        are in these reports instead of in the SDK, see pushButtons(). Of the rest, the
 *        takeoff, flight mode, pause, C1..C3, go-home and scroll wheel were all pressed
 *        while this reader held the interface and not one byte moved, so those still come
 *        from the SDK.
 * </pre>
 *
 * <p><b>What is not the report is not decoded.</b> A frame is used only when its payload looks
 * like the report measured here - the six axis fields all sitting inside their travel (see
 * {@link #isStickReport}). The endpoint carries more than one kind of report, and a frame of
 * another kind read as axes is six zeroes, which is -100% on all four sticks; that is the
 * state in which the sticks "do nothing and stay at the lowest position", and it also kept
 * the interface claimed, so the SDK could not cover for it. Frames that fail the check are
 * logged with their bytes and skipped.
 *
 * <p><b>How it is used.</b> The six axes are pushed into exactly the entry points the SDK
 * uses ({@link DjiMsdkBridge#nativeOnDjiStick}, {@link DjiMsdkBridge#nativeOnDjiDial}), and
 * so are the buttons the reports carry, so nothing downstream changes. The SDK keeps
 * running and stays the fallback: as long as this reader is feeding the firmware
 * ({@link #ownsInputs()}) the SDK's copies of the same inputs are ignored, and if this
 * reader ever stops - the device taken away, the app moved to the background and killed -
 * the SDK takes them back without a restart. Everything the reports do not carry is
 * unaffected either way.
 *
 * <p><b>A reader that stops is put back.</b> The report stream is not guaranteed - the reader
 * gives the interface back when nothing arrives for {@link #SILENT_LIMIT} - and what a user
 * reports as "after DJI Fly is started, the sticks do nothing" is exactly that state: the raw
 * reports gone, and the SDK fallback unable to cover them, because DJI's own app
 * ({@code dji.go.v5}) starves the SDK of the same remote. Nothing used to restart the reader
 * in that state, so the sticks stayed dead for the rest of the process; the supervisor in this
 * class now does. What the joystick itself does while DJI Fly runs has not been measured - the
 * reader's own lines ("no reports for 4000 ms", a claim that fails, or the restart that
 * follows) are what a report of this needs next.
 *
 * <p>Verbose logging (one line per change in a frame, plus the SDK-versus-raw comparison)
 * is enabled only while the trigger file {@code hid_probe} exists next to the app's
 * external files, so a normal build stays quiet.
 */
final class RcRawJoystick {

    private static final String TAG = "EdgeTXUI";

    /** The RC's internal joystick: "DJI Virtual Joystick" (2ca3:1501), one interface. */
    private static final int VENDOR_ID = 0x2CA3;
    private static final int PRODUCT_ID = 0x1501;

    private static final String ACTION_PERMISSION = "com.edgetx.droidui.RAW_JOYSTICK_PERMISSION";

    /** Verbose logging is on only while this file exists. */
    private static final String TRIGGER = "hid_probe";

    private static final int AXIS_COUNT = 6;
    private static final int AXIS_FIRST = 4;
    private static final int AXIS_CENTRE = 1024;
    private static final int STICK_COUNT = 4;

    private static final int BUTTON_FIRST = AXIS_FIRST + AXIS_COUNT * 2;   // 16
    private static final int BUTTON_LAST = BUTTON_FIRST + 1;               // 17

    /**
     * Byte 0 and byte 1 of the report the six axes are in: 0x02 is the report id and 0x0E is
     * the same marker in every one of the 71 847 reports measured. They do not decide whether
     * a frame is used - the payload does, see {@link #isStickReport} - but a stick report that
     * arrives under another id is said once in the log (see {@link #noteReportId}).
     */
    private static final int REPORT_ID = 0x02;
    private static final int REPORT_MARK = 0x0E;

    /**
     * How far an axis may sit from {@link #AXIS_CENTRE} and still be a stick reading. The six
     * axes measured exactly +-660 at full deflection; the margin is for a remote whose
     * calibration is a little wider.
     */
    private static final int AXIS_TRAVEL_MAX = 900;

    /**
     * How many of the six axis fields have to read like a stick before a frame is used.
     * Every one of them does in the report measured; the two that may be off are for a remote
     * whose calibration is wider than {@link #AXIS_TRAVEL_MAX}, not for a frame of another
     * kind - a frame that is not a stick report has none of them inside it.
     */
    private static final int AXIS_MIN_INSIDE = AXIS_COUNT - 2;

    /**
     * How long a freshly claimed interface may deliver nothing but frames this reader cannot
     * use before it is given back. The stick report is the same stream at 400 frames a second,
     * so a second is hundreds of frames; holding the interface longer than that for a stream
     * that carries no stick report only keeps the remote's own readers away from it.
     */
    private static final int STARTUP_GRACE_MS = 1000;

    /** A frame this reader cannot decode is reported once per this interval, not per frame. */
    private static final int REJECT_LOG_MS = 10000;

    /**
     * Sign of each axis, in the order of the report (left H, left V, right H, right V,
     * left dial, right dial). The raw axes are reported with the same range the SDK
     * reports, so the working assumption is that no sign is flipped - the verbose log
     * prints the SDK and raw values side by side while a stick moves, which is what
     * settles it if a control ever comes out backwards.
     */
    private static final int[] AXIS_SIGN = {1, 1, 1, 1, 1, 1};

    /** What the 5-way's five directions are called: byte 17, low bit first. */
    private static final String[] FIVE_WAY_NAMES = {"up", "down", "left", "right", "centre"};

    /** Those five bits, in the same order. */
    private static final int[] FIVE_WAY_BITS = {0x01, 0x02, 0x04, 0x08, 0x10};

    /** And what joystick.cpp calls them - the ids the SDK's own 5-way reports. */
    private static final int[] FIVE_WAY_IDS = {
            DjiMsdkBridge.BTN_UP, DjiMsdkBridge.BTN_DOWN, DjiMsdkBridge.BTN_LEFT,
            DjiMsdkBridge.BTN_RIGHT, DjiMsdkBridge.BTN_PRESS,
    };

    /**
     * Byte 16 carries the buttons, one bit each: 0x02 is the return button, 0x04 the record
     * button, and the RC Pro adds two more - 0x08 (photo) and 0x01 (the round button beside
     * the screen). The RC Plus 2 never sets those last two, so decoding them costs it nothing.
     * Both of the RC Pro's are in this report *instead* of in the SDK - its shutter never
     * arrives as {@code KeyShutterButtonDown} and its round button is in no SDK data at all -
     * which is why they were dead before: see {@link DjiMsdkBridge#onRawShutter} and
     * joystick.cpp's nativeOnRawButton.
     */
    private static final int RTN_BIT = 0x02;
    private static final int RECORD_BIT = 0x04;
    private static final int SHUTTER_BIT = 0x08;
    private static final int ROUND_BIT = 0x01;

    /**
     * If the device stops delivering for this long the reader gives the interface back
     * and the SDK takes the axes over again. The device sends all the time, so a gap this
     * long means something took it away from us.
     */
    private static final int SILENT_LIMIT = 4000;   // ms

    /** One status line a minute, so a log always says whether this path is alive. */
    private static final int STATUS_MS = 60000;

    /** True while reports are being fed to the firmware: the SDK's copies are redundant. */
    private static volatile boolean sOwned;

    /**
     * True from the moment a reader thread is started until it returns. Kept apart from
     * {@link #sOwned}, which only says the reports are flowing: a reader that is still
     * claiming the interface must not be started a second time.
     */
    private static volatile boolean sReaderStarted;

    /**
     * Set once a reader has run, and never cleared: from then on a reader that stops is this
     * app's job to put back. It is also what bounds the retries below - a device that never
     * yielded a reader (no joystick at all) is never retried.
     */
    private static volatile boolean sWanted;

    /** The application context, kept for the restarts: a reader outlives the activity. */
    private static volatile Context sApp;

    /**
     * How long the supervisor below waits before putting a reader back, and how far the wait
     * grows while it keeps failing.
     *
     * <p>The interface can be taken away from this reader at runtime, and until now nothing
     * gave it back: the reader noticed the silence, released the interface and returned, and
     * the only caller of {@link #start} left that could restart it was the activity's resume -
     * which is not reached while the app stays in front, and which stopped calling at all once
     * the USB dialog had been answered. So a member whose report stream stopped stayed on the
     * SDK for the rest of the process, and the SDK is exactly what DJI's own app
     * ({@code dji.go.v5}) starves - "after DJI Fly is started, the sticks do nothing".
     */
    private static final int RESTART_MIN_MS = 1000;
    private static final int RESTART_MAX_MS = 15000;
    private static int sRestartDelayMs = RESTART_MIN_MS;
    private static int sRestarts;
    private static Handler sRestartHandler;

    /** The permission receiver is registered once, however often {@link #start} is called. */
    private static volatile boolean sReceiverRegistered;

    /** When the last permission request went out, so the asking can be spaced out. */
    private static volatile long sAskedAt;

    /** How long to wait before asking again: the answer normally takes a couple of seconds. */
    private static final int ASK_AGAIN_MS = 5000;

    /**
     * The user's answer to the permission dialog, null until they have chosen.
     *
     * <p>Once it is set the dialog is never raised again in this run: asking a second time after
     * the user had already answered is what made the same dialog reappear on every resume, and on
     * every eighth second while the UI stayed up. "OK" is then also saved for good - see
     * {@link EdgeTxApplication#rememberUsbDeviceAllowed} - while "Cancel" is asked about once on
     * the next launch. The one exception is a reader that was running and lost its grant with the
     * device (see {@link #sReaskedAfterAllow}): that is asked about once more, because without a
     * dialog the grant never comes back.
     */
    private static volatile Boolean sAnswer;

    /**
     * Set the first time a saved "OK" is found without the system permission behind it - or a
     * reader that was running finds the device back without its grant - so the dialog is raised
     * for that case once per run and no more.
     */
    private static volatile boolean sReaskedAfterAllow;

    /** Verbose flag, decided once at start. */
    private static volatile boolean sVerbose;

    /** Last raw value per axis, so only real changes are pushed. */
    private static final int[] sRawValue = new int[AXIS_COUNT];
    private static final boolean[] sRawSeen = new boolean[AXIS_COUNT];

    /** An axis whose field was outside its travel, reported once (see pushAxes). */
    private static final boolean[] sAxisSkipped = new boolean[AXIS_COUNT];

    /** Last SDK value per axis, for the verbose comparison line. */
    private static final int[] sSdkValue = new int[AXIS_COUNT];

    /** The two button bytes as last seen, so only real edges are sent on. */
    private static boolean sButtonsKnown;
    private static int sButtonFirst;
    private static int sButtonSecond;

    static {
        for (int index = 0; index < AXIS_COUNT; index++) {
            sRawValue[index] = AXIS_CENTRE;
        }
    }

    private RcRawJoystick() {}

    // ---- start-up -----------------------------------------------------------

    /**
     * Called from {@code EdgeTxApplication.onCreate}, and again from the launcher activity
     * whenever it comes to the front.
     *
     * <p>It is idempotent, which is what the second caller is for: a USB permission can only be
     * granted through a dialog, and a request made from the Application - before any activity is
     * up - is refused by the system without ever being shown. Measured on the RC Pro after a
     * fresh install: "asking for permission for /dev/bus/usb/001/007" 6 s before "activity
     * started", then "USB permission denied" 1.5 s later, and with the raw reports gone the
     * photo and record buttons went with them. So the request is only ever made while the
     * launcher activity is on screen (see {@code EdgeTxApplication.isUiVisible}), where the
     * dialog is real, and the answer the user gives - allowed or refused - is kept for the rest
     * of the run: the same dialog is never put up twice.
     */
    static void start(Context context) {
        try {
            sApp = context.getApplicationContext();
            sVerbose = new File(context.getExternalFilesDir(null), TRIGGER).isFile();

            final UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (manager == null) {
                Log.w(TAG, "raw joystick: no USB service");
                return;
            }

            if (sVerbose) {
                listDevices(manager);
            }

            final UsbDevice device = findJoystick(manager);
            if (device == null) {
                Log.w(TAG, "raw joystick: the joystick is not in the USB device list");
                return;
            }

            if (manager.hasPermission(device)) {
                if (!sReaderStarted) {
                    readInBackground(manager, device);
                }
                return;
            }

            boolean reask = false;
            if (sAnswer != null) {
                if (!sWanted || sReaskedAfterAllow) {
                    // The user answered in this run; the dialog is not put up a second time.
                    return;
                }
                // A reader was running and the device came back without its grant: a
                // re-enumeration hands out a new device node, and the answer does not follow
                // it. Android cannot restore a grant without a dialog, so it is asked once
                // more - and once only, which is what sReaskedAfterAllow says afterwards.
                reask = true;
            }
            if (!EdgeTxApplication.isUiVisible()) {
                // The dialog is a system activity shown over this app's window. Asked for from
                // the Application - before any window is up - the request is refused without
                // ever being shown, which would look like an answer the user never gave. The
                // launcher activity asks from onResume, where the dialog is real.
                Log.i(TAG, "raw joystick: no window yet, the dialog is asked for once it is up");
                return;
            }

            final Context app = context.getApplicationContext();
            registerPermissionReceiver(app);

            if (System.currentTimeMillis() - sAskedAt < ASK_AGAIN_MS) {
                return;
            }

            if (!reask && sAnswer == null && wasAllowedBefore(context, device)) {
                if (sReaskedAfterAllow) {
                    return;
                }
                // Allowed on an earlier launch, but the system hands out no permission for this
                // one - the grant cannot be restored without a dialog, and the alternative is a
                // joystick that stays silent. Asked once more, and not again after that.
                reask = true;
            }
            if (reask) {
                sReaskedAfterAllow = true;
                Log.i(TAG, "raw joystick: " + (sAnswer != null
                        ? "the joystick came back without its permission"
                        : "allowed earlier but the system grants nothing now")
                        + " - asking once more");
            }
            sAskedAt = System.currentTimeMillis();

            final Intent intent = new Intent(ACTION_PERMISSION);
            intent.setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The system delivers the grant as an extra on this intent, which an
                // immutable PendingIntent would not carry.
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            Log.i(TAG, "raw joystick: asking for permission for " + device.getDeviceName());
            manager.requestPermission(device, PendingIntent.getBroadcast(app, 0, intent, flags));
        } catch (Throwable t) {
            Log.w(TAG, "raw joystick: could not start", t);
        }
    }

    /** The answer to a permission request, listened for once for the life of the process. */
    private static void registerPermissionReceiver(final Context app) {
        if (sReceiverRegistered) {
            return;
        }
        sReceiverRegistered = true;
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context received, Intent intent) {
                if (intent == null || !ACTION_PERMISSION.equals(intent.getAction())) {
                    return;
                }
                final boolean granted =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                sAnswer = granted;
                final UsbDevice answeredFor = deviceFrom(intent);
                if (granted && answeredFor != null) {
                    // Saved, so this dialog does not come back on the next launch either.
                    EdgeTxApplication.rememberUsbDeviceAllowed(app, identity(answeredFor));
                }
                Log.i(TAG, "raw joystick: USB permission " + (granted ? "granted - not asking"
                        + " again" : "denied - asked again on the next launch"));
                if (!granted) {
                    // Never fatal: this class only carries the reports the SDK cannot, so a
                    // refusal leaves the app working with the buttons the SDK does deliver.
                    return;
                }
                final UsbManager manager =
                        (UsbManager) app.getSystemService(Context.USB_SERVICE);
                final UsbDevice permitted = answeredFor != null ? answeredFor : deviceFrom(intent);
                if (manager != null && permitted != null && !sReaderStarted) {
                    readInBackground(manager, permitted);
                }
            }
        }, new IntentFilter(ACTION_PERMISSION));
    }

    /**
     * Whether this reader is feeding the firmware at the moment - the sticks, the dials, the
     * 5-way and the record button. While it is, the SDK's own copies of those are only
     * older, and for the 5-way they would be a second press of the same button; everything
     * the reports do not carry still comes from the SDK.
     */
    static boolean ownsInputs() {
        return sOwned;
    }

    /** True once the user has answered the USB permission dialog, either way. */
    static boolean hasAnswer() {
        return sAnswer != null;
    }

    /** One stick value from the SDK: recorded, and compared in the verbose log. */
    static void noteSdkStick(int axis, int value) {
        if (axis >= 0 && axis < STICK_COUNT) {
            sSdkValue[axis] = value;
        }
    }

    /** One dial value from the SDK: recorded, and compared in the verbose log. */
    static void noteSdkDial(int dial, int value) {
        final int index = STICK_COUNT + dial;
        if (dial >= 0 && index < AXIS_COUNT) {
            sSdkValue[index] = value;
        }
    }

    /**
     * One button edge from the raw reports, put through the very same handler Android's
     * key events go through, so the key map file and EdgeTX's own long-press timing apply
     * unchanged. Only the return button needs this - the 5-way and everything else still
     * arrive from Android or the DJI SDK.
     */
    private static native void nativeOnRawKey(int keycode, boolean down);

    // ---- the reader ---------------------------------------------------------

    /**
     * Puts a reader back after one has stopped, and keeps trying while it cannot.
     *
     * <p>Until this existed, nothing did. The reader gives the interface back when no report
     * arrives for {@link #SILENT_LIMIT}, and the only callers of {@link #start} were the
     * application (once per process) and the launcher activity's resume - which also stopped
     * calling once the USB dialog had been answered. So a report stream that stopped stayed
     * stopped for the rest of the process.
     *
     * <p>That is what a user reports as "after DJI Fly is started, the sticks do nothing": the
     * SDK fallback cannot cover such a gap, because DJI's own app ({@code dji.go.v5}) starves
     * the SDK of the same remote (see the silence notes in {@link DjiMsdkBridge}). The reader is
     * the source that does not depend on the SDK, so it is the one that has to come back.
     *
     * <p>One second after a reader that did deliver - the device is there and merely has to be
     * claimed again. A device that cannot be claimed at all backs off to one attempt every
     * {@link #RESTART_MAX_MS}, so a remote in a mode where the joystick is genuinely gone is not
     * hammered, and every attempt is logged only while it is still worth reading.
     */
    private static final Runnable sRestart = new Runnable() {
        @Override
        public void run() {
            final Context app = sApp;
            if (app == null || !sWanted || sReaderStarted) {
                return;
            }
            final UsbManager manager =
                    (UsbManager) app.getSystemService(Context.USB_SERVICE);
            if (manager == null) {
                return;
            }
            final UsbDevice device = findJoystick(manager);
            if (device == null) {
                // Gone while it re-enumerates; the next attempt may find the new node.
                restartReader(0);
                return;
            }
            if (!manager.hasPermission(device)) {
                // The node came back without the grant. Only a dialog can give it back, and
                // start() is where one is raised - it never asks without a window, so there is
                // nothing to do while the UI is away.
                if (EdgeTxApplication.isUiVisible()) {
                    start(app);
                }
                restartReader(0);
                return;
            }
            readInBackground(manager, device);
        }
    };

    private static void restartReader(int reports) {
        final Context app = sApp;
        if (app == null || !sWanted) {
            return;
        }
        final Handler handler = restartHandler();
        if (handler == null) {
            return;
        }
        final int delay;
        synchronized (RcRawJoystick.class) {
            if (reports > 0) {
                sRestartDelayMs = RESTART_MIN_MS;   // it did work: get it back at once
            }
            delay = sRestartDelayMs;
            sRestartDelayMs = Math.min(sRestartDelayMs * 2, RESTART_MAX_MS);
            sRestarts++;
            if (reports > 0) {
                Log.i(TAG, "raw joystick: the reader stopped after " + reports + " report(s);"
                        + " restarting it in " + delay + " ms");
            } else if (sRestarts <= 3 || sRestarts % 20 == 0) {
                Log.i(TAG, "raw joystick: no reader yet, trying again in " + delay + " ms"
                        + " (attempt " + sRestarts + ")");
            }
        }
        handler.removeCallbacks(sRestart);
        handler.postDelayed(sRestart, delay);
    }

    private static Handler restartHandler() {
        synchronized (RcRawJoystick.class) {
            if (sRestartHandler == null) {
                final Looper looper = Looper.getMainLooper();
                if (looper == null) {
                    return null;
                }
                sRestartHandler = new Handler(looper);
            }
            return sRestartHandler;
        }
    }

    /** The reader is done feeding the sticks, whatever the reason: the SDK has them again. */
    private static void releaseOwnership() {
        if (!sOwned) {
            return;
        }
        sOwned = false;
        Log.i(TAG, "raw joystick: the sticks and dials are the SDK's again until the reader"
                + " returns");
    }

    private static void readInBackground(final UsbManager manager, final UsbDevice device) {
        if (sReaderStarted) {
            return;
        }
        sReaderStarted = true;
        sWanted = true;
        final Thread thread = new Thread(() -> {
            int reports = 0;
            try {
                reports = read(manager, device);
            } catch (Throwable t) {
                Log.w(TAG, "raw joystick: reader stopped", t);
            } finally {
                releaseOwnership();
                sReaderStarted = false;
                restartReader(reports);
            }
        }, "raw-joystick");
        thread.setDaemon(true);
        thread.start();
    }

    /** @return how many reports were delivered, so the restart can tell "taken away" from
     *  "never worked". */
    private static int read(UsbManager manager, UsbDevice device) {
        final UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            Log.w(TAG, "raw joystick: openDevice returned null (permission not held?)");
            return 0;
        }

        final UsbInterface iface = device.getInterface(0);
        // force = true detaches the kernel's own driver, which is the point: it is what
        // lets an app see the reports. The driver comes back when this connection closes.
        if (!connection.claimInterface(iface, true)) {
            Log.w(TAG, "raw joystick: could not claim interface " + iface.getId()
                    + " (another reader holds it)");
            connection.close();
            return 0;
        }
        Log.i(TAG, "raw joystick: claimed " + device.getDeviceName() + " interface "
                + iface.getId() + " (" + (sVerbose ? "verbose" : "quiet") + ")");

        try {
            final UsbEndpoint endpoint = findInEndpoint(iface);
            if (endpoint == null) {
                Log.w(TAG, "raw joystick: no IN endpoint on interface " + iface.getId());
                return 0;
            }

            final int size = Math.max(endpoint.getMaxPacketSize(), 16);
            final ByteBuffer buffer = ByteBuffer.allocate(size);
            UsbRequest request = null;
            try {
                request = new UsbRequest();
                if (!request.initialize(connection, endpoint)) {
                    request = null;
                }
            } catch (Throwable t) {
                request = null;
            }
            final byte[] fallback = new byte[size];

            final Map<Integer, FrameLog> logs = new HashMap<>();
            final byte[] frame = new byte[size];
            final long start = System.currentTimeMillis();
            long windowStart = start;
            int reports = 0;
            int inWindow = 0;
            int ignored = 0;
            long lastReportMs = start;
            int counterBytes = 0;

            while (true) {
                final int length;
                if (request != null) {
                    buffer.clear();
                    if (!request.queue(buffer, buffer.capacity())) {
                        Log.w(TAG, "raw joystick: queue() failed");
                        break;
                    }
                    final UsbRequest done;
                    try {
                        done = connection.requestWait(500);
                    } catch (TimeoutException e) {
                        // Nothing arrived inside the window. Cancel it before queueing
                        // again, or the next queue() fails with it still pending.
                        request.cancel();
                        if (System.currentTimeMillis() - lastReportMs > SILENT_LIMIT) {
                            Log.w(TAG, "raw joystick: no reports for " + SILENT_LIMIT
                                    + " ms, giving the interface back");
                            break;
                        }
                        continue;
                    }
                    if (done != request) {
                        // A different request completed. Nothing came to us, so this counts as
                        // silence like a timeout does - otherwise a stream that stopped would
                        // spin here for ever and never reach the restart that follows a stop.
                        if (System.currentTimeMillis() - lastReportMs > SILENT_LIMIT) {
                            Log.w(TAG, "raw joystick: no reports for " + SILENT_LIMIT + " ms");
                            break;
                        }
                        continue;
                    }
                    length = buffer.position();
                    System.arraycopy(buffer.array(), 0, frame, 0, Math.min(length, frame.length));
                } else {
                    length = connection.bulkTransfer(endpoint, fallback, fallback.length, 500);
                    if (length > 0) {
                        System.arraycopy(fallback, 0, frame, 0, Math.min(length, frame.length));
                    }
                }

                if (length <= 0) {
                    if (System.currentTimeMillis() - lastReportMs > SILENT_LIMIT) {
                        Log.w(TAG, "raw joystick: no reports for " + SILENT_LIMIT + " ms");
                        break;
                    }
                    continue;
                }

                // Only a frame whose payload reads like the six stick axes is used. The device
                // sends other reports too, and a frame of another kind read as a stick position
                // is six zeroes - every axis below the bottom of its travel - which is what
                // pinned all four sticks to -100% and made them look dead. See isStickReport().
                if (!isStickReport(frame, length)) {
                    ignored++;
                    noteUnusable(frame, length);
                    // A stream of frames this reader cannot use is as good as no stream: it
                    // must reach the restart below rather than hold the interface for ever -
                    // and while no stick report has arrived at all, it is given back after
                    // STARTUP_GRACE_MS so the remote's own readers get the device again.
                    final long limit = reports == 0 ? STARTUP_GRACE_MS : SILENT_LIMIT;
                    if (System.currentTimeMillis() - lastReportMs > limit) {
                        Log.w(TAG, "raw joystick: nothing usable for " + limit
                                + " ms (" + ignored + " frame(s) ignored), giving the interface"
                                + " back");
                        break;
                    }
                    continue;
                }

                noteReportId(frame);
                reports++;
                inWindow++;
                lastReportMs = System.currentTimeMillis();

                // Byte 2-3 is the counter the device itself puts in every frame.
                counterBytes = ((frame[2] & 0xFF) | ((frame[3] & 0xFF) << 8)) & 0xFFFF;

                pushAxes(frame, length);
                pushButtons(frame, length);

                if (sVerbose) {
                    logFrame(logs, frame, length, lastReportMs - start);
                }

                if (lastReportMs - windowStart >= STATUS_MS) {
                    Log.i(TAG, "raw joystick: " + (inWindow * 1000 / (lastReportMs - windowStart))
                            + " report(s)/s, counter " + counterBytes + ", sticks "
                            + describeAxes());
                    windowStart = lastReportMs;
                    inWindow = 0;
                }
            }

            if (ignored > 0) {
                Log.i(TAG, "raw joystick: stopped after " + reports + " report(s), " + ignored
                        + " frame(s) this reader cannot decode");
            } else {
                Log.i(TAG, "raw joystick: stopped after " + reports + " report(s)");
            }
            return reports;
        } finally {
            releaseOwnership();
            connection.releaseInterface(iface);
            connection.close();
            if (sVerbose) {
                logInputDevices();
            }
        }
    }

    /**
     * Whether a frame carries the six axes.
     *
     * <p>Until this check existed every frame the endpoint delivered was decoded as if bytes
     * 4..15 were the six axes and 16..17 the buttons, whatever kind of report it was
     * ({@link #logFrame} keeps one record per report id, so the device does send more than
     * one kind). A frame of another kind - a status report, or whatever the joystick sends
     * while the remote is linked to an aircraft - has zeroes there. Zero is far below the
     * bottom of every axis's travel, and {@link #pushAxes} turned it into -660, i.e. -100% on
     * all four stick channels. That is the "sticks do nothing and stay at the lowest
     * position" state: the reader kept holding the interface, so the SDK's copies of the
     * sticks were ignored as well, and every frame pinned them to the bottom again.
     *
     * <p>A frame is therefore used only when its payload looks like the report measured
     * here: the full 18 bytes, and at least {@link #AXIS_MIN_INSIDE} of the six axis fields
     * inside their travel ({@link #AXIS_TRAVEL_MAX}). Everything else is logged with its
     * bytes and skipped, and it does not take the sticks away from the SDK either. A single
     * field outside its travel is not published at all - {@link #pushAxes} keeps the last
     * position for that axis - so no frame can pin one channel to an end of its range either.
     *
     * <p>The check is on the payload rather than only on the report id ({@link #REPORT_ID},
     * {@link #REPORT_MARK}) because the id is not known to survive a change of the remote's
     * state, while the six axes are the thing this reader is after. A frame of another kind
     * cannot pass by accident: with four 16-bit fields around 1024 and none at zero, that is
     * a stick reading, not a status report.
     */
    private static boolean isStickReport(byte[] data, int length) {
        return rejection(data, length) == null;
    }

    /** Whether a raw field reads like that axis, i.e. inside its travel (see AXIS_TRAVEL_MAX). */
    private static boolean insideTravel(int value) {
        return value >= AXIS_CENTRE - AXIS_TRAVEL_MAX && value <= AXIS_CENTRE + AXIS_TRAVEL_MAX;
    }

    /** Why a frame is not a stick report, or null when it is one. */
    private static String rejection(byte[] data, int length) {
        if (length < BUTTON_LAST + 1) {
            return "only " + length + " byte(s)";
        }
        int inside = 0;
        for (int index = 0; index < AXIS_COUNT; index++) {
            final int offset = AXIS_FIRST + index * 2;
            if (insideTravel((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8))) {
                inside++;
            }
        }
        if (inside < AXIS_MIN_INSIDE) {
            return "only " + inside + " of " + AXIS_COUNT + " axes read like a stick ("
                    + describeRawAxes(data) + ", id " + hex2(data[0] & 0xFF) + ' '
                    + hex2(data[1] & 0xFF) + ')';
        }
        return null;
    }

    /** The six raw axis fields of a frame, for the log. */
    private static String describeRawAxes(byte[] data) {
        final StringBuilder text = new StringBuilder();
        for (int index = 0; index < AXIS_COUNT; index++) {
            final int offset = AXIS_FIRST + index * 2;
            if (index > 0) {
                text.append(' ');
            }
            text.append(AXIS_NAMES[index]).append('=')
                    .append((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8));
        }
        return text.toString();
    }

    /** The report id the stick report last arrived under, so a change of it is said once. */
    private static int sStickReportId = -1;

    /**
     * Says once when the stick report arrives under an id other than the one measured here.
     * Nothing depends on the id - the payload is what is decoded - but a remote that changes
     * it is a state change worth a line in the log, and the line also confirms which report is
     * feeding the sticks when someone reports a problem.
     */
    private static void noteReportId(byte[] data) {
        final int id = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        synchronized (RcRawJoystick.class) {
            if (id == sStickReportId) {
                return;
            }
            sStickReportId = id;
        }
        final int measured = (REPORT_ID << 8) | REPORT_MARK;
        if (id == measured) {
            Log.i(TAG, "raw joystick: the stick report is the one measured here (id "
                    + hex2(REPORT_ID) + ' ' + hex2(REPORT_MARK) + ')');
        } else {
            Log.w(TAG, "raw joystick: the stick report now arrives with id " + hex2(id >> 8) + ' '
                    + hex2(id & 0xFF) + " instead of " + hex2(REPORT_ID) + ' '
                    + hex2(REPORT_MARK) + " - decoded by its payload");
        }
    }

    /** Last rejected signature logged, so a stream of foreign frames is one line, not 400 a second. */
    private static int sRejectedSignature = -1;
    private static long sRejectedAt;
    private static int sRejected;

    /**
     * One line per distinct frame this reader cannot decode, with its bytes. That line is what
     * a report of dead sticks needs to contain: it says what the device sends in that state
     * instead of the stick report, and at which offsets.
     */
    private static void noteUnusable(byte[] data, int length) {
        final int signature = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        final int rejected;
        synchronized (RcRawJoystick.class) {
            rejected = ++sRejected;
            final long now = System.currentTimeMillis();
            if (signature == sRejectedSignature && now - sRejectedAt < REJECT_LOG_MS) {
                return;
            }
            sRejectedSignature = signature;
            sRejectedAt = now;
        }
        Log.w(TAG, "raw joystick: frame " + rejected + " is not a stick report ("
                + rejection(data, length) + "), ignored: " + hexDump(data, length));
    }

    /** Hands the six axes to the firmware - the same entry points the SDK uses. */
    private static void pushAxes(byte[] data, int length) {
        if (length < AXIS_FIRST + AXIS_COUNT * 2) {
            return;
        }
        for (int index = 0; index < AXIS_COUNT; index++) {
            final int offset = AXIS_FIRST + index * 2;
            final int value = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
            if (!insideTravel(value)) {
                // A field that is not this axis at all: publishing it would pin the channel to
                // an end of its range (zero is the bottom), so the last position is kept.
                if (!sAxisSkipped[index]) {
                    sAxisSkipped[index] = true;
                    Log.w(TAG, "raw joystick: " + AXIS_NAMES[index] + '=' + value
                            + " is outside its travel, keeping the last position");
                }
                continue;
            }
            if (sRawSeen[index] && value == sRawValue[index]) {
                continue;
            }
            sRawSeen[index] = true;
            sRawValue[index] = value;

            // The raw axes are centred on 1024 and the SDK reports the same range relative
            // to zero, so the centre is all that has to come off.
            final int centred = (value - AXIS_CENTRE) * AXIS_SIGN[index];
            try {
                if (index < STICK_COUNT) {
                    DjiMsdkBridge.nativeOnDjiStick(index, centred);
                } else {
                    DjiMsdkBridge.nativeOnDjiDial(index - STICK_COUNT, centred);
                }
            } catch (Throwable t) {
                // A missing native side must not kill the reader.
            }
        }

        if (!sOwned) {
            sOwned = true;
            synchronized (RcRawJoystick.class) {
                sRestarts = 0;
                sRestartDelayMs = RESTART_MIN_MS;
            }
            Log.i(TAG, "raw joystick: the sticks, dials and buttons now come from the "
                    + "raw reports");
        }
    }

    // ---- verbose diagnostics ------------------------------------------------

    /**
     * The return button, byte 16 bit 1, which has to be synthesised: Android no longer sees
     * it. RTN only ever reached the app as a tap re-injected by the RC's own service, and
     * that service reads this device - which, like the kernel driver, it cannot see while
     * this reader holds the interface. The 5-way is unaffected.
     */
    private static void pushButtons(byte[] data, int length) {
        if (length <= BUTTON_LAST) {
            return;
        }
        final int first = data[BUTTON_FIRST] & 0xFF;
        final int second = data[BUTTON_LAST] & 0xFF;
        if (sButtonsKnown && first == sButtonFirst && second == sButtonSecond) {
            return;
        }
        final int wasFirst = sButtonFirst;
        final int wasSecond = sButtonSecond;
        sButtonsKnown = true;
        sButtonFirst = first;
        sButtonSecond = second;

        if (sVerbose) {
            Log.i(TAG, "raw joystick: buttons " + hex2(first) + ' ' + hex2(second)
                    + namedButtons(first, second));
        }

        if (((wasFirst ^ first) & RTN_BIT) != 0) {
            try {
                nativeOnRawKey(KeyEvent.KEYCODE_BACK, (first & RTN_BIT) != 0);
            } catch (Throwable t) {
                // Worth a line: it means the button is dead, not that nothing is interesting.
                Log.w(TAG, "raw joystick: RTN could not be delivered", t);
            }
        }

        if (((wasFirst ^ first) & RECORD_BIT) != 0) {
            try {
                DjiMsdkBridge.onRawRecordButton((first & RECORD_BIT) != 0);
            } catch (Throwable t) {
                Log.w(TAG, "raw joystick: the record button could not be delivered", t);
            }
        }

        if (((wasFirst ^ first) & SHUTTER_BIT) != 0) {
            try {
                DjiMsdkBridge.onRawShutter((first & SHUTTER_BIT) != 0);
            } catch (Throwable t) {
                Log.w(TAG, "raw joystick: the photo button could not be delivered", t);
            }
        }

        // The bit that means different things on different remotes is placed by
        // DjiMsdkBridge, which is where the switch slots and the per-remote split live.
        if (((wasFirst ^ first) & ROUND_BIT) != 0) {
            try {
                DjiMsdkBridge.onRawRoundButton((first & ROUND_BIT) != 0);
            } catch (Throwable t) {
                Log.w(TAG, "raw joystick: button 0x01 could not be delivered", t);
            }
        }

        for (int bit = 0; bit < FIVE_WAY_BITS.length; bit++) {
            // A direction is a level, as it is in the SDK: only the press is passed on, and
            // only its rising edge.
            if ((second & FIVE_WAY_BITS[bit]) != 0 && (wasSecond & FIVE_WAY_BITS[bit]) == 0) {
                try {
                    DjiMsdkBridge.onRawFiveWay(FIVE_WAY_IDS[bit]);
                } catch (Throwable t) {
                    Log.w(TAG, "raw joystick: 5-way " + FIVE_WAY_NAMES[bit]
                            + " could not be delivered", t);
                }
            }
        }
    }

    /** The buttons a frame carries, named - for the verbose log only. */
    private static String namedButtons(int first, int second) {
        final StringBuilder text = new StringBuilder();
        if ((first & RTN_BIT) != 0) {
            text.append("RTN ");
        }
        if ((first & RECORD_BIT) != 0) {
            text.append("record ");
        }
        if ((first & SHUTTER_BIT) != 0) {
            text.append("photo ");
        }
        if ((first & ROUND_BIT) != 0) {
            text.append("round ");
        }
        for (int bit = 0; bit < FIVE_WAY_BITS.length; bit++) {
            if ((second & FIVE_WAY_BITS[bit]) != 0) {
                text.append(FIVE_WAY_NAMES[bit]).append(' ');
            }
        }
        return text.length() == 0 ? " (none)" : " (" + text.toString().trim() + ')';
    }

    /** Writes one line per change in a frame, with what moved. */
    private static void logFrame(Map<Integer, FrameLog> logs, byte[] data, int length,
                                long elapsedMs) {
        final int type = data[0] & 0xFF;
        FrameLog log = logs.get(type);
        if (log == null) {
            log = new FrameLog(type);
            logs.put(type, log);
            Log.i(TAG, "raw joystick: first report of type " + hex2(type) + " (" + length
                    + " B): " + hexDump(data, length) + le16(data, length));
        }
        log.accept(data, length, elapsedMs);
    }

    /** The six axes as SDK-style numbers, for the log lines. */
    private static String describeAxes() {
        final StringBuilder text = new StringBuilder();
        for (int index = 0; index < AXIS_COUNT; index++) {
            if (index > 0) {
                text.append(' ');
            }
            text.append(AXIS_NAMES[index]).append('=')
                    .append(sRawValue[index] - AXIS_CENTRE);
        }
        return text.toString();
    }

    private static final String[] AXIS_NAMES =
            {"LH", "LV", "RH", "RV", "dialL", "dialR"};

    /** The same axes, as the SDK last reported them: the two should agree in sign. */
    static void logComparison() {
        if (!sVerbose) {
            return;
        }
        final StringBuilder sdk = new StringBuilder();
        final StringBuilder raw = new StringBuilder();
        for (int index = 0; index < AXIS_COUNT; index++) {
            if (index > 0) {
                sdk.append(' ');
                raw.append(' ');
            }
            sdk.append(AXIS_NAMES[index]).append('=').append(sSdkValue[index]);
            raw.append(AXIS_NAMES[index]).append('=')
                    .append((sRawValue[index] - AXIS_CENTRE) * AXIS_SIGN[index]);
        }
        Log.i(TAG, "raw joystick: sdk " + sdk + " | raw " + raw);
    }

    /**
     * What was observed for one frame type. Frames that differ from the last one logged are
     * held (up to {@link #MAX_PENDING} per logging interval) so that a button tapped and
     * released inside 200 ms is still reported.
     */
    private static final class FrameLog {
        static final int MAX_PENDING = 6;
        static final int MAX_LINES = 500;
        /** Offsets that are the rolling counter: they change every frame and mean nothing. */
        static final int[] COUNTER_BYTES = {2, 3};

        final int type;
        int frames;
        private byte[] baseline;
        private final List<byte[]> pending = new ArrayList<>();
        private long lastFlushMs;
        private long lastElapsedMs;
        private int lines;

        FrameLog(int type) {
            this.type = type;
        }

        void accept(byte[] data, int length, long elapsedMs) {
            frames++;
            lastElapsedMs = elapsedMs;
            // Compare with the last frame held (or, with none held, the last one written):
            // the counter alone must not count as a change, or every frame is "new".
            final byte[] reference = pending.isEmpty() ? baseline : pending.get(pending.size() - 1);
            if ((reference == null || !same(reference, data, length))
                    && pending.size() < MAX_PENDING) {
                pending.add(copy(data, length));
            }
            if (!pending.isEmpty() && elapsedMs - lastFlushMs >= LOG_INTERVAL_MS) {
                flush();
            }
        }

        private static byte[] copy(byte[] data, int length) {
            final byte[] copy = new byte[length];
            System.arraycopy(data, 0, copy, 0, length);
            return copy;
        }

        /** Whether two frames carry the same controls, ignoring the counter. */
        private static boolean same(byte[] left, byte[] right, int length) {
            if (left.length != length) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                if (left[i] != right[i] && !isCounter(i)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isCounter(int index) {
            for (int counter : COUNTER_BYTES) {
                if (counter == index) {
                    return true;
                }
            }
            return false;
        }

        private void flush() {
            lastFlushMs = lastElapsedMs;
            for (byte[] frame : pending) {
                if (lines >= MAX_LINES) {
                    break;
                }
                lines++;
                Log.i(TAG, "raw joystick: t+" + lastElapsedMs / 1000 + "s " + hex2(type) + ' '
                        + frame.length + "B " + hexDump(frame, frame.length)
                        + moved(baseline, frame) + le16(frame, frame.length));
                baseline = frame;
            }
            pending.clear();
        }

        /** {@code " | moved 8:00->01"}: which controls differ from the last logged frame. */
        private static String moved(byte[] from, byte[] to) {
            if (from == null || from.length != to.length) {
                return "";
            }
            final StringBuilder text = new StringBuilder();
            for (int i = 0; i < to.length; i++) {
                if (from[i] == to[i] || isCounter(i)) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(i).append(':').append(hex2(from[i] & 0xFF))
                        .append("->").append(hex2(to[i] & 0xFF));
            }
            return text.length() == 0 ? "" : " | moved " + text;
        }
    }

    private static final int LOG_INTERVAL_MS = 200;

    /** {@code " | le16 1024 1024 ..."}: the 16-bit values the axes sit in, centred at 1024. */
    private static String le16(byte[] data, int length) {
        if (data == null || length < 6) {
            return "";
        }
        final StringBuilder text = new StringBuilder(" | le16");
        for (int i = 4; i + 1 < length; i += 2) {
            text.append(' ').append((data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8));
        }
        return text.toString();
    }

    /** One line per USB device: what the framework is willing to hand to an app at all. */
    private static void listDevices(UsbManager manager) {
        try {
            final StringBuilder text = new StringBuilder();
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (text.length() > 0) {
                    text.append(" | ");
                }
                text.append(device.getDeviceName()).append(' ')
                        .append(hex(device.getVendorId())).append(':').append(hex(device.getProductId()))
                        .append(" ifaces=").append(device.getInterfaceCount())
                        .append(manager.hasPermission(device) ? " permitted" : " not-permitted");
            }
            Log.i(TAG, "raw joystick: USB devices: " + text);
        } catch (Throwable t) {
            Log.w(TAG, "raw joystick: could not list the USB devices", t);
        }
    }

    /**
     * Whether the kernel's own input device is back: this reader claims the joystick away
     * from the HID driver, so this is what says the driver was re-attached afterwards.
     */
    private static void logInputDevices() {
        try {
            final StringBuilder text = new StringBuilder();
            for (int id : InputDevice.getDeviceIds()) {
                final InputDevice device = InputDevice.getDevice(id);
                if (device == null) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append(", ");
                }
                text.append('[').append(id).append("] ").append(device.getName());
            }
            Log.i(TAG, "raw joystick: input devices: " + text);
        } catch (Throwable t) {
            Log.w(TAG, "raw joystick: could not list the input devices", t);
        }
    }

    /** The joystick named the way the saved permission answers are keyed. */
    private static String identity(UsbDevice device) {
        return String.format(Locale.US, "%04x:%04x",
                device.getVendorId(), device.getProductId());
    }

    /** Whether this joystick was allowed in an earlier launch, so the dialog is never raised again. */
    private static boolean wasAllowedBefore(Context context, UsbDevice device) {
        return EdgeTxApplication.isUsbDeviceAllowed(context, identity(device));
    }

    private static UsbDevice findJoystick(UsbManager manager) {
        try {
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (device.getVendorId() == VENDOR_ID && device.getProductId() == PRODUCT_ID) {
                    return device;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "raw joystick: could not search the device list", t);
        }
        return null;
    }

    private static UsbDevice deviceFrom(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            }
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static UsbEndpoint findInEndpoint(UsbInterface iface) {
        UsbEndpoint bulk = null;
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            final UsbEndpoint endpoint = iface.getEndpoint(i);
            if (endpoint.getDirection() != UsbConstants.USB_DIR_IN) {
                continue;
            }
            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
                return endpoint;
            }
            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && bulk == null) {
                bulk = endpoint;
            }
        }
        return bulk;
    }

    private static String hex(int value) {
        return String.format(Locale.US, "%04x", value);
    }

    private static String hex2(int value) {
        return String.format(Locale.US, "%02X", value & 0xFF);
    }

    private static String hexDump(byte[] data, int length) {
        if (data == null || length <= 0) {
            return "(none)";
        }
        final int count = Math.min(length, data.length);
        final StringBuilder text = new StringBuilder(count * 3);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                text.append(' ');
            }
            text.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return text.toString();
    }
}
