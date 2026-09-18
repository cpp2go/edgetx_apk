// Hardware controller input: joystick / gamepad / RC-transmitter axes drive the
// firmware's analog channels, its buttons drive EdgeTX keys.
//
// Android delivers gamepad and HID-joystick axes as MotionEvents whose source
// class is JOYSTICK (or GAMEPAD), and the buttons as KeyEvents. The EdgeTX
// simulator reads its stick, slider and pot values from simuGetAnalog(idx) and
// its buttons from simuSetKey(), so a real controller only has to be pushed
// into the firmware - no firmware change is involved.
//
// This module also matters without any controller attached: every MotionEvent
// carries an X/Y pair, so without the source check a gamepad event would be
// mistaken for a touch at the wrong coordinates.
#pragma once

#include <android/input.h>
#include <android/native_activity.h>

namespace joystick {

// Enumerate the system InputDevices (via JNI) and remember which axes each one
// declares. Logs every device so it is possible to tell from logcat whether a
// given remote controller exposes its sticks to Android at all. Cheap enough to
// call again when an unknown device shows up.
void init(ANativeActivity* activity);

// True when at least one device looked like a joystick / gamepad.
bool present();

// Feed a motion event. Returns true when the event came from a joystick (and
// was consumed as analog input), i.e. the caller must not treat it as a touch.
bool handleMotionEvent(AInputEvent* event);

// Feed a key event. Returns true when it was a controller button (whether or
// not it maps to an EdgeTX key). Every unmapped button is logged once so the
// mapping can be settled against a real device.
bool handleKeyEvent(AInputEvent* event);

// Queue a synthetic key press (used by the DJI SDK bridge, which cannot press a
// key by itself - see joystick.cpp).
//
// `holdMs` is how long the key stays down. The firmware samples key state from its
// own thread, so a press that does not survive a few of its polls is never seen at
// all - and anything shorter than about 320 ms is a *short* press to EdgeTX
// (radio/src/keys.cpp, KEY_LONG_DELAY). The return key's double click passes a hold
// long enough to count as a long press, because the remote's own input stack hands
// that button over as a tap and a real hold never reaches us.
void requestKey(uint8_t key, uint32_t holdMs = 60);
void tick();

// Queue a stick position from the DJI SDK (axis 0..3, see nativeOnDjiStick).
// Applied from tick() for the same reasons as requestKey().
void requestStick(int axis, int32_t value);

// Queue an absolute dial position. `dial` is 0 = left, 1 = right; the dials
// report the same -660..660 range as the sticks.
//
// The dials drive EdgeTX's rotary encoder, not P1/P2: the movement between two
// readings is turned into encoder steps, which move the focus and edit values in
// the colour-LCD UI.
void requestDial(uint8_t dial, int32_t value);

// Queue rotary encoder steps directly (positive = clockwise = right). The 5-way's
// up/down use it: up is one step left, down is one step right.
void requestRotary(int32_t steps);

// Queue scroll-wheel movement. The wheel only reports relative steps, so they are
// accumulated into an EdgeTX slider (channel 7 = SL2).
void requestScrollWheel(int32_t steps);

// Queue a hardware switch position from the DJI SDK. `index` is 0 = SA, 1 = SB
// (see nativeOnDjiSwitch); `state` is <0 up, 0 middle, >0 down.
//
// The board's switch table and who drives what: SA takeoff, SB flight mode,
// SC video, SD pause, SE photo, SF C1/C2/C3, SG/SH/SI R1/R2/R3, SJ arm state.
void requestSwitch(int index, int8_t state);

// ---------------------------------------------------------------- startup ----
//
// Booting the firmware is what runs EdgeTX's boot checks, and those sample the
// throttle and every switch once and warn about whatever they find. Until the
// RC's own controls have reported, a stick reads as centred - so the host waits
// for this before letting the firmware start (see native_main.cpp: link_start).
//
// Set from DjiMsdkBridge.java once the DJI SDK is registered and the sticks are
// reporting, or once it is clear that they never will be. A build without the
// SDK has nothing to wait for and is ready from the start.
bool inputsReady();
void setInputsReady(bool ready);

// Where the positions of the latching switches are kept between runs
// ("<dir>/joystick.switches"); see joystick.cpp.
void setStateDir(const char* dir);

// Puts the saved switch positions into the firmware. Called from the host between
// simuInit() and simuStart(); they survive the boot's own switch reset because
// simu::setSwitch() keeps a copy in the library (see its boardInitSwitches()).
void restoreSwitches();

}  // namespace joystick
