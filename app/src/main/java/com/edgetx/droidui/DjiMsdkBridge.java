package com.edgetx.droidui;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import dji.sdk.keyvalue.key.DJIFlightControllerKey;
import dji.sdk.keyvalue.key.DJIKey;
import dji.sdk.keyvalue.key.DJIKeyInfo;
import dji.sdk.keyvalue.key.DJIRemoteControllerKey;
import dji.sdk.keyvalue.key.KeyTools;
import dji.sdk.keyvalue.value.common.EmptyMsg;
import dji.sdk.keyvalue.value.common.LocationCoordinate2D;
import dji.sdk.keyvalue.value.remotecontroller.BatteryInfo;
import dji.sdk.keyvalue.value.remotecontroller.FiveDimensionPressedStatus;
import dji.sdk.keyvalue.value.remotecontroller.RcCustomButtonEvent;
import dji.sdk.keyvalue.value.remotecontroller.RcCustomButtonHardwareStatus;
import dji.sdk.keyvalue.value.remotecontroller.RCFlightModeSwitch;
import dji.sdk.keyvalue.value.remotecontroller.RcGPSInfo;
import dji.sdk.keyvalue.value.remotecontroller.RemoteControllerType;
import dji.sdk.keyvalue.value.remotecontroller.RCTransformationSwitchState;
import dji.sdk.keyvalue.value.remotecontroller.RcSoftSwitchMode;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import dji.v5.common.register.DJISDKInitEvent;
import dji.v5.manager.KeyManager;
import dji.v5.manager.SDKManager;
import dji.v5.manager.interfaces.SDKManagerCallback;

import java.io.File;

/**
 * Reads remote-controller buttons through the DJI Mobile SDK.
 *
 * <p>Only the RC Plus 2's 5-way switch <em>directions</em> matter here. The device
 * reports them as {@code ABS_HAT0X} / {@code ABS_HAT0Y} motion events, which the
 * RC firmware never dispatches to apps - so Android's input layer cannot see
 * them at all (see {@code joystick.cpp}). The SDK is the only documented API that
 * knows about the 5-way switch ({@code RcCustomizableButton.CUSTOM_BUTTON_FIVE_D_*}).
 *
 * <p>This class is only loaded when the SDK is packaged ({@code dji.msdk=true});
 * {@link EdgeTxApplication} guards the load reflectively.
 */
public final class DjiMsdkBridge {
    private static final String TAG = "EdgeTXUI";

    /** Button ids handed to joystick.cpp. Keep in sync with nativeOnDjiButton. */
    static final int BTN_UP = 1;
    static final int BTN_DOWN = 2;
    static final int BTN_LEFT = 3;
    static final int BTN_RIGHT = 4;
    static final int BTN_PRESS = 5;

    private static final Object OWNER = new Object();
    private static DJIKey<RcCustomButtonEvent> sButtonKey;
    private static boolean sListening;

    /**
     * Which SDK key a remote's buttons answer on is not documented and not the same on
     * different remotes: the RC Plus 2 fires the plain Boolean keys while several of them
     * never arrive from an RC Pro at all. With the trigger file {@link #PROBE_TRIGGER} next
     * to the app's external files every button key the SDK has is listened to and logged, so
     * a new remote can be identified by pressing one control at a time and reading which key
     * answered. On top of that every key {@link DJIRemoteControllerKey} defines - including
     * the ones nothing here uses yet - is asked whether this remote has it, and the answer is
     * logged as one matrix (see {@link #probeEveryRemoteControllerKey}). Nothing else changes
     * while it is off, and no probe listener is installed.
     */
    private static final String PROBE_TRIGGER = "hid_probe";
    private static volatile boolean sProbe;

    /** A separate owner, so a probed key can also be one the normal code listens to. */
    private static final Object PROBE_OWNER = new Object();

    /**
     * The keys {@link #probe} already listens to. Only the sweep needs it: it walks the whole
     * key class, which includes the ones probed by name, and subscribing twice would log every
     * change of those twice.
     */
    private static final java.util.Set<DJIKeyInfo<?>> sProbedKeys =
            java.util.Collections.synchronizedSet(new java.util.HashSet<DJIKeyInfo<?>>());

    static {
        // libedgetx_ui.so is normally loaded by NativeActivity, which registers it
        // against the framework's class loader. ART only looks for a native method
        // in the libraries registered against the loader of the class that declares
        // it, so without this the call below dies with UnsatisfiedLinkError.
        // Loading it again here is a no-op for the linker itself.
        try {
            System.loadLibrary("edgetx_ui");
        } catch (Throwable t) {
            Log.w(TAG, "dji: could not load edgetx_ui", t);
        }
    }

    private DjiMsdkBridge() {}

    /**
     * A 5-way direction (one of the {@code BTN_*} ids above).
     *
     * <p>Up and down turn EdgeTX's rotary encoder by one detent - up is a step
     * left, down a step right. Left and right page back and forward, and press is
     * Enter. See the switch in joystick.cpp for the whole table.
     */
    static native void nativeOnDjiButton(int buttonId);

    /**
     * One press of the RC's go-home button. joystick.cpp counts them: one press puts
     * the 5-way back to normal, two make it trim the left stick, three the right one.
     */
    static native void nativeOnDjiGoHome();

    /**
     * Stick positions. Android never dispatches the RC's stick MotionEvents, and
     * /dev/input/event4 cannot be opened by an app, so these four SDK keys are the
     * only source. `axis` is 0 left-horizontal, 1 left-vertical, 2 right-horizontal,
     * 3 right-vertical; `value` is the raw SDK reading, centred on zero.
     */
    static native void nativeOnDjiStick(int axis, int value);

    /**
     * Hardware switch position. `index` is 0 = SA, 1 = SB (the board's switch
     * table); `state` is <0 up, 0 middle, >0 down.
     */
    static native void nativeOnDjiSwitch(int index, int state);

    /**
     * The last position this process sent for a switch: <0 up, 0 middle, >0 down, and 0
     * while nothing has moved it yet. Read before flipping one of our own buttons' switches,
     * because the R1/R2/R3 keys and a restored position can move the same switch without
     * this side seeing it.
     */
    static native int nativeSwitchState(int index);

    /**
     * The RC's controls are reporting - or never will. joystick.cpp stops holding the
     * firmware back and EdgeTX boots (see the readiness section below).
     */
    static native void nativeSetInputsReady();

    /**
     * Absolute dial position. `dial` is 0 = left, 1 = right; the dials report the
     * same -660..660 range as the sticks.
     *
     * They are the board's P1 and P2 inputs, so whatever a radio normally does with
     * a pot - Volume and Backlight through a special function - works with them.
     */
    static native void nativeOnDjiDial(int dial, int value);

    /**
     * Scroll-wheel movement, in detents (the SDK value is relative).
     *
     * No built-in control produces the MODEL or TELE keys: the 5-way's up/down
     * report the plain UP/DOWN keys instead.
     */
    static native void nativeOnDjiScrollWheel(int steps);

    // ------------------------------------------------------ startup readiness --
    //
    // EdgeTX's boot checks read the throttle and the switches once and warn about what
    // they find, so the firmware is not started until the RC's own controls are actually
    // reporting - joystick::inputsReady() is what link_start() waits on. Without this
    // the throttle still reads as centred while the SDK starts up, and the user gets
    // "throttle not at idle" for a stick that is at the bottom.

    /** True once joystick.cpp has been told it may let the firmware boot. */
    private static boolean sInputsReady;
    /** Set once listen() is done, i.e. the switch positions are in place. */
    private static boolean sListeningDone;
    /** The four stick axes, each counted the first time it reports. */
    private static final boolean[] sStickSeen = new boolean[4];
    private static int sSticksSeen;

    /**
     * The sticks are what the boot checks care about most - the throttle warning is
     * decided from one of them, and a value that has not arrived yet reads as centre.
     * Both the listeners and the poller come through here.
     */
    private static void markStickSeen(int axis) {
        if (axis < 0 || axis >= sStickSeen.length) return;
        synchronized (DjiMsdkBridge.class) {
            if (!sStickSeen[axis]) {
                sStickSeen[axis] = true;
                sSticksSeen++;
            }
        }
        checkInputsReady();
    }

    private static void markListeningDone() {
        synchronized (DjiMsdkBridge.class) {
            sListeningDone = true;
        }
        checkInputsReady();
    }

    /** Registration or listening failed: nothing is coming, so do not wait for it. */
    private static void markInputsReady(String why) {
        synchronized (DjiMsdkBridge.class) {
            if (sInputsReady) return;
        }
        Log.i(TAG, "dji: " + why + " - nothing left to wait for, the firmware may boot");
        releaseFirmwareStart();
    }

    private static void checkInputsReady() {
        final int seen;
        synchronized (DjiMsdkBridge.class) {
            if (sInputsReady || !sListeningDone || sSticksSeen < sStickSeen.length) return;
            seen = sSticksSeen;
        }
        Log.i(TAG, "dji: sticks reporting (" + seen + "/4), the firmware may boot");
        releaseFirmwareStart();
    }

    private static void releaseFirmwareStart() {
        synchronized (DjiMsdkBridge.class) {
            if (sInputsReady) return;
            sInputsReady = true;
        }
        try {
            nativeSetInputsReady();
        } catch (Throwable t) {
            Log.w(TAG, "dji: could not release the firmware start", t);
        }
    }

    public static void init(Application app) {
        // Before anything is dispatched: where the latching switches were left.
        sPrefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        restoreLatchedSwitches();

        // Verbose input diagnostics, decided once at start (see PROBE_TRIGGER).
        try {
            sProbe = new File(app.getExternalFilesDir(null), PROBE_TRIGGER).isFile();
        } catch (Throwable t) {
            sProbe = false;
        }

        // EdgeTX's haptics are played on the remote's own motor, and that does not need the SDK
        // (see shakeRemote), so the motor is looked up before any of the registration below.
        try {
            sVibrator = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Throwable t) {
            sVibrator = null;
        }
        Log.i(TAG, "dji: EdgeTX haptics go to the remote's own motor: "
                + (sVibrator != null && sVibrator.hasVibrator() ? "yes" : "no motor here"));

        if (SDKManager.getInstance().isRegistered()) {
            listen();
            return;
        }
        SDKManager.getInstance().init(app, new SDKManagerCallback() {
            @Override public void onRegisterSuccess() {
                Log.i(TAG, "dji: register success");
                listen();
            }

            @Override public void onRegisterFailure(IDJIError error) {
                Log.w(TAG, "dji: register failed: " + error);
                markInputsReady("registration failed");
            }

            @Override public void onProductDisconnect(int id) { }

            @Override public void onProductConnect(int id) { }

            @Override public void onProductChanged(int id) { }

            @Override public void onInitProcess(DJISDKInitEvent event, int totalProcess) {
                // init() is asynchronous: calling registerApp() straight after it
                // returns fails with REGISTER_WITHOUT_INIT. Registration has to wait
                // for the SDK to finish initialising.
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    Log.i(TAG, "dji: sdk initialised, registering app");
                    // The App Key comes from the manifest meta-data com.dji.sdk.API_KEY.
                    SDKManager.getInstance().registerApp();
                }
            }

            @Override public void onDatabaseDownloadProgress(long current, long total) { }
        });
    }

    private static void listen() {
        if (sListening) {
            return;
        }
        try {
            // Which remote this is decides the slots below, the flight-mode table and whether
            // the remote's own log may move a switch - so it goes into the log once, with the
            // whole layout, before any of it is used: a button on the wrong switch is otherwise
            // indistinguishable from a button that never arrives.
            Log.i(TAG, "dji: remote " + REMOTE.label + " (" + Build.DEVICE + " / "
                    + Build.MODEL + "): " + REMOTE.describe());
            // What the remote actually has, asked of the SDK rather than assumed: the layout
            // below is the same for a whole family, but whether a given button exists on this
            // one does not. One line for the whole set, then one for whatever has no switch
            // here - that is what "this button does nothing" turns out to be.
            probeKeySupport();
            logKeySupport();
            logRemoteType();
            sButtonKey = KeyTools.createKey(DJIRemoteControllerKey.KeyRcButtonEventPro);
            KeyManager.getInstance().listen(sButtonKey, OWNER,
                    new CommonCallbacks.KeyListener<RcCustomButtonEvent>() {
                        @Override
                        public void onValueChange(RcCustomButtonEvent oldValue,
                                                  RcCustomButtonEvent newValue) {
                            if (newValue == null) {
                                return;
                            }
                            final Integer action = newValue.getButtonActionValue();
                            Log.i(TAG, "dji: rc button event " + newValue.getValue()
                                    + " action=" + action
                                    + " c1=" + newValue.getIsC1Click()
                                    + " c2=" + newValue.getIsC2Click()
                                    + " c3=" + newValue.getIsC3Click()
                                    + " c4=" + newValue.getIsC4Click());
                            // Diagnostic only. The 5-way switch is driven by
                            // KeyFiveDimensionPressedStatus below; acting on this
                            // event as well would fire every press twice.
                        }
                    });
            listenFiveDimension();
            listenButtonKeys();
            listenSticks();
            startPolling();
            listenSwitches();
            listenArmState();
            listenBattery();
            listenDials();
            if (sProbe) probeKeys();
            startHapticPolling();
            Log.i(TAG, "dji: registered, product category "
                    + SDKManager.getInstance().getProductCategory());
            sListening = true;
            markListeningDone();
            Log.i(TAG, "dji: listening for RC button events");
        } catch (Throwable t) {
            Log.w(TAG, "dji: listen failed", t);
            markInputsReady("listening failed");
        }
    }

    // ------------------------------------------------- what this remote actually has --
    //
    // RcModel says where a button goes; whether the button exists on this particular remote is
    // a different question, and the SDK answers it: isKeySupported() is true only for the keys
    // the remote publishes. A key the remote does not have never fires, so listening to it
    // costs nothing - but a key it does have and this app has no switch for is exactly the
    // "some buttons do nothing" report, and without this it stayed invisible until someone
    // pressed it with the probe file in place.
    //
    // Both answers are asked once at start-up and logged: the whole matrix on one line, then
    // the keys that exist here but have nowhere to go. That is what makes a remote this port
    // has never seen adaptable without measuring it by hand.

    /** The SDK's key names and whether this remote has them, in the order they are asked. */
    private static final java.util.Map<String, Boolean> SUPPORTED =
            new java.util.LinkedHashMap<>();

    private static void probeKeySupport() {
        support("record", DJIRemoteControllerKey.KeyRecordButtonDown);
        support("shutter", DJIRemoteControllerKey.KeyShutterButtonDown);
        support("shutterLongPress", DJIRemoteControllerKey.KeyRCShutterButtonLongPress);
        support("pause", DJIRemoteControllerKey.KeyPauseButtonDown);
        support("goHome", DJIRemoteControllerKey.KeyGoHomeButtonDown);
        support("takeoff", DJIRemoteControllerKey.KeyRCAuthLedButtonDown);
        support("C1", DJIRemoteControllerKey.KeyCustomButton1Down);
        support("C2", DJIRemoteControllerKey.KeyCustomButton2Down);
        support("C3", DJIRemoteControllerKey.KeyCustomButton3Down);
        support("C4", DJIRemoteControllerKey.KeyRCCustomButton4Down);
        support("playback", DJIRemoteControllerKey.KeyRCPlaybackButtonDown);
        support("rcSwitch", DJIRemoteControllerKey.KeyRCSwitchButtonDown);
        support("rightWheel", DJIRemoteControllerKey.KeyRCRightWheelButtonDown);
        support("scrollWheel", DJIRemoteControllerKey.KeyScrollWheel);
        support("leftDial", DJIRemoteControllerKey.KeyLeftDial);
        support("rightDial", DJIRemoteControllerKey.KeyRightDial);
        support("fiveWay", DJIRemoteControllerKey.KeyFiveDimensionPressedStatus);
        support("flightMode", DJIRemoteControllerKey.KeyFlightModeSwitchState);
        support("softSwitchMode", DJIRemoteControllerKey.KeySoftSwitchMode);
        support("transform", DJIRemoteControllerKey.KeyRCTransformationSwitchState);
    }

    /** One of those keys, whether this remote has it; false when the question itself fails. */
    private static <T> boolean support(String name, DJIKeyInfo<T> info) {
        boolean supported = false;
        try {
            supported = KeyManager.getInstance().isKeySupported(createKeyOf(info));
        } catch (Throwable t) {
            Log.w(TAG, "dji: could not ask whether this remote has '" + name + "'", t);
        }
        SUPPORTED.put(name, supported);
        return supported;
    }

    /** Whether this remote has a key, as answered at start-up. */
    private static boolean has(String name) {
        return Boolean.TRUE.equals(SUPPORTED.get(name));
    }

    private static void logKeySupport() {
        final StringBuilder matrix = new StringBuilder();
        for (java.util.Map.Entry<String, Boolean> key : SUPPORTED.entrySet()) {
            if (matrix.length() > 0) matrix.append(' ');
            matrix.append(key.getKey()).append(Boolean.TRUE.equals(key.getValue()) ? "+" : "-");
        }
        Log.i(TAG, "dji: this remote's SDK keys (+ has it, - does not): " + matrix);

        final StringBuilder nowhere = new StringBuilder();
        for (String name : keysWithoutSwitch()) {
            if (!has(name)) continue;
            if (nowhere.length() > 0) nowhere.append(", ");
            nowhere.append(name);
        }
        if (nowhere.length() > 0) {
            Log.i(TAG, "dji: this remote has keys with no EdgeTX switch here: " + nowhere
                    + " - see RcModel if one of them should drive something");
        }
    }

    /**
     * The keys this remote may have that the layout puts nowhere. Nothing else is listed: the
     * rest of the matrix either drives a switch already or is an axis (the sticks, the dials,
     * the scroll wheel, the 5-way) rather than a button.
     */
    private static String[] keysWithoutSwitch() {
        return SLOT_ROUND >= 0
                ? new String[] {"shutterLongPress", "playback", "rcSwitch", "rightWheel",
                                "transform"}
                : new String[] {"shutterLongPress", "playback", "rcSwitch", "rightWheel",
                                "transform", "C4"};
    }

    /**
     * What the SDK thinks this remote is. Only ever logged: which layout to use still comes
     * from {@link RcModel}, because Build.DEVICE names every remote this port runs on while the
     * SDK's enum has no value for the newest ones (DJI's answer about the RC Pro 2 is that the
     * MSDK does not support it at all). It is here because the two disagreeing is worth seeing
     * in a log, and because on a remote that is not in the table this line says what it is.
     */
    private static void logRemoteType() {
        try {
            final RemoteControllerType type = KeyManager.getInstance().getValue(
                    KeyTools.createKey(DJIRemoteControllerKey.KeyRemoteControllerType),
                    RemoteControllerType.UNKNOWN);
            Log.i(TAG, "dji: the SDK reports remote controller type " + type);
        } catch (Throwable t) {
            Log.w(TAG, "dji: could not read the remote controller type", t);
        }
    }

    /**
     * Every button key the SDK has, logged as it changes, plus the sweep that asks this remote
     * about the whole key class. Only ever installed while the trigger file exists - see
     * {@link #PROBE_TRIGGER}. The names are the key names with the
     * {@code Key...Down} noise removed, because a name like {@code goHome} that never fires
     * while the physical button works is exactly the answer this probe is after.
     */
    private static void probeKeys() {
        Log.i(TAG, "dji: key probe on, listening to every button key the SDK has");
        probe("record", DJIRemoteControllerKey.KeyRecordButtonDown);
        probe("shutter", DJIRemoteControllerKey.KeyShutterButtonDown);
        probe("shutterLongPress", DJIRemoteControllerKey.KeyRCShutterButtonLongPress);
        probe("authLed", DJIRemoteControllerKey.KeyRCAuthLedButtonDown);
        probe("pause", DJIRemoteControllerKey.KeyPauseButtonDown);
        probe("goHome", DJIRemoteControllerKey.KeyGoHomeButtonDown);
        probe("C1", DJIRemoteControllerKey.KeyCustomButton1Down);
        probe("C2", DJIRemoteControllerKey.KeyCustomButton2Down);
        probe("C3", DJIRemoteControllerKey.KeyCustomButton3Down);
        probe("C4", DJIRemoteControllerKey.KeyRCCustomButton4Down);
        probe("rcSwitch", DJIRemoteControllerKey.KeyRCSwitchButtonDown);
        probe("playback", DJIRemoteControllerKey.KeyRCPlaybackButtonDown);
        probe("rightWheel", DJIRemoteControllerKey.KeyRCRightWheelButtonDown);
        probe("scrollWheel", DJIRemoteControllerKey.KeyScrollWheel);
        probe("flightMode", DJIRemoteControllerKey.KeyFlightModeSwitchState);
        probe("softSwitchMode", DJIRemoteControllerKey.KeySoftSwitchMode);
        probe("flightModeString", DJIFlightControllerKey.KeyFlightModeString);
        probe("fcSwitchMode", DJIFlightControllerKey.KeyFCRemoteControllerSwitchMode);
        probe("fcFlightMode", DJIFlightControllerKey.KeyFCFlightMode);
        probe("transform", DJIRemoteControllerKey.KeyRCTransformationSwitchState);
        probe("uavLock", DJIRemoteControllerKey.KeyUavLockStatus);
        probe("machineMode", DJIRemoteControllerKey.KeyRcMachineMode);
        probe("rcShake", DJIRemoteControllerKey.KeyRcShakeMotor);
        reportSupport("flightMode", DJIRemoteControllerKey.KeyFlightModeSwitchState);
        reportSupport("softSwitchMode", DJIRemoteControllerKey.KeySoftSwitchMode);
        reportSupport("transform", DJIRemoteControllerKey.KeyRCTransformationSwitchState);
        reportSupport("hardwareState", DJIRemoteControllerKey.KeyRcHardwareState);
        reportSupport("buttonEventPro", DJIRemoteControllerKey.KeyRcButtonEventPro);
        reportSupport("checkStatus", DJIRemoteControllerKey.KeyRcCheckStatus);
        reportSupport("appWorkStage", DJIRemoteControllerKey.KeyRcAppWorkStage);
        reportSupport("customBtnFunction", DJIRemoteControllerKey.KeyCustomBtnFunction);
        reportSupport("machineMode", DJIRemoteControllerKey.KeyRcMachineMode);
        reportSupport("flightModeString", DJIFlightControllerKey.KeyFlightModeString);
        reportSupport("fcSwitchMode", DJIFlightControllerKey.KeyFCRemoteControllerSwitchMode);
        reportSupport("fcFlightMode", DJIFlightControllerKey.KeyFCFlightMode);
        reportSupport("rcShake", DJIRemoteControllerKey.KeyRcShakeMotor);

        // Everything the class defines, not only the keys listed above: this is the sweep
        // that answers "which keys does this remote have at all", including the ones nothing
        // in the app uses yet. On its own thread - see probeEveryRemoteControllerKey().
        final Thread sweep = new Thread(DjiMsdkBridge::probeEveryRemoteControllerKey,
                "dji-key-sweep");
        sweep.setDaemon(true);
        sweep.start();
    }

    /**
     * Whether the SDK has a handler for a key on this remote at all.
     *
     * <p>Worth asking because "the key never calls back" has two very different causes: the remote
     * does not publish it, or the SDK has nothing behind the name here. This is the same question
     * the direct getValue() answers with REQUEST_HANDLER_NOT_FOUND for the flight-mode switch.
     */
    private static <T> void reportSupport(final String name, DJIKeyInfo<T> info) {
        try {
            final boolean supported = KeyManager.getInstance().isKeySupported(createKeyOf(info));
            Log.i(TAG, "dji: key " + name + (supported ? " is supported" : " is NOT supported")
                    + " on this remote");
        } catch (Throwable t) {
            Log.w(TAG, "dji: could not ask about " + name, t);
        }
    }

    /** One probed key: every callback, initial snapshot included. */
    private static <T> void probe(String name, DJIKeyInfo<T> info) {
        sProbedKeys.add(info);
        try {
            KeyManager.getInstance().listen(KeyTools.createKey(info), PROBE_OWNER,
                    new CommonCallbacks.KeyListener<T>() {
                        @Override
                        public void onValueChange(T oldValue, T newValue) {
                            Log.i(TAG, "dji: probe " + name + " " + oldValue + " -> "
                                    + newValue);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: probe " + name + " could not listen", t);
        }
    }

    /**
     * Every key {@link DJIRemoteControllerKey} defines, asked once: whether this remote has it,
     * and what the key itself says it is for.
     *
     * <p>The point is the eighty-odd keys nothing here uses yet. "Does this remote have
     * {@code KeyRcUpgradeVoiceEnable}?" cannot be answered by pressing anything, and it is
     * exactly what decides whether a key is worth wiring up. The other half comes from
     * {@link DJIKeyInfo} itself: {@code isCanListen()} false means the key is a set or an
     * action, so it is reported and never subscribed - nothing in this sweep writes to the
     * remote or performs anything on it.
     *
     * <p>Reflected rather than listed: the class has 117 fields today, and a hand-written copy
     * here would be the one place that silently goes stale when the SDK adds one. It runs on
     * its own thread because every {@link KeyManager#isKeySupported} is a call into the SDK,
     * and a hundred of those do not belong on the thread the activity's lifecycle is on.
     */
    private static void probeEveryRemoteControllerKey() {
        final StringBuilder supported = new StringBuilder();
        final StringBuilder missing = new StringBuilder();
        final StringBuilder writeOnly = new StringBuilder();
        final StringBuilder unwatched = new StringBuilder();
        final StringBuilder watched = new StringBuilder();
        int total = 0;
        int found = 0;
        int listening = 0;
        for (java.lang.reflect.Field field : DJIRemoteControllerKey.class.getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            if (!DJIKeyInfo.class.isAssignableFrom(field.getType())) continue;

            final DJIKeyInfo<?> info;
            try {
                final Object value = field.get(null);
                if (!(value instanceof DJIKeyInfo)) continue;
                info = (DJIKeyInfo<?>) value;
            } catch (Throwable t) {
                continue;
            }

            total++;
            if (!supportedKey(info)) {
                appendName(missing, field.getName());
                continue;
            }
            found++;
            appendName(supported, field.getName());

            if (!info.isCanListen()) {
                appendName(writeOnly, field.getName());
                continue;
            }
            // Reading the RC's password out of the remote and into logcat is not a diagnostic.
            if (PASSWORD_KEY.equals(field.getName())) continue;
            if (alreadyListened(info) || sProbedKeys.contains(info)
                    || !worthWatching(field.getName())) {
                appendName(unwatched, field.getName());
                continue;
            }
            probeKey(field.getName(), info);
            appendName(watched, field.getName());
            listening++;
        }

        Log.i(TAG, "dji: key sweep " + found + "/" + total
                + " keys of DJIRemoteControllerKey are supported here");
        Log.i(TAG, "dji: key sweep supported: " + supported);
        Log.i(TAG, "dji: key sweep not supported: " + missing);
        if (writeOnly.length() > 0) {
            Log.i(TAG, "dji: key sweep supported but set/action only, left alone: " + writeOnly);
        }
        if (unwatched.length() > 0) {
            Log.i(TAG, "dji: key sweep supported, not watched (levels, or probed above): "
                    + unwatched);
        }
        Log.i(TAG, "dji: key sweep now listening to " + listening + " more key(s): " + watched);
    }

    /** The one key whose value is a credential rather than a diagnostic. */
    private static final String PASSWORD_KEY = "KeyRcPassword";

    /**
     * Keys the app already listens to at the rate they report - the four stick axes and the two
     * dials. A second listener on those would double every line of a stream that is already
     * counted, and their values are not what a probe is for.
     */
    private static final DJIKeyInfo<?>[] ALREADY_LISTENED = {
            DJIRemoteControllerKey.KeyStickLeftHorizontal,
            DJIRemoteControllerKey.KeyStickLeftVertical,
            DJIRemoteControllerKey.KeyStickRightHorizontal,
            DJIRemoteControllerKey.KeyStickRightVertical,
            DJIRemoteControllerKey.KeyLeftDial,
            DJIRemoteControllerKey.KeyRightDial,
    };

    private static boolean alreadyListened(DJIKeyInfo<?> info) {
        for (DJIKeyInfo<?> listened : ALREADY_LISTENED) {
            if (listened == info) return true;
        }
        return false;
    }

    /** Whether this remote publishes a key at all; false when the question itself fails. */
    private static boolean supportedKey(DJIKeyInfo<?> info) {
        try {
            return KeyManager.getInstance().isKeySupported(createKeyOf(info));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Keys worth watching for changes during a probe: the ones whose name says they are a
     * control. The rest of the class is levels and one-off messages - serial numbers,
     * calibration results, multi-RC bookkeeping - and a listener on those would fill logcat
     * without answering what the probe asks (which key does pressing this button fire).
     */
    private static boolean worthWatching(String name) {
        return name.endsWith("Down") || name.contains("Button") || name.contains("Switch")
                || name.contains("Wheel") || name.contains("Dial") || name.contains("Stick")
                || name.contains("FiveDimension");
    }

    private static void appendName(StringBuilder sb, String name) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(name);
    }

    /** The sweep reaches the key through reflection, so its type is only known at run time. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void probeKey(String name, DJIKeyInfo info) {
        probe(name, info);
    }

    /**
     * The 5-way switch is reported as a <em>level</em> - which directions are held
     * down right now - rather than as press events, so the native side is only told
     * about the rising edge of each direction.
     */
    private static void listenFiveDimension() {
        try {
            final DJIKey<FiveDimensionPressedStatus> key =
                    KeyTools.createKey(DJIRemoteControllerKey.KeyFiveDimensionPressedStatus);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<FiveDimensionPressedStatus>() {
                        @Override
                        public void onValueChange(FiveDimensionPressedStatus oldValue,
                                                  FiveDimensionPressedStatus newValue) {
                            Log.i(TAG, "dji: 5-way " + describe(newValue));
                            dispatchFiveDimension(newValue);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: 5-way listen failed", t);
        }

        // KeyRcHardwareState carries the same struct alongside the C1..C4 flags.
        try {
            final DJIKey<RcCustomButtonHardwareStatus> key =
                    KeyTools.createKey(DJIRemoteControllerKey.KeyRcHardwareState);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<RcCustomButtonHardwareStatus>() {
                        @Override
                        public void onValueChange(RcCustomButtonHardwareStatus oldValue,
                                                  RcCustomButtonHardwareStatus newValue) {
                            if (newValue == null) {
                                return;
                            }
                            // A level, pushed every few hundred milliseconds even when
                            // nothing is held, so only a change is worth a line. The C1..C4
                            // flags ride along because on some remotes they are the only
                            // place a custom button shows up at all.
                            final String state =
                                    describe(newValue.getFiveDimensionPressStatus())
                                    + " c1=" + newValue.getIsC1Click()
                                    + " c2=" + newValue.getIsC2Click()
                                    + " c3=" + newValue.getIsC3Click()
                                    + " c4=" + newValue.getIsC4Click();
                            synchronized (DjiMsdkBridge.class) {
                                if (state.equals(sLastHardwareState) && !sProbe) {
                                    dispatchFiveDimension(newValue.getFiveDimensionPressStatus());
                                    return;
                                }
                                sLastHardwareState = state;
                            }
                            Log.i(TAG, "dji: rc hardware " + state);
                            dispatchFiveDimension(newValue.getFiveDimensionPressStatus());
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: rc hardware listen failed", t);
        }
    }

    // ------------------------------------------------------- RC buttons -> switches
    //
    // The RC's photo, video and pause buttons reach neither Android's input layer
    // nor KeyRcButtonEventPro: the kernel key codes behind them are eaten by the
    // RC's own dpad service in SystemUI, and the pause button is not even in the
    // kernel input stream (the raw gpio-keys device stays silent for it). The
    // SDK's plain Boolean button keys do work, so SC/SD/SE and SF are driven from those.
    // Indices are the board's switch table, see joystick.cpp requestSwitch(); which of
    // them a given remote's button drives is that remote's row in RcModel.
    //
    // Driving them from the SDK rather than from Android key codes is what makes them
    // keep working while the UI is gone (the app swiped away, RcLinkService still holding
    // the firmware): key events only reach a window that has focus, the SDK's callbacks do
    // not need one. SI is the exception: the R1/R2/R3 buttons are Android key codes F4..F6
    // (see joystick.cpp: kPositionKeys) and KeyRcButtonEventPro never fires on this remote,
    // so SI has no SDK route and stops with the window. SF/SG/SH are on the SDK as C1/C2/C3.
    //
    // Which reader actually delivers a given button differs per reader and per remote: the SDK's
    // keys go silent in waves, the raw reports carry four buttons only, and the remote's own log
    // needs READ_LOGS. That is no longer decided here button by button - every reader feeds the
    // same place (RcButtons), which merges their copies of one press, so no button is left
    // waiting on a single reader that happens to be having a bad day.
    //
    // Measured on the RC: all of them fire. The shutter is the odd one - it reports a press
    // but not its half press, so it drives a switch of its own, see listenShutter().

    static final int SWITCH_C = RcModel.SC;   // video on the Plus family, pause on the Pro family
    static final int SWITCH_D = RcModel.SD;   // pause on the Plus family, video on the Pro family
    static final int SWITCH_E = RcModel.SE;   // photo on the Plus family, round button on the RC Pro
    static final int SWITCH_F = RcModel.SF;   // C1 on the Plus family, the photo switch on the Pro family

    /**
     * The shutter button as a switch (SLOT_PHOTO - SE on one remote, SF on the other, see
     * the slot constants below).
     *
     * The button's two stages cannot be told apart, because only one of them is reported at
     * all: the remote's half press (the focus detent) is in none of the data - MSDK has no
     * key for it, and the remote's input device carries no camera/focus key code either -
     * while a press all the way down shows up as a single Boolean:
     *
     *     KeyShutterButtonDown   false -> true
     *
     * (Measured on the RC: two half presses held for 2 s each produced no callback at all,
     * two full presses produced exactly one each. KeyRCShutterButtonLongPress, the other
     * shutter key MSDK offers, has never fired once here, so there is no hold time to go on
     * either.)
     *
     * What is left is a single "the shutter was pressed" event, so every press steps that
     * switch to the other end: low <-> high. That way each press shows up as a change wherever
     * it was standing, which pressing to the end it already sits at would not. The middle
     * position is where it sits until the first press.
     *
     * The slot is a switch that exists on every card this port boots - SE on the Plus family,
     * SF on the Pro one - so the shutter never needs a switch of its own.
     */
    private static final int SHUTTER_LOW = 1;    // the down end
    private static final int SHUTTER_HIGH = -1;  // the up end

    private static void setShutterPosition(int position) {
        sShutterPosition = position;
        persistSwitch(PREF_PHOTO, position);
        // The letter is derived, not written out: this line used to say "switch E" whatever
        // the remote, which reads as a wrong mapping in the log while the switch it drives is
        // a different one.
        Log.i(TAG, "dji: shutter -> switch " + (char) ('A' + SLOT_PHOTO) + " "
                + (position > 0 ? "low" : "high"));
        nativeOnDjiSwitch(SLOT_PHOTO, position);
    }

    /**
     * One edge of the photo button, from either of the two sources that carry it.
     *
     * <p>Both do: the SDK's {@code KeyShutterButtonDown} and the joystick's raw report (byte
     * 16, bit 0x08). Which one arrives - and in what order - depends on the remote and on the
     * press itself: on the RC Pro the report is the reliable one and the SDK key fires only
     * now and then, while on the RC Plus 2 it is the other way round. A press that both
     * deliver would otherwise step that switch twice and leave it where it started, which is
     * exactly how it looked before this existed (measured: "shutter -> low" 46 ms before
     * "shutter -> high" on one press out of four). One physical press is one step, so the
     * second copy is dropped by {@link RcButtons}, which is where that rule lives now - and
     * because both readers are wired in, this button also works on a remote whose shutter key
     * never answers at all.
     */
    private static void shutterEdge(boolean down, int source) {
        final int what = RcButtons.edge(SLOT_PHOTO, source, down);
        if (what == RcButtons.MERGED) {
            logMerged("photo", source, SLOT_PHOTO);
            return;
        }
        if (what != RcButtons.PRESS) {
            return;
        }
        setShutterPosition(sShutterPosition > 0 ? SHUTTER_HIGH : SHUTTER_LOW);
    }

    /** Last RC hardware state written to the log, so an unchanged level is not logged. */
    private static String sLastHardwareState;

    private static void listenShutter() {
        try {
            final DJIKey<Boolean> key =
                    KeyTools.createKey(DJIRemoteControllerKey.KeyShutterButtonDown);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Boolean>() {
                        @Override
                        public void onValueChange(Boolean oldValue, Boolean newValue) {
                            Log.i(TAG, "dji: shutter " + oldValue + " -> " + newValue);
                            if (oldValue == null) {
                                return;   // the SDK's initial snapshot, not a release
                            }
                            shutterEdge(Boolean.TRUE.equals(newValue), RcButtons.SOURCE_SDK);
                        }
                    });

            // This slot latches, so a restart brings back the position of the last press; the
            // middle position is where it sits until the first one.
            nativeOnDjiSwitch(SLOT_PHOTO, sShutterPosition);
        } catch (Throwable t) {
            Log.w(TAG, "dji: shutter listen failed", t);
        }
    }

    private static void listenButtonKeys() {
        // The video button latches, like the takeoff button (SA) and the pause button (SD):
        // one press and it stays where it went. Held down it reports a press, nothing more -
        // the SDK has no latched level for it, and the button itself is momentary - so
        // without the toggle there is no level for a model to assign.
        listenToggleSwitch(SLOT_VIDEO, "video", DJIRemoteControllerKey.KeyRecordButtonDown);
        listenShutter();
        // The pause button is a press-only button like the takeoff one, and the model
        // wants a latched level out of it (a channel that stays high until the next
        // press), so it toggles SD instead of being high only while it is held.
        listenToggleSwitch(SLOT_PAUSE, "pause", DJIRemoteControllerKey.KeyPauseButtonDown);
        // C1/C2/C3 are one switch each on the Plus family: C1 -> SF, C2 -> SG, C3 -> SH. They
        // used to pick the three positions of SF; SG and SH came from R1/R2, which now pick
        // SI's positions (see joystick.cpp: kPositionKeys), so both took a button here. SF
        // is on the SDK because only the SDK keeps reporting while the UI is closed (key
        // events need a focused window).
        //
        // The Pro family keeps the C1/C2 -> SG/SH pair it always drove, with C3 on SI. The RC
        // Pro's C3 SDK key is deliberately not listened to (see RcModel.c3OnSdk): the only time
        // it ever fired, it did so in the same millisecond as the return button's raw bit, so a
        // slot for it would move whenever the return key is pressed. The remote's own log still
        // reaches it (see onDpadPress).
        listenToggleSwitch(SLOT_C1, "C1", DJIRemoteControllerKey.KeyCustomButton1Down);
        listenToggleSwitch(SLOT_C2, "C2", DJIRemoteControllerKey.KeyCustomButton2Down);
        if (REMOTE.c3OnSdk) {
            listenToggleSwitch(SLOT_C3, "C3", DJIRemoteControllerKey.KeyCustomButton3Down);
        }
        // The round button beside the screen: where the layout has a switch for it and the
        // remote really has the SDK's C4 key, that key is a third reader for it - the raw
        // report's byte 16 bit 0x01 (see RcRawJoystick) and the remote's own log are the other
        // two, and RcButtons merges their copies of one press. On a remote without the button
        // the support check says so and nothing is installed.
        if (SLOT_ROUND >= 0 && has("C4")) {
            listenToggleSwitch(SLOT_ROUND, "round button",
                    DJIRemoteControllerKey.KeyRCCustomButton4Down);
        }
        listenGoHome();
        listenKeysWithoutSwitch();
    }

    /**
     * The SDK keys this app has nothing to drive, listened to for one reason: a press proves
     * the key works, which is the difference between "the button is dead" and "this app has no
     * switch for it". Only the keys the SDK says this remote has are installed (see
     * probeKeySupport), so a remote without them pays nothing.
     *
     * <p>KeyRCSwitchButtonDown is the best example: DJI documents it as the "shoot photo and
     * record button" (the photo/video mode switch), MSDK 5.3.0 and later - it is a real button
     * on some remotes, and there is no EdgeTX switch that means "switch the camera mode".
     */
    private static void listenKeysWithoutSwitch() {
        unmapped("rcSwitch", DJIRemoteControllerKey.KeyRCSwitchButtonDown);
        unmapped("playback", DJIRemoteControllerKey.KeyRCPlaybackButtonDown);
        unmapped("rightWheel", DJIRemoteControllerKey.KeyRCRightWheelButtonDown);
        unmapped("shutterLongPress", DJIRemoteControllerKey.KeyRCShutterButtonLongPress);
        unmapped("transform", DJIRemoteControllerKey.KeyRCTransformationSwitchState);
        if (SLOT_ROUND < 0) {
            unmapped("C4", DJIRemoteControllerKey.KeyRCCustomButton4Down);
        }
    }

    private static <T> void unmapped(final String name, DJIKeyInfo<T> info) {
        if (!has(name)) {
            return;
        }
        try {
            KeyManager.getInstance().listen(KeyTools.createKey(info), OWNER,
                    new CommonCallbacks.KeyListener<T>() {
                        @Override
                        public void onValueChange(T oldValue, T newValue) {
                            if (newValue == null) {
                                return;   // the SDK has taken the key away, for now
                            }
                            if (newValue instanceof Boolean && !Boolean.TRUE.equals(newValue)) {
                                return;   // the release half of a press-only key
                            }
                            announceUnmapped(name);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: could not listen to '" + name + "'", t);
        }
    }

    private static final java.util.Set<String> sUnmappedSeen = new java.util.HashSet<>();

    private static void announceUnmapped(String name) {
        synchronized (DjiMsdkBridge.class) {
            if (sUnmappedSeen.add(name)) {
                Log.i(TAG, "dji: '" + name + "' fired - this remote has that key and the app has "
                        + "no EdgeTX switch for it yet (see RcModel)");
            }
        }
    }

    /**
     * The go-home button, which is two different things depending on the remote.
     *
     * <p>On the RC Plus 2 it is the H button, which is not a switch of its own: pressing it one,
     * two or three times tells the 5-way what to trim, so only the presses are forwarded. Holding
     * it, on the other hand, is that remote's stand-in for a long press of the return button - see
     * {@link #HOLD_FOR_EXIT_MS}.
     *
     * <p>On every other remote this port runs on it is the landing / return-to-home button, and
     * it is a switch: the user drives SA with it, so neither of the two above applies there
     * ({@link RcModel#goHomeLands}). Both are left off there on purpose - the trim count would
     * put the 5-way into a trim mode after two presses of a switch the user toggles freely, and
     * a slightly slow press of it would close the page (or kill a running Lua script) as a
     * "long press of the return key". The long press is not needed on the RC Pro either: its
     * return button really is in the joystick's raw reports (see RcRawJoystick), so holding it
     * gives EdgeTX a genuine long press rather than the 8 ms tap the RC Plus 2's input stack
     * flattens it to.
     */
    private static void listenGoHome() {
        try {
            final DJIKey<Boolean> key =
                    KeyTools.createKey(DJIRemoteControllerKey.KeyGoHomeButtonDown);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Boolean>() {
                        private long pressedAt;

                        @Override
                        public void onValueChange(Boolean oldValue, Boolean newValue) {
                            if (newValue != null && newValue &&
                                (oldValue == null || !oldValue)) {
                                pressedAt = System.currentTimeMillis();
                                Log.i(TAG, "dji: go-home pressed");
                                // Where this button IS the landing switch, two readers report
                                // it that are not copies of each other: the SDK here, and the
                                // remote's own log as ACTION_KEY_GOHOME (see onDpadPress). They
                                // used to be exclusive - the log while it was being read, this
                                // branch otherwise - which left the switch depending on
                                // READ_LOGS, a hand grant a reinstall takes away. Both go into
                                // RcButtons now and it merges them, so the switch moves once
                                // whichever reader saw the press. The trim gesture is not wanted
                                // there: SA is a switch the user toggles freely, and on the RC
                                // Pro the return button carries that gesture instead (see
                                // joystick.cpp: handle_exit_click).
                                if (REMOTE.goHomeLands) {
                                    buttonEdge(SW_SA, "landing", RcButtons.SOURCE_SDK, true);
                                    return;
                                }
                                nativeOnDjiGoHome();
                                return;
                            }

                            if (oldValue == null || !Boolean.TRUE.equals(oldValue) ||
                                pressedAt == 0) {
                                return;
                            }

                            // This button does report its release, and with the real
                            // duration: measured on the remote, a tap is 99-110 ms and a
                            // hold 1160-2053 ms. That makes it the one input a long press
                            // can be built from, because the return button itself arrives as
                            // an 8 ms tap however long it is held.
                            final long held = System.currentTimeMillis() - pressedAt;
                            pressedAt = 0;
                            Log.i(TAG, "dji: go-home released after " + held + " ms");
                            if (REMOTE.goHomeLands) {
                                // The landing button has a job of its own there (see above), but
                                // its readers still have to be told the press is over, or the
                                // next one would be taken for a copy of this one.
                                buttonEdge(SW_SA, "landing", RcButtons.SOURCE_SDK, false);
                                return;
                            }
                            if (held >= HOLD_FOR_EXIT_MS) {
                                try {
                                    nativeOnDjiGoHomeHeld();
                                } catch (Throwable t) {
                                    Log.w(TAG, "dji: could not send the long press", t);
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: go-home listen failed", t);
        }
    }

    /**
     * How long the RC Plus 2's H button has to be held to count as a long press of the return
     * key. Above the 99-110 ms a tap takes and below the 1.1 s a deliberate hold takes, so the
     * two cannot be confused; EdgeTX itself calls anything over ~320 ms a long press.
     *
     * <p>Not used where this button is the landing switch instead - see
     * {@link #listenGoHome}.
     */
    private static final long HOLD_FOR_EXIT_MS = 600;

    /** The H button was held: joystick.cpp turns that into a long press of the return key. */
    static native void nativeOnDjiGoHomeHeld();

    static final int STICK_LEFT_H = 0;
    static final int STICK_LEFT_V = 1;
    static final int STICK_RIGHT_H = 2;
    static final int STICK_RIGHT_V = 3;

    private static final java.util.Set<String> sAnnounced = new java.util.HashSet<>();
    /**
     * The RC's L1/L2/L3/R1/R2/R3 buttons are NOT here: they arrive as Android key
     * codes F1..F6 and are turned into EdgeTX switches in joystick.cpp.
     *
     * The SDK's own boolean button keys are what the rest of the mappings above use
     * (KeyRecordButtonDown, KeyShutterButtonDown, KeyCustomButton1...3Down,
     * KeyPauseButtonDown): they all fire on this remote, unlike KeyRcButtonEventPro,
     * which never does.
     */

    /** One log line per control, the first time it reports. */
    private static void announce(String what) {
        synchronized (DjiMsdkBridge.class) {
            if (sAnnounced.add(what)) {
                Log.i(TAG, "dji: " + what + " active");
            }
        }
    }

    /** The four stick axes, the only source for the RC's sticks. */
    private static void listenSticks() {
        listenStick(STICK_LEFT_H, DJIRemoteControllerKey.KeyStickLeftHorizontal);
        listenStick(STICK_LEFT_V, DJIRemoteControllerKey.KeyStickLeftVertical);
        listenStick(STICK_RIGHT_H, DJIRemoteControllerKey.KeyStickRightHorizontal);
        listenStick(STICK_RIGHT_V, DJIRemoteControllerKey.KeyStickRightVertical);
    }

    private static void listenStick(final int axis, DJIKeyInfo<Integer> info) {
        try {
            final DJIKey<Integer> key = KeyTools.createKey(info);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Integer>() {
                        @Override
                        public void onValueChange(Integer oldValue, Integer newValue) {
                            if (newValue == null) {
                                return;
                            }
                            announce("sticks");
                            dispatchStick(axis, newValue);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: stick listen failed for axis " + axis, t);
        }
    }

    /** Indices in the board's switch table (tx16smk3.json): 0 = SA, 1 = SB, 7 = SH, 9 = SJ. */
    static final int SW_SA = RcModel.SA;
    static final int SW_SB = RcModel.SB;
    static final int SW_SH = RcModel.SH;

    /** SG and SH, which the RC Plus family takes from C2/C3, see listenButtonKeys(). */
    static final int SW_SG = RcModel.SG;

    /** SI - the R1/R2/R3 keys pick its position; the Pro family's C3 drives it. */
    static final int SW_SI = RcModel.SI;

    /**
     * Which switch each of these controls drives on <em>this</em> remote.
     *
     * <p>A button is the same button on every remote; the switch it lands on is not, and neither
     * is the reader that delivers it. All of that - the slots below, whether the SDK's C3 key is
     * listened to, whether the remote's own log may move a switch, and what the go-home button
     * is - belongs to this remote's row in {@link RcModel}. Nothing here is decided per remote
     * any more, so a remote that is not in that table gets a layout it can be compared against
     * instead of a branch that was never written for it.
     */
    private static final RcModel REMOTE = RcModel.CURRENT;
    private static final int SLOT_VIDEO = REMOTE.video;
    private static final int SLOT_PAUSE = REMOTE.pause;
    private static final int SLOT_C1 = REMOTE.c1;
    private static final int SLOT_C2 = REMOTE.c2;
    private static final int SLOT_C3 = REMOTE.c3;

    /** Where the photo button lands. */
    private static final int SLOT_PHOTO = REMOTE.photo;

    /** Where the round button beside the screen lands, -1 on a remote that has none. */
    private static final int SLOT_ROUND = REMOTE.round;

    /**
     * The takeoff button and the flight-mode switch.
     *
     * The takeoff button latches in hardware and has its own red/green LED (red = low,
     * green = high), but the SDK only reports the moment it is pressed: a ~200 ms
     * false -> true -> false pulse on KeyRCAuthLedButtonDown, never the latched level.
     * Mirroring the value therefore never moves the switch, so each press toggles
     * instead - one press high, the next low, which is exactly what the hardware does.
     *
     * KeyRCTransformationSwitchState is deliberately not listened to: the RC Plus 2
     * does not have that switch and the key never fires on it.
     */
    private static void listenSwitches() {
        listenToggleSwitch(SW_SA, "takeoff", DJIRemoteControllerKey.KeyRCAuthLedButtonDown);
        listenEnumSwitch(SW_SB, "flightMode", DJIRemoteControllerKey.KeyFlightModeSwitchState);
        // The same switch again, under the name the remote publishes more willingly: measured on
        // the RC Pro, KeySoftSwitchMode delivered nothing (not even an initial snapshot), so the
        // physical flight-mode key above is its only measured source. Keep this listener for
        // other remotes that publish the soft-switch key; repeated positions are de-duplicated.
        listenEnumSwitch(SW_SB, "softSwitchMode", DJIRemoteControllerKey.KeySoftSwitchMode);
    }

    /**
     * The two states a press-only button toggles between, as EdgeTX switch positions.
     * The takeoff button powers up low with a red LED, and that low state is the
     * switch's down position, so the arrow points up whenever the LED is green.
     */
    private static final int SWITCH_LOW = 1;
    private static final int SWITCH_HIGH = -1;

    /**
     * Latched state of each button that only reports presses, as EdgeTX sees it: one
     * SDK press pulse maps to exactly one toggle, so this has to track it here.
     */
    static final int SWITCH_COUNT = 10;   // SA..SJ
    private static final boolean[] sToggleHigh = initialToggleStates();

    private static boolean[] initialToggleStates() {
        final boolean[] states = new boolean[SWITCH_COUNT];
        // The firmware resets every switch to "up" on boot, and these five are software
        // latches: starting high makes their first press move them visibly. SA, SC and SD
        // stay low because their buttons power up in the low position (the takeoff button's
        // red LED says so).
        states[SWITCH_E] = true;   // SE: photo (Plus family), round button (RC Pro)
        states[SWITCH_F] = true;   // SF: C1 on the RC Plus 2
        states[SW_SG] = true;
        states[SW_SH] = true;
        states[SW_SI] = true;
        return states;
    }

    /**
     * One press or one release of a switch-driving button, from whichever reader saw it: the
     * press moves the switch, the release only closes it, and a copy of a press that another
     * reader has already counted moves nothing.
     *
     * <p>Which readers see which buttons, how far apart their copies of one press arrive, and
     * why they are merged rather than outvoted is {@link RcButtons}' business - this is only the
     * last step of it, shared by every reader so no button can be left depending on one of them.
     */
    private static void buttonEdge(int index, String label, int source, boolean down) {
        final int what = RcButtons.edge(index, source, down);
        if (what == RcButtons.PRESS) {
            toggleSwitch(index, label, source);
        } else if (what == RcButtons.MERGED) {
            logMerged(label, source, index);
        }
    }

    /**
     * Two readers saw the same physical press. Worth a line every time: it is what says both are
     * alive for that button, which is the question behind every "this switch only works
     * sometimes" report - and it is also what proves one press is still only one movement.
     */
    private static void logMerged(String label, int source, int index) {
        Log.i(TAG, "dji: " + label + " press came from " + RcButtons.sourceName(source)
                + " as well, merged with the " + RcButtons.sourceName(RcButtons.pressSource(index))
                + " one");
    }

    /**
     * Position of the photo switch (SE) from the last press, which is also where a
     * restart picks it up again (see {@link #restoreLatchedSwitches}).
     */
    private static int sShutterPosition = 0;

    /** Where the latching switch positions are kept between runs. */
    private static final String PREFS = "link";
    private static final String PREF_TAKEOFF = "switchSA";
    private static final String PREF_VIDEO = "switchSC";
    private static final String PREF_PAUSE = "switchSD";
    private static final String PREF_PHOTO = "switchSE";
    private static final String PREF_SG = "switchSG";
    private static final String PREF_SH = "switchSH";
    private static final String PREF_SF = "switchSF";

    private static SharedPreferences sPrefs;

    /**
     * Picks up the positions the latching switches had when the app last ran.
     *
     * <p>The takeoff, video and pause buttons latch in the remote's own hardware, and the SDK
     * only ever reports a press, never the latched level - so those positions are remembered
     * here and handed back on the next start, remote reboot included: the model sees the
     * settings it was left with, and the next press puts the button and the switch back in
     * step.
     *
     * <p>SF, SG and SH need to be restored here because the SDK registration pushes their
     * state at startup, and C1/C2/C3 drive them from this side. The photo slot (SE on the RC
     * Plus 2, SF on the Pro family) is pushed at startup the same way. The remaining switches are
     * restored by joystick.cpp alone: nothing here sends them without a press, and
     * {@link #toggleSwitch} reads the real position before it flips one.
     *
     * <p>This runs from Application.onCreate, early enough to beat the SDK's registration,
     * whereas the native side only learns its directory once RcLinkService starts the link.
     */
    private static void restoreLatchedSwitches() {
        final SharedPreferences prefs = sPrefs;
        if (prefs == null) {
            Log.w(TAG, "dji: no preferences, the switches start from their power-on state");
            return;
        }

        final int takeoff = prefs.getInt(PREF_TAKEOFF, 0);
        if (takeoff != 0) sToggleHigh[SW_SA] = (takeoff == SWITCH_HIGH);

        final int video = prefs.getInt(PREF_VIDEO, 0);
        if (video != 0) sToggleHigh[SLOT_VIDEO] = (video == SWITCH_HIGH);

        final int pause = prefs.getInt(PREF_PAUSE, 0);
        if (pause != 0) sToggleHigh[SLOT_PAUSE] = (pause == SWITCH_HIGH);

        final int photo = prefs.getInt(PREF_PHOTO, 0);
        if (photo != 0) sShutterPosition = photo;

        final int sg = prefs.getInt(PREF_SG, 0);
        if (sg != 0) sToggleHigh[SW_SG] = (sg == SWITCH_HIGH);

        final int sh = prefs.getInt(PREF_SH, 0);
        if (sh != 0) sToggleHigh[SW_SH] = (sh == SWITCH_HIGH);

        final int sf = prefs.getInt(PREF_SF, 0);
        if (sf != 0) sToggleHigh[SWITCH_F] = (sf == SWITCH_HIGH);

        Log.i(TAG, "dji: switches carried over - takeoff "
                + (sToggleHigh[SW_SA] ? "high" : "low") + ", video "
                + (sToggleHigh[SLOT_VIDEO] ? "high" : "low") + ", pause "
                + (sToggleHigh[SLOT_PAUSE] ? "high" : "low") + ", photo " + sShutterPosition
                + ", SF " + (sToggleHigh[SWITCH_F] ? "high" : "low")
                + ", SG " + (sToggleHigh[SW_SG] ? "high" : "low")
                + ", SH " + (sToggleHigh[SW_SH] ? "high" : "low"));
    }

    /** Remembers one of the latching positions for the next run. */
    private static void persistSwitch(String key, int position) {
        final SharedPreferences prefs = sPrefs;
        if (prefs == null) return;
        prefs.edit().putInt(key, position).apply();
    }

    /**
     * A button reporting only its press, driving a two-position EdgeTX switch: the
     * takeoff button (SA), the video button (SC) and the pause button (SD) all work this way.
     *
     * The first callback is the initial snapshot rather than a press, and the SDK also
     * reports the release half of the pulse, so only a press is acted on.
     *
     * <p>Both positions are remembered across a restart (see
     * {@link #restoreLatchedSwitches}), with one caveat that cannot be designed away: a press
     * <em>toggles</em> the remote's own latch and this level together, so the two only stay in
     * step while they started in step. A press made while the app was dead - the remote's
     * latch moving on its own - leaves them permanently inverted, and nothing here can tell:
     * the SDK never reports the latched level (the initial snapshot logged below is what would
     * settle that). Starting from "low" instead is wrong in the far more common case where the
     * latch simply stayed where it was.
     *
     * <p>Whether the remote's latch can be read at all is still open, and the snapshot log
     * line below is what would answer it: if the SDK reports the latched level in the first
     * callback rather than a plain {@code false}, that value is the right starting point and
     * the toggling stops being a guess.
     */
    private static void listenToggleSwitch(final int index, final String label,
                                           DJIKeyInfo<Boolean> info) {
        try {
            final DJIKey<Boolean> key = KeyTools.createKey(info);
            final CommonCallbacks.KeyListener<Boolean> listener =
                    new CommonCallbacks.KeyListener<Boolean>() {
                        @Override
                        public void onValueChange(Boolean oldValue, Boolean newValue) {
                            noteKeyValue(label, newValue);
                            if (oldValue == null) {
                                // A snapshot, not a press: see the note above - this is the
                                // measurement, not a decision.
                                Log.i(TAG, "dji: " + label + " initial snapshot " + newValue);
                                return;
                            }
                            // Which reader gets to count this press is RcButtons' business: this
                            // reader may be the raw report's copy of the same press, or it may
                            // be the only one that saw it at all - and that is the point.
                            buttonEdge(index, label, RcButtons.SOURCE_SDK,
                                    Boolean.TRUE.equals(newValue));
                        }
                    };
            KeyManager.getInstance().listen(key, OWNER, listener);
            dispatchToggle(index, sToggleHigh[index]);
        } catch (Throwable t) {
            Log.w(TAG, "dji: " + label + " listen failed", t);
        }
    }

    /**
     * One press of a switch-driving button: the latched level flips and goes out. Called by
     * {@link #buttonEdge} for every reader, and the source only ever appears in the log line -
     * which reader it was is exactly what a "this switch works only sometimes" report needs.
     */
    private static void toggleSwitch(final int index, final String label, final int source) {
        final boolean high;
        synchronized (DjiMsdkBridge.class) {
            // Flip from where the switch really is. SI is moved by the R1/R2/R3 keys in
            // joystick.cpp, and a restart restores saved positions, so the copy kept here can
            // be stale; 0 means joystick.cpp has not seen the switch yet.
            int current = 0;
            try {
                current = nativeSwitchState(index);
            } catch (Throwable t) {
                Log.w(TAG, "dji: could not read switch " + index + " state", t);
            }
            high = current != 0 ? current > 0 : !sToggleHigh[index];
            sToggleHigh[index] = high;
        }
        Log.i(TAG, "dji: switch " + index + " (" + label + ") -> "
                + (high ? "high" : "low") + " [from " + RcButtons.sourceName(source) + "]");
        dispatchToggle(index, high);
    }

    private static void dispatchToggle(int index, boolean high) {
        if (index == SW_SA) {
            persistSwitch(PREF_TAKEOFF, high ? SWITCH_HIGH : SWITCH_LOW);
        } else if (index == SLOT_VIDEO) {
            persistSwitch(PREF_VIDEO, high ? SWITCH_HIGH : SWITCH_LOW);
        } else if (index == SLOT_PAUSE) {
            persistSwitch(PREF_PAUSE, high ? SWITCH_HIGH : SWITCH_LOW);
        } else if (index == SWITCH_F) {
            persistSwitch(PREF_SF, high ? SWITCH_HIGH : SWITCH_LOW);
        } else if (index == SW_SG) {
            persistSwitch(PREF_SG, high ? SWITCH_HIGH : SWITCH_LOW);
        } else if (index == SW_SH) {
            persistSwitch(PREF_SH, high ? SWITCH_HIGH : SWITCH_LOW);
        }

        try {
            nativeOnDjiSwitch(index, high ? SWITCH_HIGH : SWITCH_LOW);
        } catch (Throwable t) {
            Log.w(TAG, "dji: switch " + index + " dispatch failed", t);
        }
    }

    /**
     * The RC's own battery as the SDK sees it. Only a cross-check: it carries a percentage
     * but no charging state, so RcBattery (Android's BatteryManager) is the source of
     * truth. Logging both makes it obvious if they ever disagree.
     */
    private static void listenBattery() {
        try {
            final DJIKey<BatteryInfo> key =
                    KeyTools.createKey(DJIRemoteControllerKey.KeyBatteryInfo);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<BatteryInfo>() {
                        @Override
                        public void onValueChange(BatteryInfo oldValue, BatteryInfo newValue) {
                            if (newValue == null) {
                                return;
                            }
                            Log.i(TAG, "dji: rc battery " + newValue.getBatteryPercent() + "%, "
                                    + "power=" + newValue.getBatteryPower()
                                    + ", enabled=" + newValue.getEnabled());
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: battery listen failed", t);
        }
    }

    // ---------------------------------------------------- EdgeTX's haptics -> the RC's motor ---
    //
    // EdgeTX decides when to buzz (its own Haptic setting and the model's alarms) and the
    // simulator counts each event; the remote's motor is what can actually be felt. On an RC Pro
    // the motor is Android's to drive: the SDK's KeyRcShakeMotor has no handler there at all
    // (measured: REQUEST_HANDLER_NOT_FOUND, REMOTECONTROLLER.RcShakeMotor:-1), while the same
    // motor answers the device's own vibrator (dumpsys vibrator shows it being driven), so the
    // vibration is asked for directly - which needs no adb grant, only VIBRATE. The SDK call is
    // kept as the fallback for a remote whose motor only answers the SDK.
    //
    // Only events turn into a buzz - a continuous vibration is not something a haptic queue can
    // follow - and they are rate-limited, because a burst of alarms would otherwise be a queue of
    // buzzes the motor cannot keep up with.

    /**
     * How many haptic events the firmware has raised so far, counted by radio/src/haptic.cpp.
     */
    static native int nativeHapticEvents();

    private static final long HAPTIC_POLL_MS = 50;
    /** Long enough to be felt, short enough that a burst of them stays separate. */
    private static final long HAPTIC_BUZZ_MS = 60;
    /** Leave the motor time to finish before the next one. */
    private static final long HAPTIC_MIN_GAP_MS = 150;

    /** The remote's own motor, as the device sees it (null on anything without one). */
    private static Vibrator sVibrator;

    private static long sHapticSeen;
    private static long sLastShakeAt;
    private static long sShakes;

    /**
     * Watches the firmware's haptic counter. A poll rather than a callback because that is what
     * the counter is for (the WASM build reads it the same way, every 50 ms), and it has to keep
     * running after the UI is closed, which the main looper does while the link service holds the
     * process.
     */
    private static void startHapticPolling() {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollHaptics();
                handler.postDelayed(this, HAPTIC_POLL_MS);
            }
        }, HAPTIC_POLL_MS);
    }

    private static void pollHaptics() {
        try {
            final long events = nativeHapticEvents();
            if (events == sHapticSeen) {
                return;
            }
            if (events < sHapticSeen) {
                sHapticSeen = events;   // the firmware was restarted, not another event
                return;
            }
            sHapticSeen = events;

            final long now = System.currentTimeMillis();
            if (now - sLastShakeAt < HAPTIC_MIN_GAP_MS) {
                return;   // still shaking: one event is enough for a burst
            }
            sLastShakeAt = now;
            shakeRemote();
        } catch (Throwable t) {
            Log.w(TAG, "dji: haptic poll failed", t);
        }
    }

    /** One buzz of the remote's motor, through the device's vibrator where there is one. */
    private static void shakeRemote() {
        if (sVibrator != null && sVibrator.hasVibrator()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    sVibrator.vibrate(VibrationEffect.createOneShot(HAPTIC_BUZZ_MS,
                            VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    sVibrator.vibrate(HAPTIC_BUZZ_MS);
                }
                sShakes++;
                if (sShakes <= 3 || sShakes % 20 == 0) {
                    Log.i(TAG, "dji: haptic -> the remote buzzes (" + sShakes + ")");
                }
                return;
            } catch (Throwable t) {
                Log.w(TAG, "dji: could not buzz the remote", t);
            }
        }

        try {
            final DJIKey.ActionKey<EmptyMsg, EmptyMsg> key =
                    KeyTools.createKey(DJIRemoteControllerKey.KeyRcShakeMotor);
            KeyManager.getInstance().performAction(key, new EmptyMsg(),
                    new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                        @Override
                        public void onSuccess(EmptyMsg result) {
                            sShakes++;
                            if (sShakes <= 3 || sShakes % 20 == 0) {
                                Log.i(TAG, "dji: haptic -> remote shake " + sShakes);
                            }
                        }

                        @Override
                        public void onFailure(IDJIError error) {
                            Log.w(TAG, "dji: could not shake the remote: " + error);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: could not shake the remote", t);
        }
    }

    /**
     * The aircraft's real arm state, as opposed to the button's latch.
     *
     * KeyAreMotorsOn is an input rather than a way to light anything - listening to it
     * cannot colour an LED by itself - but it is the honest source of truth for what the
     * remote is really doing, and it is what the arm indicator should be driven from.
     * It only ever reports while an aircraft is connected.
     */
    private static void listenArmState() {
        try {
            final DJIKey<Boolean> key =
                    KeyTools.createKey(DJIFlightControllerKey.KeyAreMotorsOn);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Boolean>() {
                        @Override
                        public void onValueChange(Boolean oldValue, Boolean newValue) {
                            if (newValue == null) {
                                return;
                            }
                            Log.i(TAG, "dji: motors " + oldValue + " -> " + newValue);
                            announce("motor state");
                            // The one lamp this app can reach is not the one on the arm
                            // button, but it is a real lamp, so it follows the arm state.
                            RcLed.setColor(newValue ? RcLed.GREEN : RcLed.RED);
                            // Deliberately not a switch position: SJ used to carry this, but on
                            // the RC Plus 2 it is now the 3-position switch L1/L2/L3 picks
                            // (see joystick.cpp, kPositionKeys), and two writers would fight
                            // over it. Nothing else asked for the arm state as a switch.
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: arm state listen failed", t);
        }
    }

    /**
     * One switch position, from either source: the SDK's listener, or the poll that reads the
     * same key while the listener is quiet (see {@link #pollLoop}). Only a change is logged and
     * dispatched, so the SDK re-delivering a value it already sent costs nothing - and a value
     * that has not changed cannot move the switch twice.
     */
    private static void applySwitchPosition(final int index, Object value) {
        final Integer state = switchPosition(value);
        if (state == null) {
            return;               // UNKNOWN, or a key that went silent
        }
        applyPosition(index, state);
    }

    /** One position, already decoded, from any of the keys that carry this switch. */
    private static void applyPosition(final int index, final int state) {
        if (state == sLastSwitchPosition[index]) {
            return;               // nothing new: a second key reporting the same position
        }
        sLastSwitchPosition[index] = state;
        Log.i(TAG, "dji: switch " + index + " -> " + (state > 0 ? "down" : state < 0 ? "up" : "mid"));
        try {
            nativeOnDjiSwitch(index, state);
        } catch (Throwable t) {
            Log.w(TAG, "dji: switch dispatch failed", t);
        }
    }

    /** Last position applied per switch, so a repeat is not a second move. */
    private static final int[] sLastSwitchPosition = new int[SWITCH_COUNT];

    static {
        for (int i = 0; i < sLastSwitchPosition.length; i++) {
            sLastSwitchPosition[i] = Integer.MIN_VALUE;   // nothing applied yet
        }
    }

    private static <T> void listenEnumSwitch(final int index, final String name,
                                             DJIKeyInfo<T> info) {
        try {
            final DJIKey<T> key = KeyTools.createKey(info);
            final CommonCallbacks.KeyListener<T> listener =
                    new CommonCallbacks.KeyListener<T>() {
                        @Override
                        public void onValueChange(T oldValue, T newValue) {
                            noteKeyValue(name, newValue);
                            applySwitchPosition(index, newValue);
                        }
                    };
            KeyManager.getInstance().listen(key, OWNER, listener);
        } catch (Throwable t) {
            Log.w(TAG, "dji: switch listen failed for " + index, t);
        }
    }

    // ---- what the SDK's silences look like from here ---------------------------
    //
    // The SDK sets every key of this remote to null in waves and re-delivers them when it gets
    // round to it. Measured on the RC Pro: waves of 20-60 s in which controls can be moved with
    // nothing arriving at all, which is what "sometimes it does nothing" is.
    //
    // The DJI app (dji.go.v5) is what was measured to cause this: it subscribes to the same
    // remote through the same SDK, and while it is there the pushes arrive late or not at all.
    // Measured on the RC Pro, one app instance, one switch:
    //   * with dji.go.v5 force-stopped: four positions in 13 s, every value distinct, and not a
    //     single "went silent" line in the log;
    //   * with it started again: the flight-mode key, C1, C2, video and pause all went null
    //     within seconds.
    // It cannot simply be kept away, though: on this remote it comes back on its own (seen twice,
    // the second time a force-stop was needed again 20 minutes later - pidof empty at one check
    // and the app at pid 7488 at the next), and a wave every 10 to 30 s was observed on
    // 2026-09-25 18:12-18:16 at a moment when a pidof check happened to find it stopped. So treat
    // that app as the usual cause rather than as something this code can force away, and treat a
    // wave as possible either way.
    //
    // What matters for a model is which control has a second reader: the flight-mode switch has
    // none (the remote's own log has no name for a switch, the raw reports do not carry it). The
    // landing, pause and C1..C3 buttons are wave-proof through the remote's own button log
    // (RcDpadLog), which is exactly why READ_LOGS is worth having; the raw USB report covers the
    // rest. A remote reboot used to look like the cure because the DJI app has not started yet
    // after one.
    //
    // Nothing an app does shortens a wave; all of these were tried against one:
    //   * cancelling the subscription and re-issuing it every 10 s, and again every 4 s once the
    //     above was known: not one extra value arrives, and a fresh listen does not even
    //     re-deliver the cached value;
    //   * asking for the value directly (the asynchronous getValue, a real request, unlike the
    //     synchronous one the sticks poll, which only reads the cache): the SDK answers
    //     REQUEST_HANDLER_NOT_FOUND for REMOTECONTROLLER.FlightModeSwitchState, so there is no
    //     handler to ask.
    // The remote re-publishes its whole key set when it has anything to report - measured, any
    // button press brought all of it back within 200 ms, the flight-mode switch's current
    // position included, while moving the switch alone does not, because a switch is not an
    // event and the SDK has already dropped its cache.
    //
    // What is left is to notice it happen: the position arrives when the channel comes back, so
    // the switch catches up by itself. This records how long each key was quiet for, which is
    // what makes the behaviour visible in a log - and the line for the switch says what the
    // remedy is, since that is not something this app can do.

    private static final java.util.Map<String, Long> SILENT_SINCE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** One value from a watched key: null means the SDK has taken it away, for now. */
    private static void noteKeyValue(String name, Object value) {
        final Long since = SILENT_SINCE.get(name);
        if (value == null) {
            if (since == null) {
                SILENT_SINCE.put(name, System.currentTimeMillis());
                if (sProbe) Log.i(TAG, "dji: " + name + " went silent");
                if ("flightMode".equals(name)) {
                    // Once per wave, and only for the switch a model is likely to use: it is the
                    // one control with no second reader - the remote's own button log has no
                    // name for a switch, and the raw reports do not carry it - so a wave is felt
                    // here first and nowhere else. Nothing an app does shortens one; see the
                    // silence notes above.
                    Log.i(TAG, "dji: the flight-mode switch has stopped being reported - the "
                            + "remote's own data channel is having one of its quiet spells");
                }
            }
            return;
        }
        if (since != null) {
            SILENT_SINCE.remove(name);
            if (sProbe) {
                Log.i(TAG, "dji: " + name + " is back after "
                        + (System.currentTimeMillis() - since) + " ms");
            }
        }
    }

    /**
     * Which enumeration each physical position of the three-position switch reports, top
     * first, and the EdgeTX position each of those drives.
     *
     * <p>The numbering has nothing to do with the physical order, and the remotes do not even
     * agree with each other: measured by moving the switch and reading the log, the RC Plus 2
     * reports SWITCH_TWO at the top and SWITCH_ONE in the middle, the RC Pro the other way
     * round. One table for both leaves the switch rotated by a whole position on one of them -
     * the middle position then lands on an end and the switch appears dead. Which way round a
     * remote reports it is part of its row in {@link RcModel} ({@code proFlightMode}); the two
     * remotes that have not been measured inherit the order of the family they belong to.
     *
     * <p>The sign is EdgeTX's, not the remote's: the switch is labelled high/middle/low and
     * its high end is EdgeTX's down position, so the top is +1 rather than -1.
     */
    private static final RCFlightModeSwitch[] FLIGHT_MODE_TOP_FIRST = flightModeTopFirst();
    private static final int[] POSITION_TOP_FIRST = {1, 0, -1};

    private static RCFlightModeSwitch[] flightModeTopFirst() {
        if (REMOTE.proFlightMode) {
            return new RCFlightModeSwitch[]{
                    RCFlightModeSwitch.SWITCH_ONE, RCFlightModeSwitch.SWITCH_TWO,
                    RCFlightModeSwitch.SWITCH_THREE};
        }
        return new RCFlightModeSwitch[]{
                RCFlightModeSwitch.SWITCH_TWO, RCFlightModeSwitch.SWITCH_ONE,
                RCFlightModeSwitch.SWITCH_THREE};
    }

    /** Maps the SDK's switch enumerations onto EdgeTX's -1 / 0 / 1 positions. */
    private static Integer switchPosition(Object value) {
        if (value instanceof RCFlightModeSwitch) {
            final RCFlightModeSwitch position = (RCFlightModeSwitch) value;
            for (int index = 0; index < FLIGHT_MODE_TOP_FIRST.length; index++) {
                if (FLIGHT_MODE_TOP_FIRST[index] == position) {
                    return POSITION_TOP_FIRST[index];
                }
            }
            return null;              // UNKNOWN
        }
        if (value instanceof RcSoftSwitchMode) {
            // The same physical switch as RCFlightModeSwitch above, published under a second key
            // - and this is the one that answers while the other is silent (measured on the RC
            // Pro, where KeySoftSwitchMode was the only key of the whole set that kept
            // reporting through a wave). The names are the remote's own, top to bottom:
            //     TRIPOD  = C   -> EdgeTX down, the same way round as the other key
            //     POSITION = N  -> middle
            //     SPORT   = S   -> EdgeTX up
            // Both keys feed SB and applySwitchPosition() drops a position that is already set,
            // so whichever of the two arrives first is the one that counts.
            switch ((RcSoftSwitchMode) value) {
                case TRIPOD: return 1;
                case POSITION: return 0;
                case SPORT: return -1;
                default: return null;              // UNKNOWN
            }
        }
        if (value instanceof RCTransformationSwitchState) {
            switch ((RCTransformationSwitchState) value) {
                case RETRACT: return -1;
                case DEPLOY: return 1;
                default: return null;              // UNKNOWN
            }
        }
        return null;
    }

    /** The RC's two dials, as rotary encoder sources (see nativeOnDjiDial). */
    static final int DIAL_LEFT = 0;
    static final int DIAL_RIGHT = 1;

    // --------------------------------------------------------------- polling --
    //
    // The SDK hands the stick positions out on change, and what was measured on this RC
    // is the whole story of how direct the sticks can feel:
    //
    //   raw kernel stick stream   70 Hz (14 ms) - but /dev/input/event4 is root:input, no
    //                             hidraw exists, and the framework has no joystick mapper
    //                             for the device, so no app ever sees those events
    //   one stick axis via the SDK   8-14 new values a second, gaps mostly 130-280 ms
    //   app -> firmware -> mixer -> RF   ~10 ms (CRSF frames at 400 kbaud, 200 a second)
    //
    // The SDK is what is left, and polling it harder makes it *worse*: with this loop at
    // 5 ms (190 passes, ~1150 getValue() calls a second) the axes changed only 4-10 times
    // a second, with gaps up to 600 ms; at 200 ms they change 8-14 times a second, with
    // gaps mostly under 300 ms. KeyManager.getValue() reads the cache the pushes come from
    // anyway, so all that traffic bought no freshness and cost the RC's own service the
    // time it needed. The listeners above are the primary source; this loop is only the
    // safety net for a push that never arrives.
    private static final int POLL_INTERVAL_MS = 200;

    /**
     * How often the SDK really hands over a new stick position, and how long it goes quiet
     * in between.
     *
     * <p>The value only changes while the stick moves, so this line says exactly what the
     * sticks cost in latency - and it counts every value from both sources, so it stays
     * honest whatever the poll interval is. Logged once a second, and only while a stick
     * is being moved: an idle radio stays quiet.
     */
    private static final long STICK_RATE_LOG_MS = 1000;
    private static final int STICK_AXES = 4;

    private static final long[] sStickChanges = new long[STICK_AXES];
    private static final long[] sStickLastChangeMs = new long[STICK_AXES];
    private static final long[] sStickMaxGapMs = new long[STICK_AXES];
    private static long sPolls;
    private static long sRateWindowStartMs;

    private static void startPolling() {
        final Thread thread = new Thread(DjiMsdkBridge::pollLoop, "dji-poll");
        thread.setDaemon(true);
        thread.start();
    }

    private static void pollLoop() {
        final KeyManager manager = KeyManager.getInstance();

        // The four sticks, then the two dials: both are absolute values that the app
        // turns into analog inputs (the sticks are the axes, the dials are P1/P2).
        final DJIKey<Integer>[] keys = new DJIKey[] {
                KeyTools.createKey(DJIRemoteControllerKey.KeyStickLeftHorizontal),
                KeyTools.createKey(DJIRemoteControllerKey.KeyStickLeftVertical),
                KeyTools.createKey(DJIRemoteControllerKey.KeyStickRightHorizontal),
                KeyTools.createKey(DJIRemoteControllerKey.KeyStickRightVertical),
                KeyTools.createKey(DJIRemoteControllerKey.KeyLeftDial),
                KeyTools.createKey(DJIRemoteControllerKey.KeyRightDial),
        };
        final int[] last = new int[keys.length];
        final boolean[] seen = new boolean[keys.length];

        // The remote's three-position switch is read here as well, and for a reason the sticks do
        // not have: the SDK sets every key of this remote to null in waves ("<key> -> null" in
        // the log) and re-delivers them whenever it feels like it - measured on the RC Pro, one
        // wave lasted 44 s, and a switch moved in that time never arrived at all. That is what
        // "the switches only work sometimes" is. A level read here goes through the same entry
        // point a published one does (applySwitchPosition), which is what keeps one movement
        // from being applied twice.
        //
        // The buttons are not polled: a press is an event, so the cache holds nothing to read
        // between presses, and during a wave it holds null - the log reader (RcDpadLog) is what
        // covers the buttons the SDK drops, and RcButtons merges the two where both see a press.
        final DJIKey<RCFlightModeSwitch> flightModeKey =
                createKeyOf(DJIRemoteControllerKey.KeyFlightModeSwitchState);
        RCFlightModeSwitch lastFlightMode = null;

        // The remote's own GPS used to be listened for here as KeyRcGPSInfo, which reports as
        // supported on the RC Pro and then never delivers anything (measured with the probe, like
        // KeySoftSwitchMode and KeyFlightModeString). It comes from Android's own location stack
        // instead, which is already tracking the same chip - see RcGps.

        sRateWindowStartMs = System.currentTimeMillis();

        while (true) {
            sPolls++;

            // The four sticks, then the two dials: both are absolute values that the app
            // turns into analog inputs (the sticks are the axes, the dials are P1/P2).
            for (int i = 0; i < keys.length; i++) {
                final Integer value;
                try {
                    value = manager.getValue(keys[i]);
                } catch (Throwable t) {
                    continue;
                }
                if (value == null) {
                    continue;
                }
                final int raw = value;
                if (seen[i] && raw == last[i]) {
                    continue;
                }
                seen[i] = true;
                last[i] = raw;
                if (i < STICK_AXES) {
                    dispatchStick(i, raw);
                } else {
                    dispatchDial(i - STICK_AXES, raw);
                }
            }

            logStickRate();

            // The switch and the buttons: same idea, but a level rather than an axis, so it
            // goes straight into the code the listeners use.
            try {
                final RCFlightModeSwitch mode = manager.getValue(flightModeKey);
                if (mode != null && !mode.equals(lastFlightMode)) {
                    lastFlightMode = mode;
                    if (sProbe) Log.i(TAG, "dji: poll flightMode = " + mode);
                    applySwitchPosition(SW_SB, mode);
                }
            } catch (Throwable t) {
                // A key with no handler yet: the listener will say so, once.
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** The poll works on keys whose value type it does not care about. */
    @SuppressWarnings("unchecked")
    private static <T> DJIKey<T> createKeyOf(DJIKeyInfo<T> info) {
        return KeyTools.createKey(info);
    }

    /**
     * One new stick position, from either source: the listener that fires when the SDK
     * publishes it, or the safety-net poll. Every stick value the app ever sees goes
     * through here, which is what makes the rate line count all of them.
     *
     * <p>While the raw USB reader is feeding the axes (see {@link RcRawJoystick}) this
     * copy is not pushed: it reports the same numbers but up to 90 ms older, so putting it
     * on top of a fresh one would only add jitter. It is still counted here, which is what
     * keeps the rate line honest about what the SDK alone is doing.
     */
    private static void dispatchStick(int axis, int raw) {
        noteStickChange(axis);
        markStickSeen(axis);
        RcRawJoystick.noteSdkStick(axis, raw);
        if (RcRawJoystick.ownsInputs()) {
            return;
        }
        try {
            nativeOnDjiStick(axis, raw);
        } catch (Throwable t) {
            Log.w(TAG, "dji: stick dispatch failed", t);
        }
    }

    /**
     * One dial value, from either source. The dials are reported the same way the sticks
     * are, so the raw USB reader takes them over on the same terms. The scroll wheel is
     * NOT part of this: it is not in the joystick's reports at all.
     */
    private static void dispatchDial(int dial, int value) {
        RcRawJoystick.noteSdkDial(dial, value);
        if (RcRawJoystick.ownsInputs()) {
            return;
        }
        try {
            nativeOnDjiDial(dial, value);
        } catch (Throwable t) {
            Log.w(TAG, "dji: dial dispatch failed", t);
        }
    }

    /** Records one new stick value, for the rate line below. */
    private static void noteStickChange(int axis) {
        final long now = System.currentTimeMillis();
        sStickChanges[axis]++;
        if (sStickLastChangeMs[axis] != 0) {
            final long gap = now - sStickLastChangeMs[axis];
            // A gap longer than a window is the stick having been at rest, not a slow
            // report, so only gaps inside one burst are counted.
            if (gap <= STICK_RATE_LOG_MS && gap > sStickMaxGapMs[axis]) {
                sStickMaxGapMs[axis] = gap;
            }
        }
        sStickLastChangeMs[axis] = now;
    }

    /**
     * One line a second while the sticks are being moved: how often the SDK produced a
     * new value per axis, the longest gap inside a burst, and the achieved poll rate.
     * Nothing has moved means nothing is printed, so an idle radio stays quiet.
     */
    private static void logStickRate() {
        final long now = System.currentTimeMillis();
        final long window = now - sRateWindowStartMs;
        if (window < STICK_RATE_LOG_MS) {
            return;
        }
        sRateWindowStartMs = now;

        final StringBuilder text = new StringBuilder();
        for (int axis = 0; axis < STICK_AXES; axis++) {
            if (sStickChanges[axis] > 0) {
                if (text.length() > 0) {
                    text.append(", ");
                }
                text.append("axis ").append(axis).append(' ')
                        .append(sStickChanges[axis] * 1000 / window).append("/s")
                        .append(" (max gap ").append(sStickMaxGapMs[axis]).append(" ms)");
            }
            sStickChanges[axis] = 0;
            sStickMaxGapMs[axis] = 0;
        }

        if (text.length() > 0) {
            Log.i(TAG, "dji: stick rate " + text + " | "
                    + (sPolls * 1000 / window) + " polls/s");
            // Side by side with what the raw USB reader has for the same axes: the two are
            // the same numbers from the same hardware, so this is what catches a sign
            // that does not match (see RcRawJoystick.AXIS_SIGN).
            RcRawJoystick.logComparison();
        }
        sPolls = 0;
    }

    /**
     * The two dials and the scroll wheel. The dials are absolute (-660..660, same
     * as the sticks) and drive EdgeTX's rotary encoder; the wheel only reports
     * relative steps, which the native side accumulates into the SL2 slider.
     */
    private static void listenDials() {
        listenDial(DIAL_LEFT, DJIRemoteControllerKey.KeyLeftDial);
        listenDial(DIAL_RIGHT, DJIRemoteControllerKey.KeyRightDial);
        listenScrollWheel();
    }

    private static void listenDial(final int dial, DJIKeyInfo<Integer> info) {
        try {
            final DJIKey<Integer> key = KeyTools.createKey(info);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Integer>() {
                        @Override
                        public void onValueChange(Integer oldValue, Integer newValue) {
                            if (newValue == null) {
                                return;
                            }
                            announce("dial " + dial);
                            dispatchDial(dial, newValue);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: dial listen failed for dial " + dial, t);
        }
    }

    private static void listenScrollWheel() {
        try {
            final DJIKey<Integer> key = KeyTools.createKey(DJIRemoteControllerKey.KeyScrollWheel);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Integer>() {
                        @Override
                        public void onValueChange(Integer oldValue, Integer newValue) {
                            if (newValue == null || newValue == 0) {
                                return;
                            }
                            announce("scroll wheel");
                            try {
                                nativeOnDjiScrollWheel(newValue);
                            } catch (Throwable t) {
                                Log.w(TAG, "dji: scroll wheel dispatch failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: scroll wheel listen failed", t);
        }
    }

    private static final boolean[] sFiveDimPrev = new boolean[5];

    private static void dispatchFiveDimension(FiveDimensionPressedStatus status) {
        if (status == null) {
            return;
        }
        // The raw reports carry the same five directions (byte 17), see RcRawJoystick: while
        // they are feeding the firmware, this copy would be a second press of every one.
        if (RcRawJoystick.ownsInputs()) {
            return;
        }
        edge(0, status.getUpwards(), BTN_UP);
        edge(1, status.getDownwards(), BTN_DOWN);
        edge(2, status.getLeftwards(), BTN_LEFT);
        edge(3, status.getRightwards(), BTN_RIGHT);
        edge(4, status.getMiddlePressed(), BTN_PRESS);
    }

    private static void edge(int slot, Boolean now, int buttonId) {
        final boolean on = now != null && now;
        if (on && !sFiveDimPrev[slot]) {
            dispatch(buttonId);
        }
        sFiveDimPrev[slot] = on;
    }

    private static void dispatch(int buttonId) {
        // This runs inside an SDK callback on the main thread: letting anything
        // escape would take the whole app down.
        try {
            nativeOnDjiButton(buttonId);
        } catch (Throwable t) {
            Log.w(TAG, "dji: native button dispatch failed", t);
        }
    }

    /**
     * The 5-way and the record button are also in the joystick's raw USB reports, and
     * {@link RcRawJoystick} feeds them through these two while it is running. They land in
     * exactly the places the SDK's own callbacks use, so the trim mode, the key map and the
     * 5-way's centring rules apply unchanged - and on a remote whose SDK data never arrives
     * they are then the only source for these two controls at all.
     */
    static void onRawFiveWay(int buttonId) {
        dispatch(buttonId);
    }

    /**
     * The record button as the joystick's raw reports see it: the same switch {@code video}
     * drives and the same press - RcButtons is what keeps the two from both counting it, and the
     * button keeps working on a remote where the SDK's copy of it never arrives.
     */
    static void onRawRecordButton(boolean down) {
        try {
            buttonEdge(SLOT_VIDEO, "video", RcButtons.SOURCE_RAW, down);
        } catch (Throwable t) {
            Log.w(TAG, "dji: record button dispatch failed", t);
        }
    }

    /**
     * The photo button as the joystick's raw reports see it - byte 16 bit 3.
     *
     * <p>On the RC Pro the shutter is in those reports and the SDK's
     * {@code KeyShutterButtonDown} only answers now and then (measured: it does fire on this
     * remote, but not for every press) - so before this the button missed presses. Stepping SE is
     * the same work that callback does, so the two remotes end up in one place - and on the
     * RC Plus 2, which never sets this bit, nothing changes at all.
     */
    static void onRawShutter(boolean down) {
        try {
            shutterEdge(down, RcButtons.SOURCE_RAW);
        } catch (Throwable t) {
            Log.w(TAG, "dji: shutter from the raw report failed", t);
        }
    }

    /**
     * The round button beside the screen: byte 16 bit 0x01 of the joystick report, which is the
     * only place the SDK does not also carry it.
     *
     * <p>It drives SE on the RC Pro. On the RC Plus 2 it used to toggle SI, but SI became the
     * three-position switch R1/R2/R3 pick (see joystick.cpp: kPositionKeys), so the button has
     * no switch left there and its presses are dropped.
     *
     * <p>It reaches the app twice, which is what this button taught us: the joystick report
     * calls it bit 0x01, and the remote's own log calls it {@code ACTION_KEY_C4}. Both readers
     * feed the one slot and RcButtons merges them, so the press counts once whether or not
     * READ_LOGS was ever granted and whether or not the USB claim went through.
     */
    static void onRawRoundButton(boolean down) {
        if (SLOT_ROUND < 0) {
            return;   // no switch for this button on this remote
        }
        try {
            buttonEdge(SLOT_ROUND, "round button", RcButtons.SOURCE_RAW, down);
        } catch (Throwable t) {
            Log.w(TAG, "dji: the round button could not be delivered", t);
        }
    }

    /**
     * One press of a button the remote reported in its own log - see RcDpadLog for why that log
     * is the reliable reader for these and not the SDK.
     *
     * <p>All of them are press-only buttons, and each press steps its switch. Where the SDK, the
     * raw report or both also carry the same button, their copies are merged with this one by
     * RcButtons, so a press that both readers see still counts once. The 5-way, the shutter, the
     * focus and the record buttons are not mapped from here at all: the first four are in the
     * joystick's raw USB reports, and for the two camera buttons the raw bit is the reliable
     * reader on this remote - see onRawShutter and onRawRecordButton.
     */
    static void onDpadPress(final String name) {
        // Whether this reader may move a switch at all is the remote's own business (see
        // RcModel.logDrivesSwitches). On the RC Plus 2 the SDK and Android key codes already
        // deliver every one of these buttons, so a copy from the log would only ever be a
        // duplicate; on the others the log is the reader that keeps arriving while the SDK's
        // keys go silent, and RcButtons merges its copy with the other readers' anyway. The
        // pause button is on this path everywhere: SD is its slot on every remote.
        if (!REMOTE.logDrivesSwitches && !"PAUSE".equals(name)) {
            return;
        }
        final int index;
        final String label;
        switch (name) {
            case "PAUSE":  index = SLOT_PAUSE; label = "pause"; break;
            // The remote's own landing / return-to-home button. It is the reader that keeps
            // arriving while the SDK's keys are silent (see listenGoHome for the other one) and
            // the only source left on a remote where the takeoff button does not exist. The two
            // are merged now, so a press lands whichever of them is having a good day.
            case "GOHOME": index = SW_SA;    label = "landing"; break;
            case "C1":     index = SLOT_C1;  label = "C1"; break;
            case "C2":     index = SLOT_C2;  label = "C2"; break;
            case "C3":     index = SLOT_C3;  label = "C3"; break;
            case "C4":
                // The round button beside the screen, under the name the remote's log uses for
                // it - the same press the joystick report delivers as byte 16 bit 0x01, merged
                // with it by RcButtons.
                if (SLOT_ROUND < 0) return;
                index = SLOT_ROUND;
                label = "round button";
                break;
            case "UP": case "DOWN": case "LEFT": case "RIGHT": case "MIDDLE":
            case "SHUTTER": case "FOCUS": case "RECORD":
                return;
            default:
                // Something this app has never seen: worth one line, so a new control can be
                // identified from the log rather than guessed at.
                announceDpad(name);
                return;
        }
        // This reader writes one line per press and never a release (see RcDpadLog), so the
        // switch is stepped straight away - and RcButtons drops the copy when one of the readers
        // that does report both edges has already counted this press.
        if (RcButtons.tap(index, RcButtons.SOURCE_LOG) != RcButtons.PRESS) {
            logMerged(label, RcButtons.SOURCE_LOG, index);
            return;
        }
        toggleSwitch(index, label, RcButtons.SOURCE_LOG);
    }

    /** One log line the first time each unknown action turns up. */
    private static final java.util.Set<String> sDpadSeen = new java.util.HashSet<>();

    private static void announceDpad(String name) {
        synchronized (DjiMsdkBridge.class) {
            if (sDpadSeen.add(name)) {
                Log.i(TAG, "dji: the remote logged an unknown button '" + name
                        + "' - it has no EdgeTX mapping yet");
            }
        }
    }

    private static String describe(FiveDimensionPressedStatus status) {
        if (status == null) {
            return "null";
        }
        return "up=" + status.getUpwards()
                + " down=" + status.getDownwards()
                + " left=" + status.getLeftwards()
                + " right=" + status.getRightwards()
                + " press=" + status.getMiddlePressed();
    }
}
