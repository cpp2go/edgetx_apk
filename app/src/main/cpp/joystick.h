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

}  // namespace joystick
