package com.edgetx.droidui;

import android.app.Application;
import android.util.Log;

import dji.sdk.keyvalue.key.DJIFlightControllerKey;
import dji.sdk.keyvalue.key.DJIKey;
import dji.sdk.keyvalue.key.DJIKeyInfo;
import dji.sdk.keyvalue.key.DJIRemoteControllerKey;
import dji.sdk.keyvalue.key.KeyTools;
import dji.sdk.keyvalue.value.remotecontroller.BatteryInfo;
import dji.sdk.keyvalue.value.remotecontroller.FiveDimensionPressedStatus;
import dji.sdk.keyvalue.value.remotecontroller.RcCustomButtonEvent;
import dji.sdk.keyvalue.value.remotecontroller.RcCustomButtonHardwareStatus;
import dji.sdk.keyvalue.value.remotecontroller.RCFlightModeSwitch;
import dji.sdk.keyvalue.value.remotecontroller.RCTransformationSwitchState;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import dji.v5.common.register.DJISDKInitEvent;
import dji.v5.manager.KeyManager;
import dji.v5.manager.SDKManager;
import dji.v5.manager.interfaces.SDKManagerCallback;

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
     * Absolute dial position. `dial` is 0 = left, 1 = right; the dials report the
     * same -660..660 range as the sticks.
     *
     * The dials drive EdgeTX's rotary encoder: the movement between two readings
     * becomes encoder steps, which move the focus and edit values in the UI. They
     * do not map to P1/P2 - see joystick.cpp.
     */
    static native void nativeOnDjiDial(int dial, int value);

    /**
     * Scroll-wheel movement, in detents (the SDK value is relative).
     *
     * No built-in control produces the MODEL or TELE keys: the 5-way's up/down
     * report the plain UP/DOWN keys instead.
     */
    static native void nativeOnDjiScrollWheel(int steps);

    public static void init(Application app) {
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
            listenSwitches();
            listenArmState();
            listenBattery();
            listenDials();
            Log.i(TAG, "dji: registered, product category "
                    + SDKManager.getInstance().getProductCategory());
            sListening = true;
            Log.i(TAG, "dji: listening for RC button events");
        } catch (Throwable t) {
            Log.w(TAG, "dji: listen failed", t);
        }
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
                            Log.i(TAG, "dji: rc hardware 5-way "
                                    + describe(newValue.getFiveDimensionPressStatus()));
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
    // SDK's plain Boolean button keys do work, so SF/SG/SH are driven from those.
    // Indices are the board's switch table, see joystick.cpp requestSwitch().
    //
    // Measured on the RC: pause and video fire, photo does not - KeyShutterButtonDown
    // is the camera's shutter, which only reports once a camera/aircraft is
    // connected. The wiring is there for when one is.

    static final int SWITCH_F = 5;   // video button
    static final int SWITCH_G = 6;   // photo button
    static final int SWITCH_H = 7;   // pause button

    private static void listenSwitchButton(DJIKeyInfo<Boolean> info, String name, int index) {
        try {
            final DJIKey<Boolean> key = KeyTools.createKey(info);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Boolean>() {
                        @Override
                        public void onValueChange(Boolean oldValue, Boolean newValue) {
                            final boolean down = newValue != null && newValue;
                            Log.i(TAG, "dji: " + name + (down ? " pressed" : " released")
                                    + " -> EdgeTX switch " + (char) ('A' + index));
                            nativeOnDjiSwitch(index, down ? 1 : -1);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: " + name + " listen failed", t);
        }
    }

    private static void listenButtonKeys() {
        listenSwitchButton(DJIRemoteControllerKey.KeyRecordButtonDown, "video", SWITCH_F);
        listenSwitchButton(DJIRemoteControllerKey.KeyShutterButtonDown, "photo", SWITCH_G);
        listenSwitchButton(DJIRemoteControllerKey.KeyPauseButtonDown, "pause", SWITCH_H);
    }

    static final int STICK_LEFT_H = 0;
    static final int STICK_LEFT_V = 1;
    static final int STICK_RIGHT_H = 2;
    static final int STICK_RIGHT_V = 3;

    private static final java.util.Set<String> sAnnounced = new java.util.HashSet<>();
    /**
     * The RC's L1/L2/L3/R1/R2/R3 buttons are NOT here: they arrive as Android key
     * codes F1..F6 and are turned into EdgeTX switches in joystick.cpp.
     *
     * The SDK's own boolean button keys (KeyRecordButtonDown, KeyShutterButtonDown,
     * KeyCustomButton1Down(), ... KeyPauseButtonDown) were all listened to during
     * development and never fired on this remote, so they are not wired up.
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
                            try {
                                nativeOnDjiStick(axis, newValue);
                            } catch (Throwable t) {
                                Log.w(TAG, "dji: stick dispatch failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: stick listen failed for axis " + axis, t);
        }
    }

    /** Indices in the board's switch table (tx16smk3.json): 0 = SA, 1 = SB, 9 = SJ. */
    static final int SW_SA = 0;
    static final int SW_SB = 1;

    /**
     * Free slot used to carry the aircraft's arm state into EdgeTX, where any screen,
     * logical switch or special function can pick it up. On this board SJ is a real
     * 2POS position that nothing else drives.
     */
    static final int SW_SJ = 9;

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
        listenEnumSwitch(SW_SB, DJIRemoteControllerKey.KeyFlightModeSwitchState);
    }

    /**
     * The takeoff button's two states as EdgeTX switch positions. The button powers up
     * low with a red LED and that low state is SA down; the green LED is high, SA up -
     * so the arrow points up whenever the LED is green.
     */
    private static final int TAKEOFF_LOW = 1;
    private static final int TAKEOFF_HIGH = -1;

    /**
     * Latched state of the takeoff button as EdgeTX sees it. It tracks the hardware's
     * own high/low because one SDK press pulse maps to exactly one toggle.
     */
    private static boolean sTakeoffHigh;

    /** A button reporting only its press, driving a two-position EdgeTX switch. */
    private static void listenToggleSwitch(final int index, final String label,
                                           DJIKeyInfo<Boolean> info) {
        try {
            final DJIKey<Boolean> key = KeyTools.createKey(info);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Boolean>() {
                        @Override
                        public void onValueChange(Boolean oldValue, Boolean newValue) {
                            // The first callback is the initial snapshot, not a press,
                            // and the SDK also reports the release half of the pulse.
                            if (oldValue == null || !Boolean.TRUE.equals(newValue)) {
                                return;
                            }
                            final boolean high;
                            synchronized (DjiMsdkBridge.class) {
                                sTakeoffHigh = !sTakeoffHigh;
                                high = sTakeoffHigh;
                            }
                            Log.i(TAG, "dji: switch " + index + " (" + label + ") -> "
                                    + (high ? "high" : "low"));
                            dispatchToggle(index, high);
                        }
                    });
            // Presses are all the SDK reports, so the latched level has to be assumed:
            // the button starts out low (red LED), which is also how SA should look.
            sTakeoffHigh = false;
            dispatchToggle(index, false);
        } catch (Throwable t) {
            Log.w(TAG, "dji: " + label + " listen failed", t);
        }
    }

    private static void dispatchToggle(int index, boolean high) {
        try {
            nativeOnDjiSwitch(index, high ? TAKEOFF_HIGH : TAKEOFF_LOW);
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
                            try {
                                nativeOnDjiSwitch(SW_SJ, newValue ? 1 : -1);
                            } catch (Throwable t) {
                                Log.w(TAG, "dji: arm state dispatch failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: arm state listen failed", t);
        }
    }

    private static <T> void listenEnumSwitch(final int index, DJIKeyInfo<T> info) {
        try {
            final DJIKey<T> key = KeyTools.createKey(info);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<T>() {
                        @Override
                        public void onValueChange(T oldValue, T newValue) {
                            final Integer state = switchPosition(newValue);
                            if (state == null) {
                                return;
                            }
                            Log.i(TAG, "dji: switch " + index + " <- " + newValue);
                            try {
                                nativeOnDjiSwitch(index, state);
                            } catch (Throwable t) {
                                Log.w(TAG, "dji: switch dispatch failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: switch listen failed for " + index, t);
        }
    }

    /** Maps the SDK's switch enumerations onto EdgeTX's -1 / 0 / 1 positions. */
    private static Integer switchPosition(Object value) {
        if (value instanceof RCFlightModeSwitch) {
            switch ((RCFlightModeSwitch) value) {
                // Measured on the RC Plus 2 - the SDK's numbering has nothing to do
                // with the physical order of the switch:
                //     switch at the top    -> SWITCH_TWO
                //     switch in the middle -> SWITCH_ONE
                //     switch at the bottom -> SWITCH_THREE
                case SWITCH_TWO: return -1;
                case SWITCH_ONE: return 0;
                case SWITCH_THREE: return 1;
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
                            try {
                                nativeOnDjiDial(dial, newValue);
                            } catch (Throwable t) {
                                Log.w(TAG, "dji: dial dispatch failed", t);
                            }
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
