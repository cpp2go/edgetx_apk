package com.edgetx.droidui;

import android.app.Application;
import android.util.Log;

import dji.sdk.keyvalue.key.DJIKey;
import dji.sdk.keyvalue.key.DJIKeyInfo;
import dji.sdk.keyvalue.key.DJIRemoteControllerKey;
import dji.sdk.keyvalue.key.KeyTools;
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
    static final int BTN_SYS = 6;

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
     * Absolute dial position. `channel` is an EdgeTX analog channel index:
     * 4 = P1, 5 = P2 for this board. The dials report the same -660..660 range as
     * the sticks.
     */
    static native void nativeOnDjiDial(int channel, int value);

    /** Scroll-wheel movement, in detents (the SDK value is relative). */
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
            listenSticks();
            listenSwitches();
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

        // The go-home (RTH) button. Android never reports it and it has no entry
        // in RcCustomButtonEvent either, so this key is the only source. It is a
        // level as well, hence the rising edge.
        try {
            final DJIKey<Boolean> key =
                    KeyTools.createKey(DJIRemoteControllerKey.KeyGoHomeButtonDown);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Boolean>() {
                        @Override
                        public void onValueChange(Boolean oldValue, Boolean newValue) {
                            Log.i(TAG, "dji: go-home button = " + newValue);
                            edgeGoHome(newValue);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: go-home listen failed", t);
        }
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

    /** Indices in the board's switch table (tx16smk3.json): 0 = SA, 1 = SB. */
    static final int SW_SA = 0;
    static final int SW_SB = 1;

    /**
     * The RC's physical switches. Its flight-mode switch is three-position and its
     * transformation switch is two-position, which line up with EdgeTX's switch
     * positions (-1 up / 0 middle / 1 down).
     */
    private static void listenSwitches() {
        listenEnumSwitch(SW_SA, DJIRemoteControllerKey.KeyFlightModeSwitchState);
        listenEnumSwitch(SW_SB, DJIRemoteControllerKey.KeyRCTransformationSwitchState);
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

    /** EdgeTX analog channels for this board's two pots (hw_defs/tx16smk3.json). */
    static final int DIAL_P1 = 4;
    static final int DIAL_P2 = 5;

    /**
     * The two dials and the scroll wheel. The dials are absolute (-660..660, same
     * as the sticks); the wheel only reports relative steps, which the native side
     * accumulates into the SL2 slider because this target has no rotary navigation.
     */
    private static void listenDials() {
        listenDial(DIAL_P1, DJIRemoteControllerKey.KeyLeftDial);
        listenDial(DIAL_P2, DJIRemoteControllerKey.KeyRightDial);
        listenScrollWheel();
    }

    private static void listenDial(final int channel, DJIKeyInfo<Integer> info) {
        try {
            final DJIKey<Integer> key = KeyTools.createKey(info);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Integer>() {
                        @Override
                        public void onValueChange(Integer oldValue, Integer newValue) {
                            if (newValue == null) {
                                return;
                            }
                            announce("dial " + channel);
                            try {
                                nativeOnDjiDial(channel, newValue);
                            } catch (Throwable t) {
                                Log.w(TAG, "dji: dial dispatch failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: dial listen failed for channel " + channel, t);
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
    private static boolean sGoHomePrev;

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

    /** Rising edge of the go-home button, which has no slot in sFiveDimPrev. */
    private static void edgeGoHome(Boolean now) {
        final boolean on = now != null && now;
        if (on && !sGoHomePrev) {
            dispatch(BTN_SYS);
        }
        sGoHomePrev = on;
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
