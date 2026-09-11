// Host side of the ported EdgeTX simulator library.
//
// The app is a *thin host*: the whole UI is the real EdgeTX firmware inside
// libedgetx-<flavour>-simulator.so, driven through the platform API documented
// in radio/src/targets/simu/simulib.h. This file owns the firmware lifecycle
// and frame/touch plumbing; native_main.cpp owns the Android window.
#pragma once

#include <cstdint>

// EdgeTX simulator API (radio/src/targets/simu/simulib.h). These live in the
// simulator library at GLOBAL scope and keep C++ linkage, so they must be
// declared exactly as the firmware declares them - not wrapped in a namespace,
// or the calls resolve to simu::simuInit() and fail to link.
void simuInit();
void simuFatfsSetPaths(const char* sdPath, const char* settingsPath);
void simuCreateDefaults();
void simuStart(bool tests);
void simuStop();
bool simuIsRunning();
bool simuLcdChanged();
uint32_t simuLcdCopy(uint8_t* buf, uint32_t maxLen);
uint32_t simuLcdGetWidth();
uint32_t simuLcdGetHeight();
uint32_t simuLcdGetDepth();
void simuLcdFlushed();
void simuTouchDown(int16_t x, int16_t y);
void simuTouchUp();

// Key injection. `key` is an index into EdgeTX's EnumKeys
// (radio/src/hal/key_driver.h): 0 MENU, 1 EXIT, 2 ENTER, 3 PAGEUP, 4 PAGEDN,
// 5 UP, 6 DOWN, 7 LEFT, 8 RIGHT, 9 PLUS, 10 MINUS, 11 MODEL, 12 TELE,
// 13 SYS, 14 SHIFT, 15 BIND.
//
// simuSetKey() only writes to the state array, and the polling loop does not
// filter, so any index is deliverable - but only the keys the target's GUI
// actually consumes do anything. TX16SMK3's colour LCD reacts to exactly seven:
// EXIT, ENTER, PAGEUP, PAGEDN, MODEL, TELE and SYS.
void simuSetKey(uint8_t key, bool state);
void simuSetSwitch(uint8_t swtch, int8_t state);

// Implemented by the Android host layer inside the simulator library
// (radio/src/targets/simu/android_host.cpp). They let the app feed real
// hardware-joystick values into the firmware's ADC channels.
void edgetxAndroidSetAnalog(uint8_t idx, uint16_t value);
void edgetxAndroidSetAnalogExternal(uint8_t on);

namespace simu {

// ---------------------------------------------------------------- host API --

struct LcdInfo {
    int width = 0;
    int height = 0;
    int depth = 0;  // bits per pixel
};

LcdInfo lcdInfo();

// Start the firmware on a worker thread (simuStart() blocks until shutdown).
// `sdPath` is the simulated SD-card root, `settingsPath` the settings folder.
// Both must already exist.
bool start(const char* sdPath, const char* settingsPath);

// Ask the firmware to shut down and join the worker thread.
void stop();

// True while the firmware thread is alive.
bool running();

// Copies the latest LCD frame into `dst` when a new one is ready.
// Returns true if a frame was written (dst holds width*height*2 bytes, RGB565).
bool takeFrame(uint8_t* dst, uint32_t dstLen);

// Touch input in LCD coordinates.
void touchDown(int16_t x, int16_t y);
void touchUp();

// ------------------------------------------------------------- input API --
//
// EdgeTX analog channel order (radio/src/targets/simu/adc_driver.cpp and the
// SDL reference implementation in sdl_simu.cpp):
//
//   0 left stick X  (rudder)             1 left stick Y  (throttle, inverted)
//   2 right stick Y (elevator, inverted)  3 right stick X (aileron)
//   4..                                   flex inputs (sliders / pots)
//
// Values are 0..4096 with centre 2048.
void pushAnalog(uint8_t idx, uint16_t value);

// false = the firmware generates its demo sine wave (no joystick attached),
// true = use the values pushed with pushAnalog().
void setAnalogSource(bool external);

// EdgeTX key index (see the EnumKeys note above); `down` = pressed.
void setKey(uint8_t key, bool down);

// Hardware switch position. `index` follows the board's switch table
// (radio/src/boards/hw_defs/tx16smk3.json): 0 = SA, 1 = SB, 2 = SC, ... and
// `state` is <0 = up, 0 = middle, >0 = down, matching boardSwitchGetPosition().
void setSwitch(uint8_t index, int8_t state);

}  // namespace simu
