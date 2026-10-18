package com.edgetx.droidui;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The RF module, reached over a USB serial port - one slot at a time.
 *
 * <p>EdgeTX has an internal and an external module slot. This app drives exactly the
 * slot the model enables: the internal one when it is on, otherwise the external one.
 * Both modules may be attached, but only one is opened, so the other cannot transmit.
 *
 * <ul>
 *   <li><b>internal</b> - the built-in module, normally the C3 on its own USB (ESP32
 *       native USB Serial/JTAG, {@code 303a:1001}), module port 0 (firmware AUX1);</li>
 *   <li><b>external</b> - a module on a USB-UART adapter (CH34x, CP210x, FTDI, Prolific),
 *       module port 1 (firmware AUX2).</li>
 * </ul>
 *
 * <p>When only one module is attached there is nothing to tell apart, so it is used as
 * the internal one: a model with the internal slot enabled works without any device
 * change. With both attached, the C3 is the internal one and everything else is the
 * external one.
 *
 * <p>Whichever slot is active runs the protocol the model selects for it (CRSF, SBUS,
 * DSM, PPM, ...):
 *
 * <pre>
 *   sticks/switches -> EdgeTX mixer -> module protocol -> nativeTakeTx(port) -> USB -> module
 *   module -> USB -> nativePushRx(port) -> firmware RX -> EdgeTX telemetry screens
 * </pre>
 *
 * <p>Everything the module sends back is fed to the firmware, so it shows up on EdgeTX's
 * own telemetry pages exactly as it would with a physically attached module.
 *
 * <p>The port's parameters are driven by the firmware:
 * {@link #nativeWantedBaudrate(int)} reports what EdgeTX configured for that slot, and
 * the baud rate is applied when it changes.
 */
final class RcModuleSerial {
    private static final String TAG = "EdgeTXUI";

    private static final String ACTION_USB_PERMISSION = "com.edgetx.droidui.USB_PERMISSION";

    /** Baud used between "device attached" and the firmware telling us the real one. */
    private static final int DEFAULT_BAUD = 115200;

    /** ETX_Encoding_8N1 / ETX_Encoding_8E2 - the two framings EdgeTX asks a module for. */
    private static final int ENCODING_8N1 = 0;
    private static final int ENCODING_8E2 = 1;

    private static final int WRITE_TIMEOUT_MS = 200;
    private static final int STATUS_LOG_INTERVAL_MS = 5000;

    /** Espressif: the built-in C3's native USB Serial/JTAG. Everything else serial is external. */
    private static final int INTERNAL_VID = 0x303A;

    /** CRSF addresses a module answers with or to, used to recognise its frames. */
    private static final int CRSF_ADDRESS_MODULE = 0xC8;
    private static final int CRSF_ADDRESS_RECEIVER = 0xEC;
    private static final int CRSF_ADDRESS_HANDSET = 0xEA;
    private static final int CRSF_ADDRESS_RADIO = 0xEE;
    /** Broadcast destination, used by the device ping. */
    private static final int CRSF_ADDRESS_BROADCAST = 0x00;
    /** CRSF_FRAMETYPE_DEVICE_PING, payload [destination, origin]. */
    private static final int CRSF_FRAMETYPE_DEVICE_PING = 0x28;
    /** EdgeTX's MODULE_TYPE_CROSSFIRE, the only protocol worth pinging this way. */
    private static final int MODULE_TYPE_CROSSFIRE = 5;

    /** The CH340 variant on this module's USB-UART adapter; not in the library's own table. */
    private static final int CH340_VID = 0x1A86;
    private static final int CH340_PID_7522 = 0x7522;

    /**
     * The default prober plus that adapter.
     *
     * <p>The library's CH34x table lists 1a86:7523 and 1a86:5523 but not 1a86:7522, which
     * is the CH340 on the module's adapter. It speaks the same protocol, so the same
     * driver opens it; without the entry no driver matched and the external module was
     * invisible to the app.
     */
    private static final UsbSerialProber sProber = buildProber();

    private static UsbSerialProber buildProber() {
        final ProbeTable table = UsbSerialProber.getDefaultProbeTable();
        table.addProduct(CH340_VID, CH340_PID_7522, Ch34xSerialDriver.class);
        return new UsbSerialProber(table);
    }

    //
    // Stall instrumentation. A link occasionally stops for tens of seconds while the port
    // stays open; these counters say which direction stalls and for how long, which is what
    // tells a host side stall apart from a module that stopped reading its USB (the module's
    // RX FIFO fills up, the endpoint then NAKs and a write only returns when the stack gives
    // up).
    //
    /** Writes slower than this are counted as a stall. */
    private static final int WRITE_STALL_MS = 100;
    /** Gaps between received chunks longer than this are counted as a stall. Has to stay above the
     *  1 Hz keep-alive frame a module sends, which is the only traffic while the RF link is down. */
    private static final int RX_GAP_STALL_MS = 1500;
    /** Read timeout: a read that never returns would wedge the reader thread with it, but
     *  a short one makes the app re-issue IN transfers many times a second - which a
     *  share-mode USB hub with two full-speed devices behind it does not like (the kernel
     *  logs `clear tt ... error -71` and can drop the device). One second is short enough
     *  to notice a device that went away and long enough to keep the transaction rate low. */
    private static final int READ_TIMEOUT_MS = 1000;

    /**
     * Baud rates tried, in order, while the external module answers nothing at the rate the
     * firmware asked for. 400000 is the CRSF rate EdgeTX configures, but a module can be set to
     * another one, and a CH340 with an off-spec clock produces something else entirely.
     */
    private static final int[] AUTO_BAUD = { 460800, 921600, 230400, 115200 };
    /** How many CRC-valid frames a rate must produce before it is trusted. */
    private static final int CRSF_LOCK_FRAMES = 3;
    /** A locked rate that delivers nothing valid for this long is given up again. */
    private static final long CRSF_LOCK_TIMEOUT_MS = 5000;

    /** How long one rate is given to produce a valid frame before trying the next. */
    private static final long AUTO_BAUD_STEP_MS = 3000;
    /** Bytes written to measure the adapter's real line rate, see {@link #calibrateBaud}. */
    private static final int CALIBRATION_BYTES = 32768;
    /** Longer than {@link #WRITE_TIMEOUT_MS}: a measurement block is bigger than a frame. */
    private static final int CALIBRATION_TIMEOUT_MS = 2000;
    /** How many times the list is walked before the rate the firmware asked for is kept. */
    private static final int AUTO_BAUD_PASSES = 1;
    /**
     * If no standard rate works the configured rate is swept in steps of this size, because the
     * adapter's clock error is a plain ratio: a chip that is a few percent off puts the right
     * rate at a nearby, non-standard request value. UART tolerates about 2 %, so a 1.5 % step
     * cannot skip over the working rate of a chip that is within ±12 %.
     */
    private static final double FINE_STEP = 0.015;
    private static final int FINE_STEPS = 17;
    /** A fine step only has to cover a ping round trip. */
    private static final long FINE_STEP_MS = 2000;

    /** File the module log is mirrored into (app external files dir), see {@link #logi}. */
    private static final String FILE_LOG_NAME = "module_link.txt";
    private static final java.text.SimpleDateFormat TIME_FORMAT =
            new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final Object sFileLogLock = new Object();
    private static boolean sFileLogStarted;

    private static final ExecutorService sIoExecutor = Executors.newCachedThreadPool();
    private static ScheduledExecutorService sMonitor;

    private RcModuleSerial() {}

    static {
        // Runs from Application.onCreate, before NativeActivity loads the library.
        try {
            System.loadLibrary("edgetx_ui");
        } catch (Throwable t) {
            Log.w(TAG, "module: could not load edgetx_ui", t);
        }
    }

    // ------------------------------------------------------------- natives --

    /** Baud the firmware configured for that module slot, 0 while its port is closed. */
    static native int nativeWantedBaudrate(int port);

    /** Framing the firmware configured (ETX_Encoding_xx), 0 while the port is closed. */
    static native int nativeWantedEncoding(int port);

    /** True between the firmware opening and closing that slot's module port. */
    static native boolean nativePortOpen(int port);

    /** Drains up to buffer.length bytes the firmware wants to transmit on that port. */
    static native int nativeTakeTx(int port, byte[] buffer);

    /** Hands bytes read from that module to the firmware's RX path for its slot. */
    static native void nativePushRx(int port, byte[] buffer, int length);

    /** { txBytes, rxBytes, droppedBytes, baudrate, portOpen } for one port. */
    static native int[] nativeStats(int port);

    /** Protocol the model selected for each slot (0 = off), see MODULE_TYPE_*. */
    static native int nativeInternalModuleType();
    static native int nativeExternalModuleType();

    /** Drops frames the firmware queued for a slot the model is not using. */
    static native void nativeDiscardTx(int port);

    // --------------------------------------------------------------- state --

    private static boolean sStarted;

    private static Context sAppContext;
    private static UsbManager sUsbManager;
    private static final List<String> sPermissionRequested = new ArrayList<>();

    /** One module slot and the USB serial device that carries it. */
    private static final class Link {
        /** module_serial port index, which is also the firmware AUX1/AUX2 index. */
        final int port;
        /** Name used in every log line, so the two links are never confused. */
        final String role;
        /** True for the built-in C3, false for the module on the USB-UART adapter. */
        final boolean internal;

        volatile UsbSerialPort usbPort;
        volatile UsbDeviceConnection connection;
        SerialInputOutputManager ioManager;
        volatile String deviceName;

        volatile int appliedBaud = -1;
        volatile int appliedEncoding = -1;

        /**
         * Serialises writes. The probe ping and the firmware's frames go to the same USB
         * endpoint from two threads, and two bulk transfers in flight at once can be delivered
         * interleaved, which mangles both.
         */
        final Object writeLock = new Object();

        /** True while the model drives this slot; the other slot's bytes are dropped. */
        volatile boolean active;

        /**
         * Bytes this USB device delivered, whether or not the slot is active. The
         * firmware-side counter only sees what was handed over, so this is what tells
         * "the device is silent" apart from "the bytes went to the other slot".
         */
        volatile long deviceRxBytes;
        long loggedDeviceRxBytes;

        long lastOpenAttemptMs;
        long lastStatusLogMs;
        long lastDeviceListLogMs;
        boolean firmwarePortOpen;

        long lastRxAtMs;
        long rxGapMaxMs;
        int rxStalls;
        /** How many received chunks were dumped as hex, see the diagnostic in the listener. */
        int hexDumps;

        /** Index into {@link #AUTO_BAUD} while probing, -1 while using the firmware's rate. */
        int autoBaudIndex = -1;
        long autoBaudStepAtMs;
        int autoBaudPass;
        boolean autoBaudDone;
        /** True while the line rate is being measured; the firmware's frames are dropped then. */
        boolean calibrating;
        /** True once the adapter's clock has been measured, so it is only done once per open. */
        boolean measured;
        /** Index into the fine sweep around the configured rate, -1 when it is not running. */
        int fineIndex = -1;
        long fineStepAtMs;
        /** True once a CRC-valid CRSF frame arrived: the rate in use is the module's. */
        boolean crsfSeen;
        /** Valid frames seen at the current rate, see {@link #CRSF_LOCK_FRAMES}. */
        int crsfFrames;
        long lastValidFrameAtMs;
        /** Rate the firmware asked for when the lock was taken; a change means re-probing. */
        int lockedWanted;
        final byte[] crsfTail = new byte[64];
        int crsfTailLength;
        long writeMaxMs;
        long writeTotalMs;
        int writeCount;
        int writeStalls;

        Link(int port, String role, boolean internal) {
            this.port = port;
            this.role = role;
            this.internal = internal;
        }
    }

    private static final Link sInternal = new Link(0, "internal slot", true);
    private static final Link sExternal = new Link(1, "external slot", false);
    private static final Link[] sLinks = {sInternal, sExternal};

    /** The one slot the model is using; the other link is kept closed. */
    private static Link sActiveLink;

    // ----------------------------------------------------------- lifecycle --

    static void start(Context context) {
        if (sStarted) {
            return;
        }
        sStarted = true;
        sFileLogStarted = false;   // start a fresh module_link.txt for this run

        try {
            // The permission dialog is raised through a PendingIntent, so the context has
            // to outlive this call.
            sAppContext = context.getApplicationContext();
            sUsbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (sUsbManager == null) {
                Log.w(TAG, "module: no USB service on this device");
                return;
            }

            context.registerReceiver(sPermissionReceiver,
                    new IntentFilter(ACTION_USB_PERMISSION));

            final IntentFilter detach = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
            context.registerReceiver(sDetachReceiver, detach);

            sMonitor = Executors.newSingleThreadScheduledExecutor();
            sMonitor.scheduleWithFixedDelay(RcModuleSerial::monitor, 500, 500,
                    TimeUnit.MILLISECONDS);

            logi("module: two USB serial bridges ready - internal (C3, 303a:1001), "
                    + "external (USB-UART)");
            // The links are opened by the monitor once the model says which slot it
            // uses: internal when it is enabled, otherwise external.
        } catch (Throwable t) {
            Log.w(TAG, "module: could not start", t);
        }
    }

    // --------------------------------------------------------- device/port --

    private static final BroadcastReceiver sPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final UsbDevice device =
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            final boolean granted =
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            logi("module: USB permission "
                    + (granted ? "granted" : "denied") + " for " + describe(device));
            if (granted) {
                for (Link link : sLinks) {
                    openWhenAvailable(link);
                }
            }
        }
    };

    private static final BroadcastReceiver sDetachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            final String name = device != null ? device.getDeviceName() : null;
            logi("module: USB device detached: " + describe(device));
            // Close only the link whose device went away: the other module keeps running.
            for (Link link : sLinks) {
                if (name == null || (link.deviceName != null && link.deviceName.equals(name))) {
                    close(link);
                }
            }
        }
    };

    /** Opens the device that belongs to this slot, if one is present and permitted. */
    private static void openWhenAvailable(Link link) {
        if (sUsbManager == null || link.usbPort != null) {
            return;
        }

        final long now = System.currentTimeMillis();
        if (now - link.lastOpenAttemptMs < 1000) {
            return;
        }
        link.lastOpenAttemptMs = now;

        final List<UsbSerialDriver> drivers;
        try {
            drivers = sProber.findAllDrivers(sUsbManager);
        } catch (Throwable t) {
            Log.w(TAG, "module: could not probe USB devices", t);
            return;
        }

        UsbSerialDriver match = null;
        boolean espressifPresent = false;
        for (UsbSerialDriver driver : drivers) {
            if (driver.getDevice().getVendorId() == INTERNAL_VID) {
                espressifPresent = true;
                break;
            }
        }
        for (UsbSerialDriver driver : drivers) {
            final UsbDevice device = driver.getDevice();
            if (!belongsTo(link, device, espressifPresent) || isClaimedByOther(link, device)) {
                continue;
            }
            match = driver;
            break;
        }

        if (match == null) {
            // Nothing for this slot. Report what *is* attached (with its interface
            // classes and whether a driver probes it) so a module that is plugged in but
            // not recognized is not a silent failure.
            logLinkDiag(link, "no device for the " + link.role + " module");
            return;
        }

        final UsbDevice device = match.getDevice();
        if (!sUsbManager.hasPermission(device)) {
            requestPermission(link, device);
            return;
        }
        open(link, match);
    }

    /**
     * Whether a USB device is this slot's module.
     *
     * <p>The C3 (internal) is the Espressif native USB Serial/JTAG, 303a:1001. Everything
     * else that exposes a serial port is the external module: its adapter can be a CH34x,
     * CP210x, FTDI, Prolific or something else entirely, and there is no useful list to
     * close that set off. The C3 is excluded first, so it can never be taken as external.
     *
     * <p>When no C3 is attached at all, the single module that is present is treated as
     * the internal one: with only one module there is nothing to distinguish, and a
     * model that has the internal slot enabled must still work.
     */
    private static boolean belongsTo(Link link, UsbDevice device, boolean espressifPresent) {
        final int vendorId = device.getVendorId();
        if (link.internal) {
            // The single-module fallback only applies to the slot the model actually
            // uses: otherwise an inactive internal slot would take the external device.
            return vendorId == INTERNAL_VID || (link.active && !espressifPresent);
        }
        return vendorId != INTERNAL_VID;
    }

    private static boolean isClaimedByOther(Link link, UsbDevice device) {
        final String name = device.getDeviceName();
        if (name == null) {
            return false;
        }
        for (Link other : sLinks) {
            if (other != link && other.usbPort != null && name.equals(other.deviceName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One-line snapshot of the USB side for one slot: every attached device, its interface
     * classes, whether the app holds permission for it and whether any driver probes it.
     *
     * <p>This is what tells the failure modes apart. A module that is not in this list is
     * not seen by Android at all (cable, OTG adapter, or it is not enumerating). One that
     * is listed with {@code drv=false} is a probe-table problem. One that is listed with
     * {@code perm=false} is waiting for the permission dialog.
     */
    private static void logLinkDiag(Link link, String reason) {
        // Runs from the 500 ms monitor, and a missing module is the normal state - do not
        // flood logcat or the log file with the same line.
        final long now = System.currentTimeMillis();
        if (now - link.lastDeviceListLogMs < 30000) {
            return;
        }
        link.lastDeviceListLogMs = now;

        final StringBuilder sb = new StringBuilder(reason);
        final Map<String, UsbDevice> devices = sUsbManager.getDeviceList();
        sb.append(" | usb devices=").append(devices.size());
        final UsbSerialProber prober = sProber;
        for (UsbDevice device : devices.values()) {
            sb.append(" [").append(describe(device))
              .append(" ifaces=").append(device.getInterfaceCount());
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                final UsbInterface iface = device.getInterface(i);
                sb.append(String.format(Locale.US, " %d:%02x/%02x", iface.getId(),
                        iface.getInterfaceClass(), iface.getInterfaceSubclass()));
            }
            sb.append(" perm=").append(sUsbManager.hasPermission(device));
            try {
                sb.append(" drv=").append(prober.probeDevice(device) != null);
            } catch (Throwable t) {
                sb.append(" drv=?");
            }
            sb.append(']');
        }
        logi("module: " + sb);
    }

    /**
     * Writes a line to logcat <em>and</em> to {@code module_link.txt} in the app's external
     * files directory.
     *
     * <p>The file exists because the module occupies the USB port while it is being
     * diagnosed, so adb cannot be attached at the same time - open the file with any file
     * manager on the device instead. It is truncated at every service start, so it always
     * describes the current run.
     */
    private static void logi(String message) {
        Log.i(TAG, message);
        try {
            synchronized (sFileLogLock) {
                final File dir = sAppContext != null ? sAppContext.getExternalFilesDir(null) : null;
                if (dir == null) {
                    return;
                }
                final File file = new File(dir, FILE_LOG_NAME);
                try (FileWriter writer = new FileWriter(file, sFileLogStarted)) {
                    if (!sFileLogStarted) {
                        sFileLogStarted = true;
                        writer.write("# RF module links (USB) - " + new Date() + "\n");
                    }
                    writer.write(TIME_FORMAT.format(new Date()) + "  " + message + "\n");
                }
            }
        } catch (Throwable t) {
            // Logging must never be able to break the link.
        }
    }

    private static void requestPermission(Link link, UsbDevice device) {
        final String name = device.getDeviceName();
        if (name != null && sPermissionRequested.contains(name)) {
            return;  // already asked; waiting for the user
        }
        if (name != null) {
            sPermissionRequested.add(name);
        }

        logi(link.role + ": requesting USB permission for " + describe(device));

        final Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(deviceName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system delivers the grant as an extra on this intent, which an
            // immutable PendingIntent would not carry.
            flags |= PendingIntent.FLAG_MUTABLE;
        }

        try {
            sUsbManager.requestPermission(device,
                    PendingIntent.getBroadcast(appContext(), 0, intent, flags));
        } catch (Throwable t) {
            Log.w(TAG, "module: requestPermission failed", t);
        }
    }

    private static void open(Link link, UsbSerialDriver driver) {
        final UsbDevice device = driver.getDevice();
        logi(link.role + ": opening " + describe(device)
                + " (" + driver.getClass().getSimpleName() + ")");

        final UsbDeviceConnection connection;
        try {
            connection = sUsbManager.openDevice(device);
        } catch (Throwable t) {
            Log.w(TAG, link.role + ": openDevice failed", t);
            return;
        }

        if (connection == null) {
            Log.w(TAG, link.role + ": openDevice returned null (permission not held?)");
            return;
        }

        final List<UsbSerialPort> ports = driver.getPorts();
        if (ports.isEmpty()) {
            Log.w(TAG, link.role + ": device exposes no serial ports");
            connection.close();
            return;
        }

        final UsbSerialPort port = ports.get(0);
        try {
            port.open(connection);
            port.setParameters(DEFAULT_BAUD, 8, UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE);
        } catch (Throwable t) {
            Log.w(TAG, link.role + ": could not open the serial port", t);
            try {
                port.close();
            } catch (Throwable ignored) {
                // nothing left to do
            }
            connection.close();
            return;
        }

        // Explicitly deassert DTR/RTS. A CH340 keeps their state across app runs, and a
        // module wired to an auto-reset circuit (DTR -> IO0, RTS -> EN) stays in reset
        // while they are asserted: it then receives frames and never answers. Deasserted
        // is the state a module runs in. Modules that do not use the lines do not care.
        try {
            port.setDTR(false);
            port.setRTS(false);
        } catch (Throwable ignored) {
            // optional
        }

        link.usbPort = port;
        link.connection = connection;
        link.deviceName = device.getDeviceName();
        link.appliedBaud = DEFAULT_BAUD;
        link.appliedEncoding = ENCODING_8N1;
        link.lastRxAtMs = 0;
        logi(link.role + ": " + describe(device) + " open at " + DEFAULT_BAUD + " 8N1");

        link.ioManager = new SerialInputOutputManager(port, listenerFor(link));
        // A read with no timeout can block for ever (Android's USB stack does not always honour the
        // device disappearing), which would take the reader thread with it.
        link.ioManager.setReadTimeout(READ_TIMEOUT_MS);
        link.ioManager.setWriteTimeout(WRITE_TIMEOUT_MS);
        sIoExecutor.submit(link.ioManager);
        sIoExecutor.submit(() -> writeLoop(link));
    }

    private static SerialInputOutputManager.Listener listenerFor(Link link) {
        return new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                if (data == null || data.length == 0) {
                    return;
                }
                link.deviceRxBytes += data.length;
                if (link.hexDumps < 3) {
                    // Whatever arrives is worth seeing: a wrong baud rate shows up as garbage
                    // rather than as silence, and the byte pattern tells which way it is off.
                    link.hexDumps++;
                    logi(link.role + ": rx " + data.length + " B " + hexDump(data));
                }
                noteFrames(link, data);
                if (!link.active) {
                    // The model is using the other slot: count the bytes so a device that
                    // is talking can be told apart from a silent one, but hand nothing to
                    // the firmware - this slot must not drive the model.
                    return;
                }
                final long now = System.currentTimeMillis();
                if (link.lastRxAtMs != 0) {
                    final long gap = now - link.lastRxAtMs;
                    if (gap > link.rxGapMaxMs) {
                        link.rxGapMaxMs = gap;
                    }
                    if (gap > RX_GAP_STALL_MS) {
                        link.rxStalls++;
                        logi(link.role + ": rx stopped for " + gap + " ms - " + lastStats(link));
                    }
                }
                link.lastRxAtMs = now;
                try {
                    nativePushRx(link.port, data, data.length);
                } catch (Throwable t) {
                    Log.w(TAG, link.role + ": could not deliver received data", t);
                }
            }

            @Override
            public void onRunError(Exception e) {
                Log.w(TAG, link.role + ": read loop stopped", e);
                close(link);
            }
        };
    }

    /** Drains the firmware's TX queue for one slot into that port. Exits when it closes. */
    private static void writeLoop(Link link) {
        final byte[] buffer = new byte[1024];

        while (true) {
            final UsbSerialPort port = link.usbPort;
            if (port == null) {
                return;
            }

            final int count;
            try {
                count = nativeTakeTx(link.port, buffer);
            } catch (Throwable t) {
                Log.w(TAG, link.role + ": could not read the TX queue", t);
                return;
            }

            if (count <= 0) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            if (!link.active || link.calibrating) {
                // The model is using the other slot: the frames were taken out of the
                // firmware's queue and are dropped here instead of being transmitted. While the
                // line rate is being measured nothing else may write either, or the timing that
                // measurement depends on is spoiled.
                continue;
            }

            try {
                // UsbSerialPort.write(byte[] src, int length, int timeoutMillis) - note
                // the argument order, it is length first, not an offset.
                final long started = System.currentTimeMillis();
                synchronized (link.writeLock) {
                    port.write(buffer, count, WRITE_TIMEOUT_MS);
                }
                final long took = System.currentTimeMillis() - started;
                link.writeCount++;
                link.writeTotalMs += took;
                if (took > link.writeMaxMs) {
                    link.writeMaxMs = took;
                }
                if (took > WRITE_STALL_MS) {
                    link.writeStalls++;
                    // A device that has stopped reading its USB keeps the write pending until the
                    // stack times out, so a slow write is the module side showing up on this side.
                    logi(link.role + ": write took " + took + " ms for " + count + " B"
                            + " (stalls " + link.writeStalls + ", worst " + link.writeMaxMs + " ms)");
                }
            } catch (Throwable t) {
                Log.w(TAG, link.role + ": write failed", t);
                close(link);
                return;
            }
        }
    }

    private static synchronized void close(Link link) {
        if (link.ioManager != null) {
            link.ioManager.stop();
            link.ioManager = null;
        }
        if (link.usbPort != null) {
            try {
                link.usbPort.close();
            } catch (IOException ignored) {
                // closing anyway
            }
            link.usbPort = null;
            logi(link.role + ": serial port closed");
        }
        if (link.connection != null) {
            link.connection.close();
            link.connection = null;
        }
        link.deviceName = null;
        link.appliedBaud = -1;
        link.appliedEncoding = -1;
        link.firmwarePortOpen = false;
        link.lastRxAtMs = 0;
        link.autoBaudIndex = -1;
        link.autoBaudStepAtMs = 0;
        link.autoBaudPass = 0;
        link.autoBaudDone = false;
        link.calibrating = false;
        link.measured = false;
        link.fineIndex = -1;
        link.fineStepAtMs = 0;
        link.crsfSeen = false;
        link.crsfFrames = 0;
        link.lockedWanted = 0;
        link.lastValidFrameAtMs = 0;
        link.crsfTailLength = 0;
        link.hexDumps = 0;
    }

    // -------------------------------------------------------------- monitor --

    /**
     * Drives exactly one module: the internal one when the model enables it, otherwise
     * the external one. Even with both modules physically attached only one is opened,
     * so the other cannot transmit on the same model.
     */
    private static void monitor() {
        final int internalType = moduleType(true);
        final int externalType = moduleType(false);
        final Link active = internalType != 0 ? sInternal
                          : externalType != 0 ? sExternal
                          : null;

        if (active != sActiveLink) {
            sActiveLink = active;
            logi("module: " + (active != null ? "using the " + active.role
                                              : "no module slot is enabled")
                    + " (internal type " + internalType + ", external type "
                    + externalType + ")");
        }
        for (Link link : sLinks) {
            link.active = (link == active);
        }

        // Close the inactive slot first: with a single module attached it is the device
        // the active slot wants, and it is still claimed by the link being closed.
        for (Link link : sLinks) {
            if (link == active) {
                continue;
            }
            try {
                monitorInactive(link);
            } catch (Throwable t) {
                Log.w(TAG, link.role + ": monitor failed", t);
            }
        }

        if (active != null) {
            try {
                monitorActive(active);
            } catch (Throwable t) {
                Log.w(TAG, active.role + ": monitor failed", t);
            }
        }
    }

    /**
     * A slot the model is not using. As soon as the model switches slots the old one is
     * closed again, so only the enabled module is ever read; anything it had queued is
     * discarded rather than transmitted.
     */
    private static void monitorInactive(Link link) {
        try {
            nativeDiscardTx(link.port);
        } catch (Throwable ignored) {
            // the library may not be up yet
        }

        if (link.usbPort != null) {
            logi(link.role + ": closed, the model uses the other slot (" + link.deviceRxBytes
                    + " B read from its device)");
            close(link);
        }
    }

    private static int moduleType(boolean internal) {
        try {
            return internal ? nativeInternalModuleType() : nativeExternalModuleType();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void monitorActive(Link link) {
        if (link.usbPort == null) {
            // No device for the active slot: drop what the firmware produces instead of
            // letting the bounded queue overflow while we wait for it.
            try {
                nativeDiscardTx(link.port);
            } catch (Throwable ignored) {
                // the library may not be up yet
            }
            openWhenAvailable(link);
            return;
        }

        final int wantedRaw = nativeWantedBaudrate(link.port);

        // A lock pins the rate that works, which is not necessarily the one the firmware asks
        // for - but when the firmware changes its mind the pin belongs to the wrong rate, and
        // waiting at a rate the model no longer uses makes everything on the link slower.
        if (link.crsfSeen && wantedRaw > 0 && link.lockedWanted != 0
                && wantedRaw != link.lockedWanted) {
            logi(link.role + ": firmware now asks for " + wantedRaw + " baud, probing again");
            dropLock(link);
        }
        if (!link.crsfSeen) {
            link.lockedWanted = 0;
        } else if (link.lockedWanted == 0 && wantedRaw > 0) {
            link.lockedWanted = wantedRaw;
        }

        final int wanted = resolveBaud(link, wantedRaw);
        final int encoding = nativeWantedEncoding(link.port);

        // A lock is only as good as the frames that keep arriving: a rate that was accepted by
        // accident stops producing them, and then the firmware would be fed garbage for ever.
        if (link.crsfSeen && link.lastValidFrameAtMs != 0
                && System.currentTimeMillis() - link.lastValidFrameAtMs > CRSF_LOCK_TIMEOUT_MS) {
            logi(link.role + ": nothing valid for " + CRSF_LOCK_TIMEOUT_MS + " ms at "
                    + link.appliedBaud + " baud, probing again");
            dropLock(link);
        }

        if (wanted > 0 && (wanted != link.appliedBaud || encoding != link.appliedEncoding)) {
            applyPortParams(link, wanted, encoding);
        }

        final boolean firmwareOpen = nativePortOpen(link.port);
        if (firmwareOpen != link.firmwarePortOpen) {
            link.firmwarePortOpen = firmwareOpen;
            logi(link.role + ": firmware module port "
                    + (firmwareOpen ? "opened" : "closed")
                    + (firmwareOpen ? " (" + wanted + " baud)" : "; " + lastStats(link)));
        }

        if (firmwareOpen) {
            // A CRSF module stays silent until it is asked, so poking it is what tells a rate
            // that decodes it apart from one that does not. Stops as soon as it answered once.
            if (!link.internal && !link.crsfSeen
                    && moduleType(false) == MODULE_TYPE_CROSSFIRE) {
                sendProbePing(link);
            }
            final long now = System.currentTimeMillis();
            if (now - link.lastStatusLogMs >= STATUS_LOG_INTERVAL_MS) {
                link.lastStatusLogMs = now;
                logi(link.role + ": " + lastStats(link));
            }
        }
    }

    /**
     * Applies what EdgeTX configured for that slot. Only the framing the firmware asks
     * for can be set - 8N1 for everything but SBUS, 8E2 for SBUS. The polarity cannot:
     * a USB serial adapter puts out the signal it puts out, so a protocol EdgeTX drives
     * inverted (MULTI, DSM2/DSMP, SBUS, AFHDS3) works only if the module takes the line
     * as it comes; otherwise it needs an inverter on the wire.
     */
    private static void applyPortParams(Link link, int baudrate, int encoding) {
        final UsbSerialPort port = link.usbPort;
        if (port == null) {
            return;
        }

        final boolean eightE2 = encoding == ENCODING_8E2;
        final String framing = eightE2 ? "8E2" : "8N1";
        final int stopBits = eightE2 ? UsbSerialPort.STOPBITS_2 : UsbSerialPort.STOPBITS_1;
        final int parity = eightE2 ? UsbSerialPort.PARITY_EVEN : UsbSerialPort.PARITY_NONE;

        try {
            port.setParameters(baudrate, 8, stopBits, parity);
            link.appliedBaud = baudrate;
            link.appliedEncoding = encoding;
            logi(link.role + ": port set to " + baudrate + " " + framing
                    + " " + readBackBaudRegisters(link, baudrate));
        } catch (Throwable t) {
            // Record it anyway: retrying the same failing value every 500 ms helps nobody.
            link.appliedBaud = baudrate;
            link.appliedEncoding = encoding;
            Log.w(TAG, link.role + ": could not set " + baudrate + " " + framing, t);
        }
    }

    /**
     * Baud rate to put the port at. Normally the one the firmware configured; the external slot
     * walks {@link #AUTO_BAUD} while the module answers nothing that parses as CRSF, and locks
     * the first rate at which a valid frame arrives.
     *
     * <p>Needed because the rate on the wire is not necessarily the one that was asked for: a
     * CRSF module can be configured for a different rate, and a CH340 with an off-spec clock (the
     * 1a86:7522 board here) runs a rate that is far enough off 400 kbaud for the module to reject
     * every frame. A loopback test cannot show this, because both directions use the same clock.
     */
    private static int resolveBaud(Link link, int wanted) {
        if (link.internal || link.crsfSeen) {
            // Keep whatever rate was in use when the lock was taken: it may be one of the
            // probed ones, not the rate the firmware configured.
            return link.internal || link.appliedBaud <= 0 ? wanted : link.appliedBaud;
        }
        if (link.autoBaudDone) {
            return wanted;
        }

        final long now = System.currentTimeMillis();

        if (link.fineIndex >= 0) {
            if (now - link.fineStepAtMs >= FINE_STEP_MS) {
                link.fineIndex++;
                link.fineStepAtMs = now;
                if (link.fineIndex >= FINE_STEPS) {
                    link.fineIndex = -1;
                    link.autoBaudDone = true;
                    logi(link.role + ": no CRSF frames between "
                            + fineRate(wanted, 0) + " and "
                            + fineRate(wanted, FINE_STEPS - 1) + " baud either, back to "
                            + wanted + " - the module may have fallen into WiFi mode (only a power"
                            + " cycle gets it out) or its wires are not connected");
                    return wanted;
                }
                logi(link.role + ": trying " + fineRate(wanted, link.fineIndex)
                        + " baud (" + link.deviceRxBytes + " B read so far)");
            }
            return fineRate(wanted, link.fineIndex);
        }

        if (link.autoBaudIndex < 0) {
            if (!link.firmwarePortOpen) {
                // Nothing is being transmitted, so there is nothing to answer: keep the rate the
                // firmware chose and restart the wait once its port opens.
                link.autoBaudStepAtMs = now;
                return wanted;
            }
            if (link.autoBaudStepAtMs == 0) {
                link.autoBaudStepAtMs = now;
                return wanted;
            }
            if (now - link.autoBaudStepAtMs < AUTO_BAUD_STEP_MS) {
                return wanted;
            }
            link.autoBaudIndex = 0;
            link.autoBaudStepAtMs = now;
            logi(link.role + ": nothing valid at " + wanted + " baud, trying " + AUTO_BAUD[0]);
            return AUTO_BAUD[0];
        }

        if (now - link.autoBaudStepAtMs < AUTO_BAUD_STEP_MS) {
            return AUTO_BAUD[link.autoBaudIndex];
        }

        link.autoBaudIndex++;
        link.autoBaudStepAtMs = now;
        if (link.autoBaudIndex >= AUTO_BAUD.length) {
            link.autoBaudPass++;
            if (link.autoBaudPass >= AUTO_BAUD_PASSES) {
                link.autoBaudIndex = -1;
                if (!link.measured) {
                    link.measured = true;
                    measureLineBaud(link, wanted);
                }
                link.fineIndex = 0;
                link.fineStepAtMs = now;
                logi(link.role + ": no standard rate answered, sweeping " + wanted
                        + " in " + Math.round(FINE_STEP * 100) + "% steps");
                return fineRate(wanted, 0);
            }
            link.autoBaudIndex = 0;
            logi(link.role + ": no CRSF frames yet, trying again from " + AUTO_BAUD[0]);
        }
        logi(link.role + ": nothing valid either, trying " + AUTO_BAUD[link.autoBaudIndex]
                + " (" + link.deviceRxBytes + " B read so far)");
        return AUTO_BAUD[link.autoBaudIndex];
    }

    /**
     * Measures how far the adapter's clock is off, for the log only.
     *
     * <p>A CH34x takes the divider from the host but derives the bit clock from its own crystal or
     * oscillator, so the rate on the wire is the requested one times that clock's error. The rate
     * can be measured without test gear: once the chip's transmit FIFO is full, {@code write()}
     * only returns as fast as the line drains, so timing a burst gives the real rate. The number
     * is not precise enough to program a correction from (the USB hub adds jitter), which is why
     * the caller falls back to sweeping the rate instead.
     */
    private static void measureLineBaud(Link link, int wanted) {
        if (link.usbPort == null || wanted <= 0) {
            return;
        }

        final int measured = measureLineBaudOnce(link, wanted);
        if (measured <= 0) {
            logi(link.role + ": could not measure the adapter's clock");
            return;
        }
        logi(link.role + ": adapter runs about " + measured + " baud when asked for " + wanted
                + " (" + String.format(Locale.US, "%.1f", 100.0 * measured / wanted) + "%)");
    }

    /** Writes a burst and returns the baud rate the bytes actually left the chip at, or 0. */
    private static int measureLineBaudOnce(Link link, int nominal) {
        final UsbSerialPort port = link.usbPort;
        if (port == null) {
            return 0;
        }

        final byte[] chunk = new byte[4096];
        Arrays.fill(chunk, (byte) 0x55);

        link.calibrating = true;
        try {
            applyPortParams(link, nominal, ENCODING_8N1);
            // Prime the FIFO first: the first bytes return before the line is the bottleneck.
            synchronized (link.writeLock) {
                port.write(chunk, chunk.length, CALIBRATION_TIMEOUT_MS);
            }

            final long started = System.nanoTime();
            int written = 0;
            while (written < CALIBRATION_BYTES) {
                synchronized (link.writeLock) {
                    port.write(chunk, chunk.length, CALIBRATION_TIMEOUT_MS);
                }
                written += chunk.length;
            }
            final long elapsedNanos = System.nanoTime() - started;
            // 8N1 puts 10 bits on the line per byte.
            return (int) Math.round(written * 10.0 * 1e9 / elapsedNanos);
        } catch (Throwable t) {
            Log.w(TAG, link.role + ": could not measure the line rate", t);
            return 0;
        } finally {
            link.calibrating = false;
        }
    }

    /** Rate {@code index} of the fine sweep around the configured rate. */
    private static int fineRate(int wanted, int index) {
        final double offset = (index - (FINE_STEPS - 1) / 2.0) * FINE_STEP;
        return (int) Math.round(wanted * (1.0 + offset));
    }

    /** Forgets the current rate so the next tick probes again from what the firmware asks for. */
    private static void dropLock(Link link) {
        link.crsfSeen = false;
        link.crsfFrames = 0;
        link.lockedWanted = 0;
        link.autoBaudIndex = -1;
        link.autoBaudPass = 0;
        link.autoBaudStepAtMs = 0;
        link.autoBaudDone = false;
        link.fineIndex = -1;
        link.deviceRxBytes = 0;
    }

    /**
     * Notes whether a CRC-valid CRSF frame went past, which is what tells a rate that decodes the
     * module apart from one that merely produces bytes.
     */
    private static void noteFrames(Link link, byte[] data) {
        final int tail = link.crsfTailLength;
        final byte[] buf = new byte[tail + data.length];
        System.arraycopy(link.crsfTail, 0, buf, 0, tail);
        System.arraycopy(data, 0, buf, tail, data.length);

        for (int i = 0; i + 3 < buf.length; i++) {
            final int address = buf[i] & 0xFF;
            if (address != CRSF_ADDRESS_MODULE && address != CRSF_ADDRESS_RADIO
                    && address != CRSF_ADDRESS_RECEIVER && address != CRSF_ADDRESS_HANDSET) {
                continue;
            }
            final int length = buf[i + 1] & 0xFF;
            if (length < 2 || length > 64 || i + 1 + length >= buf.length) {
                continue;
            }
            int crc = 0;
            for (int k = i + 2; k <= i + length; k++) {
                crc = crc8DvbS2(crc, buf[k]);
            }
            if (crc == (buf[i + 1 + length] & 0xFF)) {
                link.lastValidFrameAtMs = System.currentTimeMillis();
                if (link.crsfSeen || !link.firmwarePortOpen) {
                    // Frames only count once the firmware opened its side: before that the port
                    // sits at the rate it was opened with, which is not a rate anything asked for.
                    continue;
                }
                // One frame can be a coincidence in garbage: a wrong baud rate produces random
                // bytes, and about one frame header in 256 has a matching CRC. A rate that really
                // decodes the module produces frames continuously, so ask for several.
                if (++link.crsfFrames >= CRSF_LOCK_FRAMES) {
                    link.crsfSeen = true;
                    logi(link.role + ": CRSF frames decode at " + currentBaud(link) + " baud");
                }
                continue;
            }
        }

        final int keep = Math.min(buf.length, link.crsfTail.length);
        System.arraycopy(buf, buf.length - keep, link.crsfTail, 0, keep);
        link.crsfTailLength = keep;
    }

    private static int currentBaud(Link link) {
        if (link.autoBaudIndex >= 0) {
            return AUTO_BAUD[link.autoBaudIndex];
        }
        return link.appliedBaud;
    }

    private static int crc8DvbS2(int crc, byte value) {
        crc ^= value & 0xFF;
        for (int bit = 0; bit < 8; bit++) {
            crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0xD5) & 0xFF : (crc << 1) & 0xFF;
        }
        return crc;
    }

    /**
     * Asks the module who it is. A CRSF module answers nothing at all until something asks it,
     * so the only way to find out whether a rate is the right one is to talk first.
     */
    private static void sendProbePing(Link link) {
        final UsbSerialPort port = link.usbPort;
        if (port == null) {
            return;
        }
        final byte[] frame = new byte[6];
        frame[0] = (byte) CRSF_ADDRESS_RADIO;
        frame[1] = 4;
        frame[2] = CRSF_FRAMETYPE_DEVICE_PING;
        frame[3] = CRSF_ADDRESS_BROADCAST;
        frame[4] = (byte) CRSF_ADDRESS_HANDSET;
        int crc = crc8DvbS2(0, frame[2]);
        crc = crc8DvbS2(crc, frame[3]);
        frame[5] = (byte) crc8DvbS2(crc, frame[4]);
        try {
            synchronized (link.writeLock) {
                port.write(frame, frame.length, WRITE_TIMEOUT_MS);
            }
        } catch (Throwable t) {
            Log.w(TAG, link.role + ": could not ping the module", t);
        }
    }

    /**
     * Reads back the two baud rate registers a CH34x keeps, so the log shows whether the rate
     * that was asked for is the rate the chip actually holds. A wrong (or unchanged) value here
     * means the USB control transfer did not take, not that the module is at fault.
     */
    private static String readBackBaudRegisters(Link link, int baudrate) {
        final UsbDeviceConnection connection = link.connection;
        if (connection == null) {
            return "";
        }
        return " [0x1312=" + readRegister(connection, 0x1312) + " 0x0f2c="
                + readRegister(connection, 0x0f2c) + ", expected " + expectedBaudRegisters(baudrate) + "]";
    }

    private static String readRegister(UsbDeviceConnection connection, int register) {
        final byte[] buffer = new byte[2];
        final int read;
        try {
            read = connection.controlTransfer(0xC0, 0x95, register, 0, buffer, buffer.length, 500);
        } catch (Throwable t) {
            return "err";
        }
        if (read != buffer.length) {
            return "n/a(" + read + ")";
        }
        return String.format(Locale.US, "%02X%02X", buffer[1] & 0xFF, buffer[0] & 0xFF);
    }

    /** Mirrors the CH34x driver's baud rate maths, for the log only. */
    private static String expectedBaudRegisters(int baudrate) {
        long factor;
        long divisor;
        if (baudrate == 921600) {
            divisor = 7;
            factor = 0xf300;
        } else {
            factor = 1532620800L / baudrate;
            divisor = 3;
            while ((factor > 0xfff0) && divisor > 0) {
                factor >>= 3;
                divisor--;
            }
            factor = 0x10000 - factor;
        }
        divisor |= 0x0080;
        final int val1 = (int) ((factor & 0xff00) | divisor);
        final int val2 = (int) (factor & 0xff);
        return String.format(Locale.US, "%04X/%02X", val1, val2);
    }

    private static String hexDump(byte[] data) {        final StringBuilder sb = new StringBuilder();
        final int n = Math.min(data.length, 16);
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.US, "%02X ", data[i] & 0xFF));
        }
        if (data.length > n) {
            sb.append("...");
        }
        return sb.toString().trim();
    }

    private static String lastStats(Link link) {
        try {
            final int[] s = nativeStats(link.port);
            if (s == null || s.length < 5) {
                return "no stats";
            }
            return "tx " + s[0] + " B, rx " + s[1] + " B, dropped " + s[2]
                    + " B, firmware " + s[3] + " baud (port " + link.appliedBaud + "), "
                    + (s[4] != 0 ? "open" : "closed")
                    + ", writes " + link.writeCount + " (worst " + link.writeMaxMs + " ms, "
                    + link.writeStalls + ">" + WRITE_STALL_MS + " ms)"
                    + ", rx stalls " + link.rxStalls + " (worst " + link.rxGapMaxMs + " ms)";
        } catch (Throwable t) {
            return "stats unavailable";
        }
    }

    // -------------------------------------------------------------- helpers --

    private static Context appContext() {
        return sAppContext;
    }

    private static String deviceName() {
        return sAppContext != null ? sAppContext.getPackageName() : "com.edgetx.droidui";
    }

    private static String describe(UsbDevice device) {
        if (device == null) {
            return "<null>";
        }
        return String.format(Locale.US, "%s %04x:%04x", device.getDeviceName(),
                device.getVendorId(), device.getProductId());
    }
}