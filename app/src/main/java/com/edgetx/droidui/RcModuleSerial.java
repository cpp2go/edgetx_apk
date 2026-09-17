package com.edgetx.droidui;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The RF module, reached over a USB serial port.
 *
 * <p>This app makes the RC behave like a radio whose module is wired straight to the USB
 * port: EdgeTX runs the module protocol (whatever the model's module settings select -
 * CRSF, SBUS, DSM, PPM, ...) and this class is the wire that protocol travels on. The
 * firmware's module port has no UART on Android, so the simulator library hands its bytes to
 * {@code module_serial.cpp}, which queues them here:
 *
 * <pre>
 *   sticks/switches -> EdgeTX mixer -> module protocol -> nativeTakeTx() -> USB -> module
 *   module -> USB -> nativePushRx() -> firmware RX -> EdgeTX telemetry screens
 * </pre>
 *
 * <p>Everything the module sends back is fed to the firmware, so it shows up on EdgeTX's
 * own telemetry pages exactly as it would with a physically attached module.
 *
 * <p>The port is opened as soon as a USB serial device is present, but its parameters are
 * driven by the firmware: {@link #nativeWantedBaudrate()} reports what EdgeTX configured,
 * and the baud rate is applied when it changes.
 */
final class RcModuleSerial {
    private static final String TAG = "EdgeTXUI";

    private static final String ACTION_USB_PERMISSION = "com.edgetx.droidui.USB_PERMISSION";

    /** Baud used between "device attached" and the firmware telling us the real one. */
    private static final int DEFAULT_BAUD = 115200;

    private static final int WRITE_TIMEOUT_MS = 200;
    private static final int STATUS_LOG_INTERVAL_MS = 5000;

    static {
        // Runs from Application.onCreate, before NativeActivity loads the library.
        try {
            System.loadLibrary("edgetx_ui");
        } catch (Throwable t) {
            Log.w(TAG, "module: could not load edgetx_ui", t);
        }
    }

    // ------------------------------------------------------------- natives --

    /** Baud the firmware configured for the module port, 0 while it is closed. */
    static native int nativeWantedBaudrate();

    /** True between the firmware opening and closing the module port. */
    static native boolean nativePortOpen();

    /** Drains up to buffer.length bytes the firmware wants to transmit. */
    static native int nativeTakeTx(byte[] buffer);

    /** Hands bytes read from the module to the firmware's module RX path. */
    static native void nativePushRx(byte[] buffer, int length);

    /** { txBytes, rxBytes, droppedBytes, baudrate, portOpen }. */
    static native int[] nativeStats();

    // --------------------------------------------------------------- state --

    private static boolean sStarted;

    private static Context sAppContext;
    private static UsbManager sUsbManager;
    private static final List<String> sPermissionRequested = new ArrayList<>();

    private static UsbSerialPort sPort;
    private static UsbDeviceConnection sConnection;
    private static SerialInputOutputManager sIoManager;
    private static volatile int sAppliedBaud = -1;

    private static long sLastOpenAttemptMs;
    private static boolean sLastFirmwarePortOpen;
    private static long sLastStatusLogMs;

    private static final ExecutorService sIoExecutor = Executors.newCachedThreadPool();
    private static ScheduledExecutorService sMonitor;

    private RcModuleSerial() {}

    // ----------------------------------------------------------- lifecycle --

    static void start(Context context) {
        if (sStarted) {
            return;
        }
        sStarted = true;

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

            Log.i(TAG, "module: USB serial bridge ready");
            openWhenAvailable();
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
            Log.i(TAG, "module: USB permission "
                    + (granted ? "granted" : "denied") + " for " + describe(device));
            if (granted) {
                openWhenAvailable();
            }
        }
    };

    private static final BroadcastReceiver sDetachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            // Any detach closes the port: there is only ever one module attached, and a
            // stale connection would keep failing writes.
            Log.i(TAG, "module: USB device detached: " + describe(device));
            closePort();
        }
    };

    /** Opens the first USB serial device that is present and permitted. */
    private static void openWhenAvailable() {
        if (sUsbManager == null || sPort != null) {
            return;
        }

        final List<UsbSerialDriver> drivers;
        try {
            drivers = UsbSerialProber.getDefaultProber().findAllDrivers(sUsbManager);
        } catch (Throwable t) {
            Log.w(TAG, "module: could not probe USB devices", t);
            return;
        }

        if (drivers.isEmpty()) {
            return;
        }

        for (UsbSerialDriver driver : drivers) {
            final UsbDevice device = driver.getDevice();
            if (!sUsbManager.hasPermission(device)) {
                requestPermission(device);
                continue;
            }
            open(driver);
            return;
        }
    }

    private static void requestPermission(UsbDevice device) {
        final String name = device.getDeviceName();
        if (name != null && sPermissionRequested.contains(name)) {
            return;  // already asked; waiting for the user
        }
        if (name != null) {
            sPermissionRequested.add(name);
        }

        Log.i(TAG, "module: requesting USB permission for " + describe(device));

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

    private static void open(UsbSerialDriver driver) {
        final UsbDevice device = driver.getDevice();
        Log.i(TAG, "module: opening " + describe(device)
                + " (" + driver.getClass().getSimpleName() + ")");

        final UsbDeviceConnection connection;
        try {
            connection = sUsbManager.openDevice(device);
        } catch (Throwable t) {
            Log.w(TAG, "module: openDevice failed", t);
            return;
        }

        if (connection == null) {
            Log.w(TAG, "module: openDevice returned null (permission not held?)");
            return;
        }

        final List<UsbSerialPort> ports = driver.getPorts();
        if (ports.isEmpty()) {
            Log.w(TAG, "module: device exposes no serial ports");
            connection.close();
            return;
        }

        final UsbSerialPort port = ports.get(0);
        try {
            port.open(connection);
            port.setParameters(DEFAULT_BAUD, 8, UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE);
        } catch (Throwable t) {
            Log.w(TAG, "module: could not open the serial port", t);
            try {
                port.close();
            } catch (Throwable ignored) {
                // nothing left to do
            }
            connection.close();
            return;
        }

        // Flow control lines are not implemented by every bridge; a module that does
        // not use them does not care either way.
        try {
            port.setDTR(true);
            port.setRTS(true);
        } catch (Throwable ignored) {
            // optional
        }

        sPort = port;
        sConnection = connection;
        sAppliedBaud = DEFAULT_BAUD;
        Log.i(TAG, "module: " + describe(device) + " open at " + DEFAULT_BAUD + " 8N1");

        sIoManager = new SerialInputOutputManager(port, sListener);
        sIoExecutor.submit(sIoManager);
        sIoExecutor.submit(RcModuleSerial::writeLoop);
    }

    private static final SerialInputOutputManager.Listener sListener =
            new SerialInputOutputManager.Listener() {
                @Override
                public void onNewData(byte[] data) {
                    if (data == null || data.length == 0) {
                        return;
                    }
                    try {
                        nativePushRx(data, data.length);
                    } catch (Throwable t) {
                        Log.w(TAG, "module: could not deliver received data", t);
                    }
                }

                @Override
                public void onRunError(Exception e) {
                    Log.w(TAG, "module: read loop stopped", e);
                    closePort();
                }
            };

    /** Drains the firmware's TX queue into the port. Exits when the port closes. */
    private static void writeLoop() {
        final byte[] buffer = new byte[1024];

        while (true) {
            final UsbSerialPort port = sPort;
            if (port == null) {
                return;
            }

            final int count;
            try {
                count = nativeTakeTx(buffer);
            } catch (Throwable t) {
                Log.w(TAG, "module: could not read the TX queue", t);
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

            try {
                // UsbSerialPort.write(byte[] src, int length, int timeoutMillis) - note
                // the argument order, it is length first, not an offset.
                port.write(buffer, count, WRITE_TIMEOUT_MS);
            } catch (Throwable t) {
                Log.w(TAG, "module: write failed", t);
                closePort();
                return;
            }
        }
    }

    private static synchronized void closePort() {
        if (sIoManager != null) {
            sIoManager.stop();
            sIoManager = null;
        }
        if (sPort != null) {
            try {
                sPort.close();
            } catch (IOException ignored) {
                // closing anyway
            }
            sPort = null;
            Log.i(TAG, "module: serial port closed");
        }
        if (sConnection != null) {
            sConnection.close();
            sConnection = null;
        }
        sAppliedBaud = -1;
    }

    // -------------------------------------------------------------- monitor --

    /**
     * Keeps the port in step with the firmware: opens a device that appeared later, and
     * applies whatever baud rate EdgeTX configured for the module.
     */
    private static void monitor() {
        try {
            if (sPort == null) {
                final long now = System.currentTimeMillis();
                if (now - sLastOpenAttemptMs >= 1000) {
                    sLastOpenAttemptMs = now;
                    openWhenAvailable();
                }
                return;
            }

            final int wanted = nativeWantedBaudrate();
            if (wanted > 0 && wanted != sAppliedBaud) {
                applyBaud(wanted);
            }

            final boolean firmwareOpen = nativePortOpen();
            if (firmwareOpen != sLastFirmwarePortOpen) {
                sLastFirmwarePortOpen = firmwareOpen;
                Log.i(TAG, "module: firmware module port " + (firmwareOpen ? "opened" : "closed")
                        + (firmwareOpen ? " (" + wanted + " baud)" : "; " + lastStats()));
            }

            if (firmwareOpen) {
                final long now = System.currentTimeMillis();
                if (now - sLastStatusLogMs >= STATUS_LOG_INTERVAL_MS) {
                    sLastStatusLogMs = now;
                    Log.i(TAG, "module: " + lastStats());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "module: monitor failed", t);
        }
    }

    private static void applyBaud(int baudrate) {
        final UsbSerialPort port = sPort;
        if (port == null) {
            return;
        }
        try {
            port.setParameters(baudrate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            sAppliedBaud = baudrate;
            Log.i(TAG, "module: port set to " + baudrate + " 8N1");
        } catch (Throwable t) {
            // Record it anyway: retrying the same failing value every 500 ms helps nobody.
            sAppliedBaud = baudrate;
            Log.w(TAG, "module: could not set " + baudrate + " baud", t);
        }
    }

    private static String lastStats() {
        try {
            final int[] s = nativeStats();
            if (s == null || s.length < 5) {
                return "no stats";
            }
            return "tx " + s[0] + " B, rx " + s[1] + " B, dropped " + s[2]
                    + " B, " + s[3] + " baud, port " + (s[4] != 0 ? "open" : "closed");
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
