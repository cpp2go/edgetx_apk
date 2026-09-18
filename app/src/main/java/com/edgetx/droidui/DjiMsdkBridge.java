package com.edgetx.droidui;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
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
    // SDK's plain Boolean button keys do work, so SC/SD/SE and SF are driven from those.
    // Indices are the board's switch table, see joystick.cpp requestSwitch().
    //
    // Driving them from the SDK rather than from Android key codes is what makes them
    // keep working while the UI is gone (the app swiped away, RcLinkService still holding
    // the firmware): key events only reach a window that has focus, the SDK's callbacks do
    // not need one. SG/SH/SI are the exception - the R1/R2/R3 buttons are Android key codes
    // F4..F6 (see kSwitchKeys in joystick.cpp) and KeyRcButtonEventPro never fires on this
    // remote, so they have no SDK route and stop with the window.
    //
    // Measured on the RC: all of them fire. The shutter is the odd one - one contact
    // only, so it also drives a switch of its own, see listenShutter().

    static final int SWITCH_C = 2;   // video button
    static final int SWITCH_D = 3;   // pause button
    static final int SWITCH_E = 4;   // photo/shutter button, three positions
    static final int SWITCH_F = 5;   // C1/C2/C3 pick its position (see listenButtonKeys)
    static final int SWITCH_G = 6;   // R1 button - Android key code F4, see joystick.cpp
    static final int SWITCH_H = 7;   // R2 button
    static final int SWITCH_I = 8;   // R3 button

    /**
     * The shutter button as a three-position switch (SE).
     *
     * The button has a single contact - on the RC it is BTN_TR on the joystick device,
     * and the SDK reports it as one Boolean - so the two positions a camera shutter
     * normally has, half press and full press, are not in the data at all. What is left
     * is how long it was held, and that is what picks the outer state:
     *
     *     at boot               -> middle
     *     pressed &lt; 300 ms   -> up
     *     pressed &gt;= 300 ms  -> down
     *
     * The switch latches, so a channel fed from it holds the level until the next press.
     *
     * SE and not one of the free slots: this board declares SI and SJ as 2-position
     * switches, and configuring one of those as 3POS makes the firmware reject the whole
     * settings file.
     */
    private static final long SHUTTER_SHORT_PRESS_MS = 300;

    private static void listenShutter() {
        try {
            final DJIKey<Boolean> key =
                    KeyTools.createKey(DJIRemoteControllerKey.KeyShutterButtonDown);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Boolean>() {
                        private long pressedAt;

                        @Override
                        public void onValueChange(Boolean oldValue, Boolean newValue) {
                            if (newValue != null && newValue) {
                                pressedAt = System.currentTimeMillis();
                                return;
                            }

                            if (oldValue == null || pressedAt == 0) {
                                return;   // the initial snapshot, not a release
                            }

                            final long held = System.currentTimeMillis() - pressedAt;
                            pressedAt = 0;
                            final int position = held >= SHUTTER_SHORT_PRESS_MS ? 1 : -1;
                            sShutterPosition = position;
                            persistSwitch(PREF_PHOTO, position);
                            Log.i(TAG, "dji: photo held " + held + " ms -> switch E "
                                    + (position > 0 ? "down" : "up"));
                            nativeOnDjiSwitch(SWITCH_E, position);
                        }
                    });

            // SE latches, so a restart brings back the position of the last press; the
            // middle position is where it sits until the first one.
            nativeOnDjiSwitch(SWITCH_E, sShutterPosition);
        } catch (Throwable t) {
            Log.w(TAG, "dji: shutter listen failed", t);
        }
    }

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
        listenSwitchButton(DJIRemoteControllerKey.KeyRecordButtonDown, "video", SWITCH_C);
        listenShutter();
        // The pause button is a press-only button like the takeoff one, and the model
        // wants a latched level out of it (a channel that stays high until the next
        // press), so it toggles SD instead of being high only while it is held.
        listenToggleSwitch(SWITCH_D, "pause", DJIRemoteControllerKey.KeyPauseButtonDown);
        // C1/C2/C3 are the three positions of SF: up, middle, down. They used to be SG/SH/SI,
        // which the RC's R1/R2/R3 buttons now drive from Android key codes - SF is the one
        // that had to move onto the SDK, because only the SDK keeps reporting while the UI
        // is closed (key events need a focused window).
        listenPositionButton(DJIRemoteControllerKey.KeyCustomButton1Down, "C1", SWITCH_F, -1);
        listenPositionButton(DJIRemoteControllerKey.KeyCustomButton2Down, "C2", SWITCH_F, 0);
        listenPositionButton(DJIRemoteControllerKey.KeyCustomButton3Down, "C3", SWITCH_F, 1);
        listenGoHome();
    }

    /**
     * A button that selects one position of a switch, which then stays there until another
     * of its buttons is pressed - only the press half of the SDK's pulse matters.
     *
     * <p>This is what SF is: C1 up, C2 middle, C3 down. (L1/L2/L3 did this from Android
     * key codes, where the release would have to be ignored the same way.)
     */
    private static void listenPositionButton(DJIKeyInfo<Boolean> info, String name, int index,
                                             int position) {
        try {
            final DJIKey<Boolean> key = KeyTools.createKey(info);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Boolean>() {
                        @Override
                        public void onValueChange(Boolean oldValue, Boolean newValue) {
                            if (oldValue == null || !Boolean.TRUE.equals(newValue)) {
                                return;   // the initial snapshot, or the release half
                            }
                            Log.i(TAG, "dji: " + name + " -> switch " + (char) ('A' + index)
                                    + " position " + position);
                            try {
                                nativeOnDjiSwitch(index, position);
                            } catch (Throwable t) {
                                Log.w(TAG, "dji: switch " + index + " dispatch failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "dji: " + name + " listen failed", t);
        }
    }

    /**
     * The go-home button is not a switch of its own: pressing it one, two or three
     * times tells the 5-way what to trim, so only the presses are forwarded.
     */
    private static void listenGoHome() {
        try {
            final DJIKey<Boolean> key =
                    KeyTools.createKey(DJIRemoteControllerKey.KeyGoHomeButtonDown);
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Boolean>() {
                        @Override
                        public void onValueChange(Boolean oldValue, Boolean newValue) {
                            if (newValue != null && newValue &&
                                (oldValue == null || !oldValue)) {
                                Log.i(TAG, "dji: go-home pressed");
                                nativeOnDjiGoHome();
                            }
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
                            markStickSeen(axis);
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

    /** Indices in the board's switch table (tx16smk3.json): 0 = SA, 1 = SB, 7 = SH, 9 = SJ. */
    static final int SW_SA = 0;
    static final int SW_SB = 1;
    static final int SW_SH = 7;

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
    private static final int SWITCH_COUNT = 10;   // SA..SJ
    private static final boolean[] sToggleHigh = new boolean[SWITCH_COUNT];

    /**
     * Position of the photo switch (SE) from the last press, which is also where a
     * restart picks it up again (see {@link #restoreLatchedSwitches}).
     */
    private static int sShutterPosition = 0;

    /** Where the latching switch positions are kept between runs. */
    private static final String PREFS = "link";
    private static final String PREF_TAKEOFF = "switchSA";
    private static final String PREF_PAUSE = "switchSD";
    private static final String PREF_PHOTO = "switchSE";
    private static final String PREF_UPTIME = "switchUptime";

    private static SharedPreferences sPrefs;

    /**
     * Picks up the positions the latching switches had when the app last ran.
     *
     * <p>The takeoff and pause buttons toggle a level that only this class knows about, and
     * the photo button picks its position from how long it is held. The SDK reports presses
     * for all three and never the latched level, while the remote's own latch keeps its
     * position across the app being killed - so the positions are remembered here and handed
     * back on the next start. See {@link #listenToggleSwitch} for the one case that cannot be
     * got right.
     *
     * <p>Nothing is restored after a reboot of the remote itself: its latches go back to their
     * power-on state (the takeoff button powers up low, with a red LED), and the uptime check
     * below is what tells such a reboot apart from the app merely being restarted.
     *
     * <p>Stored here rather than in joystick.cpp, which keeps the one switch it alone sees
     * (SF, the L1/L2/L3 buttons): this runs from Application.onCreate, early enough to beat
     * the SDK's registration, whereas the native side only learns its directory once
     * RcLinkService starts the link.
     */
    private static void restoreLatchedSwitches() {
        final SharedPreferences prefs = sPrefs;
        if (prefs == null) {
            Log.w(TAG, "dji: no preferences, the switches start from their power-on state");
            return;
        }

        // elapsedRealtime() only ever grows within one boot of the device, so a value below
        // the one saved alongside the positions means the remote restarted since then.
        if (SystemClock.elapsedRealtime() < prefs.getLong(PREF_UPTIME, 0)) {
            Log.i(TAG, "dji: remote rebooted since the switches were saved, starting over");
            return;
        }

        final int takeoff = prefs.getInt(PREF_TAKEOFF, 0);
        if (takeoff != 0) sToggleHigh[SW_SA] = (takeoff == SWITCH_HIGH);

        final int pause = prefs.getInt(PREF_PAUSE, 0);
        if (pause != 0) sToggleHigh[SWITCH_D] = (pause == SWITCH_HIGH);

        final int photo = prefs.getInt(PREF_PHOTO, 0);
        if (photo != 0) sShutterPosition = photo;

        Log.i(TAG, "dji: switches carried over - takeoff "
                + (sToggleHigh[SW_SA] ? "high" : "low") + ", pause "
                + (sToggleHigh[SWITCH_D] ? "high" : "low") + ", photo " + sShutterPosition);
    }

    /** Remembers one of the latching positions for the next run. */
    private static void persistSwitch(String key, int position) {
        final SharedPreferences prefs = sPrefs;
        if (prefs == null) return;
        prefs.edit()
                .putInt(key, position)
                .putLong(PREF_UPTIME, SystemClock.elapsedRealtime())
                .apply();
    }

    /**
     * A button reporting only its press, driving a two-position EdgeTX switch: the
     * takeoff button (SA) and the pause button (SD) both work this way.
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
            KeyManager.getInstance().listen(key, OWNER,
                    new CommonCallbacks.KeyListener<Boolean>() {
                        @Override
                        public void onValueChange(Boolean oldValue, Boolean newValue) {
                            if (oldValue == null) {
                                // A snapshot, not a press: see the note above - this is the
                                // measurement, not a decision.
                                Log.i(TAG, "dji: " + label + " initial snapshot " + newValue);
                                return;
                            }
                            if (!Boolean.TRUE.equals(newValue)) {
                                return;
                            }
                            final boolean high;
                            synchronized (DjiMsdkBridge.class) {
                                sToggleHigh[index] = !sToggleHigh[index];
                                high = sToggleHigh[index];
                            }
                            Log.i(TAG, "dji: switch " + index + " (" + label + ") -> "
                                    + (high ? "high" : "low"));
                            dispatchToggle(index, high);
                        }
                    });
            dispatchToggle(index, sToggleHigh[index]);
        } catch (Throwable t) {
            Log.w(TAG, "dji: " + label + " listen failed", t);
        }
    }

    private static void dispatchToggle(int index, boolean high) {
        if (index == SW_SA) {
            persistSwitch(PREF_TAKEOFF, high ? SWITCH_HIGH : SWITCH_LOW);
        } else if (index == SWITCH_D) {
            persistSwitch(PREF_PAUSE, high ? SWITCH_HIGH : SWITCH_LOW);
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
                //
                // The two ends are the other way round in EdgeTX on purpose: the switch
                // is labelled high/middle/low, and its high position is EdgeTX's down,
                // so the sign is flipped here rather than left to every model to undo.
                case SWITCH_TWO: return 1;
                case SWITCH_ONE: return 0;
                case SWITCH_THREE: return -1;
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
    // The SDK hands out these values on change, and on this RC that push arrives at
    // roughly 6-9 Hz per axis: measured on the device, 20-36 samples a second over
    // the four axes, with gaps up to 370 ms between two of them. That is 100-200 ms
    // of lag before the mixer sees a stick move, which is what makes the sticks feel
    // remote-controlled rather than direct.
    //
    // KeyManager.getValue() reads the cache the SDK fills as the data arrives, so it
    // is polled here instead. The listeners above stay as a fallback - a poll that
    // returns the same value again pushes nothing.
    private static final int POLL_INTERVAL_MS = 5;

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

        while (true) {
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
                try {
                    if (i < 4) {
                        markStickSeen(i);
                        nativeOnDjiStick(i, raw);
                    } else {
                        nativeOnDjiDial(i - 4, raw);
                    }
                } catch (Throwable ignored) {
                    // A missing native side must not kill the poller.
                }
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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
