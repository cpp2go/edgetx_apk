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
 * <p><b>How it is used.</b> The six axes are pushed into exactly the entry points the SDK
 * uses ({@link DjiMsdkBridge#nativeOnDjiStick}, {@link DjiMsdkBridge#nativeOnDjiDial}), and
 * so are the buttons the reports carry, so nothing downstream changes. The SDK keeps
 * running and stays the fallback: as long as this reader is feeding the firmware
 * ({@link #ownsInputs()}) the SDK's copies of the same inputs are ignored, and if this
 * reader ever stops - the device taken away, the app moved to the background and killed -
 * the SDK takes them back without a restart. Everything the reports do not carry is
 * unaffected either way.
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

    /** The permission receiver is registered once, however often {@link #start} is called. */
    private static volatile boolean sReceiverRegistered;

    /** When the last permission request went out, so the asking can be spaced out. */
    private static volatile long sAskedAt;

    /** How long to wait before asking again: the answer normally takes a couple of seconds. */
    private static final int ASK_AGAIN_MS = 5000;

    /** Verbose flag, decided once at start. */
    private static volatile boolean sVerbose;

    /** Last raw value per axis, so only real changes are pushed. */
    private static final int[] sRawValue = new int[AXIS_COUNT];
    private static final boolean[] sRawSeen = new boolean[AXIS_COUNT];

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
     * photo and record buttons went with them. Asked again once the window is there, the dialog
     * appears and the answer is real. A denied answer is simply asked again on the next resume,
     * spaced out so a dialog cannot pile up.
     */
    static void start(Context context) {
        try {
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

            final Context app = context.getApplicationContext();
            registerPermissionReceiver(app);

            if (System.currentTimeMillis() - sAskedAt < ASK_AGAIN_MS) {
                return;
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
                Log.i(TAG, "raw joystick: USB permission " + (granted ? "granted" : "denied"));
                if (!granted) {
                    // Never fatal: this class only carries the reports the SDK cannot, so a
                    // refusal leaves the app working with the buttons the SDK does deliver.
                    sAskedAt = 0;
                    return;
                }
                final UsbManager manager =
                        (UsbManager) app.getSystemService(Context.USB_SERVICE);
                final UsbDevice permitted = deviceFrom(intent);
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

    private static void readInBackground(final UsbManager manager, final UsbDevice device) {
        sReaderStarted = true;
        final Thread thread = new Thread(() -> {
            try {
                read(manager, device);
            } catch (Throwable t) {
                Log.w(TAG, "raw joystick: reader stopped", t);
            } finally {
                sOwned = false;
                sReaderStarted = false;
            }
        }, "raw-joystick");
        thread.setDaemon(true);
        thread.start();
    }

    private static void read(UsbManager manager, UsbDevice device) {
        final UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            Log.w(TAG, "raw joystick: openDevice returned null (permission not held?)");
            return;
        }

        final UsbInterface iface = device.getInterface(0);
        // force = true detaches the kernel's own driver, which is the point: it is what
        // lets an app see the reports. The driver comes back when this connection closes.
        if (!connection.claimInterface(iface, true)) {
            Log.w(TAG, "raw joystick: could not claim interface " + iface.getId());
            connection.close();
            return;
        }
        Log.i(TAG, "raw joystick: claimed " + device.getDeviceName() + " interface "
                + iface.getId() + " (" + (sVerbose ? "verbose" : "quiet") + ")");

        try {
            final UsbEndpoint endpoint = findInEndpoint(iface);
            if (endpoint == null) {
                Log.w(TAG, "raw joystick: no IN endpoint on interface " + iface.getId());
                return;
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
            int missing = 0;
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
                        continue;   // a different request completed
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

                missing = 0;
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

            Log.i(TAG, "raw joystick: stopped after " + reports + " report(s)");
        } finally {
            sOwned = false;
            connection.releaseInterface(iface);
            connection.close();
            if (sVerbose) {
                logInputDevices();
            }
        }
    }

    /** Hands the six axes to the firmware - the same entry points the SDK uses. */
    private static void pushAxes(byte[] data, int length) {
        if (length < AXIS_FIRST + AXIS_COUNT * 2) {
            return;
        }
        for (int index = 0; index < AXIS_COUNT; index++) {
            final int offset = AXIS_FIRST + index * 2;
            final int value = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
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
