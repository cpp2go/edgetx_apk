package com.edgetx.droidui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.util.Log;

/**
 * What DJI's own protocol stream carries, in the app process - the third and last reader for the
 * controls the other readers can miss.
 *
 * <p>The ladder for the stick axes is:
 *
 * <ol>
 *   <li>the raw USB reports ({@link RcRawJoystick}) - 400 per second, but only while this app
 *       holds the joystick's USB interface;</li>
 *   <li>this: the RC firmware's own control packet, read in the {@code dji.go.v5} process by
 *       {@link RcProtocolService} - no permission, no USB interface, and independent of both
 *       the interface being taken and the SDK's silent waves;</li>
 *   <li>the DJI SDK's own 8-14 Hz copy, which is what the other two exist to improve on.</li>
 * </ol>
 *
 * <p>So this class never argues with the raw reports: while {@link RcRawJoystick#ownsInputs()}
 * is true its packets are counted but not pushed, and the firmware keeps the faster reader. When
 * the raw reports stop - the USB permission refused, the interface taken by DJI's app, or the
 * reader given up after a silent stream - these packets are what keeps the sticks moving, and
 * the SDK's copies stand aside for as long as they arrive (see DjiMsdkBridge.dispatchStick).
 *
 * <p>The packet's button field is passed on as well, and {@link DjiMsdkBridge#onProtocolBits}
 * is where it goes: every button whose own reader can miss a press has this copy as well, and
 * {@link RcButtons} merges them - the takeoff / ARM button first of all, which on the RC Plus
 * answers neither the SDK key nor the remote's own log.
 */
final class RcProtocolInputs {

    private static final String TAG = "EdgeTXUI";

    /** The service sends one of these per changed packet: four stick values in SDK order. */
    static final int MSG_STICKS = 1;

    /** The bundle key those four values travel under. */
    static final String KEY_STICKS = "sticks";

    /** The packet's 32-bit button/switch field, in {@code arg1}; sent when it changes. */
    static final int MSG_BUTTONS = 2;

    /** A packet keeps the sticks claimed for this long; the stream is much faster than that. */
    private static final long STALE_MS = 1000;

    /** How long to wait before binding again after the service process was taken away. */
    private static final long REBIND_MS = 3000;

    /** How often the "is the stream still there?" line is decided. */
    private static final long WATCHDOG_MS = 10000;

    /** The four axes this reader covers - everything after them stays with its own reader. */
    private static final int STICK_COUNT = 4;

    /** When the last packet arrived, 0 while none has. */
    private static volatile long sLastPacketAt;

    /** Whether a packet has ever arrived, so the first one is said once. */
    private static volatile boolean sSeen;

    /** Whether the stream was delivering at the last watchdog check. */
    private static volatile boolean sDelivering;

    private static volatile boolean sStarted;
    private static volatile boolean sBound;

    private RcProtocolInputs() {}

    /** Where the packets arrive: this runs on the app process's main thread. */
    private static final Handler SINK = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message message) {
            final Bundle data = message.getData();
            if (!fromWinner(data)) {
                return;
            }
            if (message.what == MSG_BUTTONS) {
                try {
                    DjiMsdkBridge.onProtocolBits(message.arg1);
                } catch (Throwable t) {
                    Log.w(TAG, "sticks: could not deliver a protocol button", t);
                }
                return;
            }
            if (message.what != MSG_STICKS) {
                return;
            }
            final int[] sticks = data == null ? null : data.getIntArray(KEY_STICKS);
            if (sticks == null || sticks.length < STICK_COUNT) {
                return;
            }
            onSticks(sticks);
        }
    };

    /**
     * All four DJI process names are listened to (only one of them answers, and which one is not
     * documented), so the copies from the others are dropped here rather than merged or counted
     * twice. The first process that produces a packet is the one this app listens to.
     */
    private static boolean fromWinner(Bundle data) {
        final String process = data == null ? null : data.getString(RcProtocolService.KEY_PROCESS);
        if (process == null) {
            return false;
        }
        final String winner = sWinner;
        if (winner == null) {
            sWinner = process;
            Log.i(TAG, "rc protocol: " + process + " is the process that answers");
            return true;
        }
        return winner.equals(process);
    }

    /** The process that answers - the only one of the four whose packets are used. */
    private static volatile String sWinner;

    /** Called from {@code EdgeTxApplication.startRuntime}. */
    static void start(final Context context) {
        if (sStarted) {
            return;
        }
        sStarted = true;
        sStartedServices = new java.util.HashSet<>();
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        final Class<?> service = installed(app);
        if (service == null) {
            // Nothing installed to go by: every process name is tried, first that answers wins.
            for (Class<?> candidate : SERVICES) {
                start(app, candidate);
            }
        } else {
            // DJI's own app is here, so its process name is the one the library answers on -
            // the same choice the reference reader makes. The others are not bound: each is a
            // process of its own, and a name whose app is absent never answers.
            start(app, service);
            // A guess that turns out wrong on this firmware costs nothing but a wait: if not a
            // single packet has arrived, the rest of the names are tried as well.
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (sSeen) {
                        return;
                    }
                    for (Class<?> candidate : SERVICES) {
                        if (candidate != service) {
                            start(app, candidate);
                        }
                    }
                }
            }, FALLBACK_MS);
        }
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                watchdog();
            }
        }, WATCHDOG_MS);
    }

    /** How long the chosen process name gets before the other three are bound as well. */
    private static final long FALLBACK_MS = 6000;

    /**
     * The service whose process is named after the DJI app installed on this remote, if any.
     *
     * <p>Which name the protocol library answers on follows the DJI app the remote normally
     * runs, and nothing documents the rule (see RcProtocolService), so this follows the
     * reference reader's order: industry, agriculture-x, consumer (DJI Fly), agriculture.
     */
    private static Class<?> installed(Context context) {
        if (isInstalled(context, "com.dji.industry.pilot")) {
            return RcProtocolServiceIndustryPilot.class;
        }
        if (isInstalled(context, "com.dji.agrasx")) {
            return RcProtocolServiceAgrasX.class;
        }
        if (isInstalled(context, "dji.go.v5") && !isInstalled(context, "com.dji.agras")) {
            return RcProtocolService.class;
        }
        if (isInstalled(context, "com.dji.agras")) {
            return RcProtocolServiceAgras.class;
        }
        return null;
    }

    private static boolean isInstalled(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The four listeners, one per DJI process name - all of them are tried. */
    private static final Class<?>[] SERVICES = {
            RcProtocolService.class,
            RcProtocolServiceAgras.class,
            RcProtocolServiceAgrasX.class,
            RcProtocolServiceIndustryPilot.class,
    };

    private static java.util.Set<String> sStartedServices;

    /**
     * Whether the protocol stream is feeding the sticks right now. While it is, the SDK's own
     * copies of the same axes are older and are not pushed - the same rule the raw reports get.
     */
    static boolean ownsSticks() {
        final long at = sLastPacketAt;
        return at != 0 && SystemClock.uptimeMillis() - at < STALE_MS;
    }

    /**
     * Whether any packet is arriving. The buttons use the same rule as the sticks: while the
     * stream is alive its presses are the exact ones (they carry the real press moment), and the
     * remote's own log - which is written at the release - stands aside.
     */
    static boolean delivering() {
        return ownsSticks();
    }

    /**
     * Bind one of the four listeners. Binding is also what keeps it alive: a bound service lives
     * as long as the app process does, and no notification of its own is wanted - the link
     * service already shows one, and a second is noise on a screen that is the radio's.
     */
    private static void start(final Context context, Class<?> service) {
        final Intent intent = new Intent(context, service);
        intent.putExtra(RcProtocolService.EXTRA_SINK, new Messenger(SINK));
        final String name = service.getSimpleName();
        try {
            if (context.bindService(intent, CONNECTION, Context.BIND_AUTO_CREATE)) {
                sBound = true;
                if (sStartedServices.add(name)) {
                    Log.i(TAG, "rc protocol: attached " + name);
                }
            } else {
                Log.w(TAG, "rc protocol: " + name + " refused the binding");
            }
        } catch (Throwable t) {
            Log.w(TAG, "rc protocol: could not bind " + name, t);
        }
    }

    private static void retry(final Context context) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!sBound) {
                    start(context, RcProtocolService.class);
                }
            }
        }, REBIND_MS);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // Nothing to send: the service has the sink from the bind intent and pushes to it.
            Log.i(TAG, "rc protocol: connected to " + name.flattenToShortString());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // One process was taken away. Android reconnects a bound service by itself, and until
            // it does the other readers cover the controls - so only the state is reset.
            sBound = false;
            sLastPacketAt = 0;
            Log.i(TAG, "rc protocol: lost " + name.flattenToShortString());
        }
    };

    /** One set of four stick values, in the SDK's axis order. */
    private static void onSticks(int[] sticks) {
        sLastPacketAt = SystemClock.uptimeMillis();
        if (!sSeen) {
            sSeen = true;
            Log.i(TAG, "sticks: DJI's protocol stream is delivering");
        }
        if (RcRawJoystick.ownsInputs()) {
            // The raw reports are the faster reader and they are alive: this is only the
            // fallback, so the value is dropped rather than fought over.
            return;
        }
        for (int axis = 0; axis < STICK_COUNT && axis < sticks.length; axis++) {
            try {
                DjiMsdkBridge.nativeOnDjiStick(axis, sdkValue(sticks[axis]));
            } catch (Throwable t) {
                // A missing native side must not kill the reader.
                return;
            }
        }
    }

    /**
     * The packet's idea of full deflection in the SDK's: both come from the same hardware, but
     * the packet reports it as 16 bits around 1024 (so -1024..1024) and the SDK as a number
     * around zero that stops at 660 (see joystick.cpp, kDjiStickRange - the native side clamps
     * to that, so an unscaled value would go flat over the last third of the stick's travel).
     */
    private static int sdkValue(int packetValue) {
        if (packetValue > PACKET_STICK_RANGE) {
            packetValue = PACKET_STICK_RANGE;
        } else if (packetValue < -PACKET_STICK_RANGE) {
            packetValue = -PACKET_STICK_RANGE;
        }
        return packetValue * SDK_STICK_RANGE / PACKET_STICK_RANGE;
    }

    /** Half of the packet's 16-bit axis range, so full deflection either way. */
    private static final int PACKET_STICK_RANGE = 1024;

    /** Half of the SDK's range, which is what the native side takes. */
    private static final int SDK_STICK_RANGE = 660;

    /** One status line when the stream stops or comes back, so a log says which reader has it. */
    private static void watchdog() {
        final boolean delivering = ownsSticks();
        if (delivering != sDelivering && sSeen) {
            sDelivering = delivering;
            if (delivering) {
                Log.i(TAG, "sticks: the protocol stream is back");
            } else {
                Log.i(TAG, "sticks: the protocol stream went quiet - the sticks fall back to "
                        + (RcRawJoystick.ownsInputs() ? "the raw reports" : "the DJI SDK"));
            }
        }
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                watchdog();
            }
        }, WATCHDOG_MS);
    }
}

