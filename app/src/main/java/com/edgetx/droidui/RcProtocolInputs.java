package com.edgetx.droidui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.util.Log;

import java.util.Locale;

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
 * <p>The packets carry the two wheels as well, and those are sent on under a stricter rule: only
 * for a dial the SDK has never produced a value for. The dials keep the SDK wherever it has them
 * (the same as before this reader existed); on a remote whose SDK reports nothing at all - the
 * RC Pro 2, which MSDK v5 does not support - the packet is what makes P1/P2 move once the raw
 * reports are not arriving.
 *
 * <p>The packet's button field is passed on as well, and {@link DjiMsdkBridge#onProtocolBits}
 * is where it goes: every button whose own reader can miss a press has this copy as well, and
 * {@link RcButtons} merges them - the takeoff / ARM button first of all, which on the RC Plus
 * answers neither the SDK key nor the remote's own log.
 */
final class RcProtocolInputs {

    private static final String TAG = "EdgeTXUI";

    /** The service sends one of these per changed packet: the axes, in the firmware's order. */
    static final int MSG_STICKS = 1;

    /** The bundle key the axes travel under: the four sticks, then the two wheels. */
    static final String KEY_STICKS = "sticks";

    /** The packet's 32-bit button/switch field, in {@code arg1}; sent when it changes. */
    static final int MSG_BUTTONS = 2;

    /**
     * Set on the one button message a process sends before its field has ever changed.
     *
     * <p>Every packet stream opens with a value, and a process whose field carries nothing sends
     * that value and never another one - which is exactly what a dead field looks like from here
     * (see {@link #fromButtonSource}).
     */
    static final String KEY_INITIAL = "initial";

    /** A packet keeps the sticks claimed for this long; the stream is much faster than that. */
    private static final long STALE_MS = 1000;

    /** How long to wait before binding again after the service process was taken away. */
    private static final long REBIND_MS = 3000;

    /** How often the "is the stream still there?" line is decided. */
    private static final long WATCHDOG_MS = 10000;

    /** The four sticks, in the order the firmware takes them. */
    private static final int STICK_COUNT = 4;

    /** The two wheels after them in the packet, in its own order: left, right. */
    private static final int DIAL_COUNT = 2;

    /** What one packet carries, in the order {@link RcProtocolService} sends it. */
    static final int AXIS_COUNT = STICK_COUNT + DIAL_COUNT;

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
            sLastMessageAt = SystemClock.uptimeMillis();
            final Bundle data = message.getData();
            final String process = data == null
                    ? null : data.getString(RcProtocolService.KEY_PROCESS);
            if (message.what == MSG_BUTTONS) {
                final boolean initial = data != null && data.getBoolean(KEY_INITIAL, false);
                if (!fromButtonSource(process, initial)) {
                    return;
                }
                try {
                    DjiMsdkBridge.onProtocolBits(message.arg1);
                } catch (Throwable t) {
                    Log.w(TAG, "sticks: could not deliver a protocol button", t);
                }
                return;
            }
            if (message.what != MSG_STICKS || !fromWinner(process)) {
                return;
            }
            final int[] axes = data.getIntArray(KEY_STICKS);
            if (axes == null || axes.length < AXIS_COUNT) {
                return;
            }
            onAxes(axes);
        }
    };

    /**
     * All four DJI process names are listened to (only one of them answers, and which one is not
     * documented), so the copies from the others are dropped here rather than merged or counted
     * twice. The first process that produces a packet is used, and the process the installed DJI
     * app names takes over from it if it speaks later.
     */
    private static boolean fromWinner(String process) {
        if (process == null) {
            return false;
        }
        final String winner = sWinner;
        if (winner == null) {
            sWinner = process;
            Log.i(TAG, "rc protocol: " + process + " is the process that answers");
            return true;
        }
        if (!winner.equals(process)) {
            // The process the installed DJI app names takes over from a fallback one that
            // answered first; both see the same remote, so only the source changes.
            if (process.equals(sPreferred)) {
                sWinner = process;
                Log.i(TAG, "rc protocol: " + process + " took over from " + winner);
                return true;
            }
            return false;
        }
        return true;
    }

    /**
     * The button field gets its own winner.
     *
     * <p>The process the reference rule names wins it - but only once its field has *moved*.
     * Every process sends the value its stream opens with, and a process whose field carries
     * nothing sends that value and never another one: measured on one RC Plus, the agriculture
     * process the rule picks there delivers the axes with an empty field, while DJI Fly's
     * process carries the switch and the buttons. So the named process's opening value is
     * accepted as a placeholder, another process's field is used as soon as it moves, and the
     * named process takes the field over for good the moment it shows a movement of its own.
     */
    private static boolean fromButtonSource(String process, boolean initial) {
        if (process == null) {
            return false;
        }
        if (process.equals(sPreferred) && !initial) {
            sPreferredFieldAlive = true;
        }
        final String winner = sButtonWinner;
        final String next;
        if (process.equals(sPreferred)) {
            // The named process keeps the field once it has shown one of its own; its opening
            // value is only worth having while nothing else is there.
            next = sPreferredFieldAlive || winner == null ? process : winner;
        } else if (sPreferredFieldAlive) {
            next = winner;                                   // the named process has it
        } else if (!initial && (winner == null || winner.equals(sPreferred))) {
            next = process;                                  // a field that moves wins
        } else {
            next = winner;
        }
        if (!process.equals(next)) {
            return false;
        }
        if (!process.equals(winner)) {
            sButtonWinner = process;
            // One field at a time: the new source's first value is the state the remote is in,
            // taken as the baseline rather than as a press (see DjiMsdkBridge.onProtocolBits).
            DjiMsdkBridge.onProtocolSourceChanged();
        }
        return true;
    }

    /** The process whose stick packets are used. */
    private static volatile String sWinner;

    /** The process the remote's own DJI app names - the reference reader's pick. */
    private static volatile String sPreferred;

    /** The process whose button field is trusted while the preferred one is silent. */
    private static volatile String sButtonWinner;

    /** Whether the preferred process has shown a button field of its own, i.e. one that moved. */
    private static volatile boolean sPreferredFieldAlive;

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
        sPreferred = processOf(service);
        sContext = app;
        // The process name the remote's own DJI app implies - the same choice the reference
        // reader makes. The others are not bound: each is a process of its own, and a name
        // whose app is absent never answers.
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
     * The service whose process is named after the DJI app this remote runs. Never null: the
     * reference reader always picks one, and the all-process fallback covers a firmware that
     * answers somewhere else.
     *
     * <p>Which name the protocol library answers on follows the DJI app the remote normally
     * runs, and nothing documents the rule (see RcProtocolService), so this is the reference
     * reader's choice condition for condition ({@code RC Plus Control 1.15},
     * {@code com.rcplus.keyprobe}, its {@code onCreate}): industry when that app is part of the
     * firmware, then agriculture-X, then - on every remote that is not the RC Pro
     * ({@code rm510}), and where DJI Fly is absent or the agriculture app is there - the
     * agriculture process, and DJI Fly's own for everything else.
     *
     * <p>"There" means preinstalled, not merely installed: the reference asks the package
     * manager for {@code FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP} (see {@link #isPreinstalled}),
     * because a remote can carry DJI Fly and the agriculture app side by side and the two must
     * not be weighed the same.
     */
    private static Class<?> installed(Context context) {
        final boolean industry = isPreinstalled(context, "com.dji.industry.pilot");
        final boolean agrasX = isPreinstalled(context, "com.dji.agrasx");
        final boolean consumer = isPreinstalled(context, "dji.go.v5");
        final boolean agras = isPreinstalled(context, "com.dji.agras");
        final String device = Build.DEVICE == null
                ? "" : Build.DEVICE.toLowerCase(Locale.ROOT);

        final Class<?> choice;
        if (industry) {
            choice = RcProtocolServiceIndustryPilot.class;
        } else if (agrasX) {
            choice = RcProtocolServiceAgrasX.class;
        } else if (!device.contains("rm510") && (!consumer || agras)) {
            choice = RcProtocolServiceAgras.class;
        } else {
            choice = RcProtocolService.class;
        }

        Log.i(TAG, "rc protocol: preinstalled dji.go.v5=" + consumer + ", agras=" + agras
                + ", agrasx=" + agrasX + ", industry=" + industry
                + "; listening in " + processOf(choice));
        return choice;
    }

    /** The manifest process name behind one of the four listener classes. */
    private static String processOf(Class<?> service) {
        if (service == RcProtocolServiceAgras.class) {
            return "com.dji.agras";
        }
        if (service == RcProtocolServiceAgrasX.class) {
            return "com.dji.agrasx";
        }
        if (service == RcProtocolServiceIndustryPilot.class) {
            return "com.dji.industry.pilot";
        }
        return RcProtocolService.PROCESS;
    }

    /**
     * Whether an app is part of the firmware rather than merely installed - the reference
     * reader's test, asked the same way so the two pick the same process.
     *
     * <p>Both DJI apps it weighs can be installed beside each other on one remote, and the one
     * the firmware ships with is the one whose process the library answers on. A user-installed
     * copy of the other does not change that, which is why {@code getPackageInfo} alone is not
     * enough here.
     */
    private static boolean isPreinstalled(Context context, String pkg) {
        try {
            final int flags = context.getPackageManager().getApplicationInfo(pkg, 0).flags;
            return (flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The four listeners; the one the remote's own DJI app names is used, the rest are fallbacks. */
    private static final Class<?>[] SERVICES = {
            RcProtocolService.class,
            RcProtocolServiceAgras.class,
            RcProtocolServiceAgrasX.class,
            RcProtocolServiceIndustryPilot.class,
    };

    private static java.util.Set<String> sStartedServices;

    /** The application context, kept for the one extra binding {@link #bindCounterpart} makes. */
    private static volatile Context sContext;

    /** Whether the extra binding above has been decided, so it is done at most once. */
    private static boolean sCounterpartTried;

    /**
     * Binds the other process name of the same reference rule when the named one's field never
     * moves.
     *
     * <p>The reference reader's rule is a guess about which DJI app the firmware answers on, and
     * on one RC Plus the agriculture process it picks there sends the axes with an empty button
     * field: the flight-mode code and every button bit are then missing, and SB is left with the
     * SDK's own late key. A field that never moves while the switch is being moved is that
     * state, and the other candidate of the same rule - DJI Fly's process, or the agriculture
     * one - is bound as an extra reader; its field is used until the named process starts moving
     * its own (see {@link #fromButtonSource}).
     */
    private static void bindCounterpart(int sdkSwitchMoves) {
        if (sCounterpartTried || sPreferredFieldAlive || !sSeen) {
            return;
        }
        if (sdkSwitchMoves < 2) {
            // The switch has not been moved since the app started (the opening value is one
            // move), so there is nothing to tell a dead field from a resting one.
            return;
        }
        final Context context = sContext;
        if (context == null) {
            return;
        }
        final Class<?> counterpart;
        if (sPreferred.equals(processOf(RcProtocolServiceAgras.class))) {
            counterpart = RcProtocolService.class;
        } else if (sPreferred.equals(RcProtocolService.PROCESS)) {
            counterpart = RcProtocolServiceAgras.class;
        } else {
            return;   // an industry or agriculture-X build has no second candidate
        }
        sCounterpartTried = true;
        start(context, counterpart);
        Log.i(TAG, "rc protocol: " + sPreferred + " moves no button field here - also listening in "
                + processOf(counterpart) + " for the buttons");
    }

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
     * When any message - axes or button field - last arrived from one of the service processes.
     *
     * <p>Not the same thing as {@link #ownsSticks()}, which only counts a packet whose axes
     * changed: this is what says the stream itself is alive, which is what the SDK's own copies
     * of the flight-mode switch defer to (see DjiMsdkBridge.noteProtocolFlightMode).
     */
    private static volatile long sLastMessageAt;

    /** How long a message keeps the stream alive for that test. */
    private static final long ALIVE_MS = 1500;

    /** Whether the service processes are delivering anything at all right now. */
    static boolean streamAlive() {
        final long at = sLastMessageAt;
        return at != 0 && SystemClock.uptimeMillis() - at < ALIVE_MS;
    }

    /**
     * Bind one of the four listeners. Binding is also what keeps it alive: a bound service lives
     * as long as the app process does, and no notification of its own is wanted - the link
     * service already shows one, and a second is noise on a screen that is the radio's.
     * BIND_IMPORTANT raises that process to foreground priority too, which is what the
     * reference reader's startForeground() buys it - without a second notification.
     */
    private static void start(final Context context, Class<?> service) {
        final Intent intent = new Intent(context, service);
        intent.putExtra(RcProtocolService.EXTRA_SINK, new Messenger(SINK));
        final String name = service.getSimpleName();
        try {
            boolean bound;
            try {
                bound = context.bindService(intent, CONNECTION,
                        Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            } catch (SecurityException e) {
                // BIND_IMPORTANT is only allowed for a client that is already foreground (the
                // runtime can be started from the application's own onCreate). A plain bind is
                // still better than no protocol reader at all.
                Log.i(TAG, "rc protocol: important binding refused, binding normally");
                bound = context.bindService(intent, CONNECTION, Context.BIND_AUTO_CREATE);
            }
            if (bound) {
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

    /** One packet's six axes: the four sticks, then the two wheels - all in the firmware's order. */
    private static void onAxes(int[] axes) {
        sLastPacketAt = SystemClock.uptimeMillis();
        if (!sSeen) {
            sSeen = true;
            Log.i(TAG, "sticks: DJI's protocol stream is delivering");
        }
        if (RcRawJoystick.ownsInputs()) {
            // The raw reports are the faster reader and they carry the dials too: this is only
            // the fallback, so the values are dropped rather than fought over.
            return;
        }
        for (int axis = 0; axis < STICK_COUNT; axis++) {
            try {
                DjiMsdkBridge.nativeOnDjiStick(axis, sdkValue(axes[axis]));
            } catch (Throwable t) {
                // A missing native side must not kill the reader.
                return;
            }
        }
        for (int dial = 0; dial < DIAL_COUNT; dial++) {
            pushDial(dial, axes[STICK_COUNT + dial]);
        }
    }

    /**
     * One wheel from the packet, for a dial the SDK does not move.
     *
     * <p>The wheels are the one pair of axes that stays with the SDK wherever it has them: the
     * first value the SDK *changes* is enough, and the packet never touches that dial again.
     * What is left is a dial the SDK delivers nothing for - the remote whose SDK publishes no
     * dial at all (the RC Pro 2, which MSDK v5 does not support) and the one whose dial key
     * hands out a single resting value and never another - and there the packets are what makes
     * P1/P2 move once the raw reports are not arriving.
     *
     * <p>Only a *movement* is published: a dial the SDK is not driving would otherwise jump to
     * this position at start-up, and the position it holds is the last one the user set anyway.
     *
     * <p>Scaled like the sticks: the packet's 1024 is the SDK's 660. A field that cannot be a
     * position is not published, because it would pin P1/P2 to an end of their range: an empty
     * wheel field is 0x0000 (or 0xFFFF on a firmware that sends all ones), which lands on or far
     * outside the bottom of the travel, and the dial then keeps its last position - the same
     * rule the raw reader applies to a raw axis field (see RcRawJoystick.pushAxes).
     */
    private static void pushDial(int dial, int packetValue) {
        if (DjiMsdkBridge.dialFromSdk(dial)) {
            return;
        }
        if (packetValue <= -PACKET_AXIS_RANGE || packetValue > PACKET_AXIS_RANGE) {
            synchronized (RcProtocolInputs.class) {
                if (sDialNotAPosition.add(dial)) {
                    Log.w(TAG, "dials: the " + dialName(dial) + " dial field reads " + packetValue
                            + " in DJI's protocol stream - not a position, so the dial keeps "
                            + "its last one");
                }
            }
            return;
        }
        if (sDialValue[dial] == Integer.MIN_VALUE) {
            // The first sighting is where the knob already is, not a movement: taking it as the
            // baseline is what keeps a dial the packet is about to take over from jumping.
            sDialValue[dial] = packetValue;
            return;
        }
        if (sDialValue[dial] == packetValue) {
            return;
        }
        sDialValue[dial] = packetValue;
        try {
            DjiMsdkBridge.nativeOnDjiDial(dial, sdkValue(packetValue));
        } catch (Throwable t) {
            return;
        }
        synchronized (RcProtocolInputs.class) {
            if (sDialFromStream.add(dial)) {
                Log.i(TAG, "dials: the " + dialName(dial)
                        + " dial comes from DJI's protocol stream - the SDK does not move it");
            }
        }
    }

    private static String dialName(int dial) {
        return dial == DjiMsdkBridge.DIAL_LEFT ? "left" : "right";
    }

    /** The value each dial was last published with, so only a movement is pushed. */
    private static final int[] sDialValue = {Integer.MIN_VALUE, Integer.MIN_VALUE};

    /** The dials the packet has taken over, so {@link #pushDial} says it once each. */
    private static final java.util.Set<Integer> sDialFromStream = new java.util.HashSet<>();

    /** The dials whose packet field was not a position, so that line is said once each. */
    private static final java.util.Set<Integer> sDialNotAPosition = new java.util.HashSet<>();

    /**
     * The packet's idea of full deflection in the SDK's: both come from the same hardware, but
     * the packet reports it as 16 bits around 1024 (so -1024..1024) and the SDK as a number
     * around zero that stops at 660 (see joystick.cpp, kDjiStickRange - the native side clamps
     * to that, so an unscaled value would go flat over the last third of the stick's travel).
     */
    private static int sdkValue(int packetValue) {
        if (packetValue > PACKET_AXIS_RANGE) {
            packetValue = PACKET_AXIS_RANGE;
        } else if (packetValue < -PACKET_AXIS_RANGE) {
            packetValue = -PACKET_AXIS_RANGE;
        }
        return packetValue * SDK_STICK_RANGE / PACKET_AXIS_RANGE;
    }

    /** Half of the packet's 16-bit axis range, so full deflection either way. */
    private static final int PACKET_AXIS_RANGE = 1024;

    /** Half of the SDK's range, which is what the native side takes. */
    private static final int SDK_STICK_RANGE = 660;

    /** One status line when the stream stops or comes back, so a log says which reader has it. */
    private static void watchdog() {
        bindCounterpart(DjiMsdkBridge.sdkSwitchMoves());
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

