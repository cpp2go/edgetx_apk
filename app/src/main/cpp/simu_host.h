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

// Boot with the EdgeTX splash screen, but skip the first-boot calibration wizard
// and the throttle/switch startup checks. Call before simuStart().
//
// The splash dismisses itself after SPLASH_TIMEOUT; the wizard and the startup
// checks wait for key presses, and this RC's touch panel does not reach the
// firmware, so they would be dead ends.
void simuSetSplashStartup();

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
void simuTouchCancel();

// Key injection. `key` is an index into EdgeTX's EnumKeys
// (radio/src/hal/key_driver.h): 0 MENU, 1 EXIT, 2 ENTER, 3 PAGEUP, 4 PAGEDN,
// 5 UP, 6 DOWN, 7 LEFT, 8 RIGHT, 9 PLUS, 10 MINUS, 11 MODEL, 12 TELE,
// 13 SYS, 14 SHIFT, 15 BIND.
//
// simuSetKey() only writes to the state array, and the polling loop does not
// filter, so any index is deliverable - but only the keys the target's GUI
// actually consumes do anything. TX16SMK3's colour LCD reacts to exactly seven:
// EXIT, ENTER, PAGEUP, PAGEDN, MODEL, TELE and SYS. EXIT is the key the radio
// prints RTN on; joystick.cpp uses that name in the log and accepts both in the
// key map file.
// Rotary encoder steps (positive = clockwise), from radio/src/targets/simu/simulib.h.
//
// Each step moves the firmware's encoder position, which EdgeTX feeds to LVGL as
// encoder input: it walks the focus between the controls of the current window and,
// with a field in edit mode, changes its value.
void simuRotaryEncoderEvent(int32_t steps);

void simuSetKey(uint8_t key, bool state);
void simuSetSwitch(uint8_t swtch, int8_t state);
void simuSetTrim(uint8_t trim, bool state);
void simuSetTrim(uint8_t trim, bool state);

// Implemented by the Android host layer inside the simulator library
// (radio/src/targets/simu/android_host.cpp). They let the app feed real
// hardware-joystick values into the firmware's ADC channels.
void edgetxAndroidSetAnalog(uint8_t idx, uint16_t value);
void edgetxAndroidSetAnalogExternal(uint8_t on);

// Battery and charging state, read by the firmware's own battery and charger paths
// (targets/simu/adc_driver.cpp and led_driver.cpp).
//
// Weak on purpose: it only exists in simulator libraries built after the injection was
// added, and a strong reference would make the whole app fail to load against an older
// one. Check for null before calling (see setBattery).
void edgetxAndroidSetBattery(uint16_t millivolts, uint8_t charging) __attribute__((weak));

// What the firmware's battery path sees, i.e. what was injected above. Weak for the
// same reason; used to tell "the value never reached the library" apart from "the
// library has it but the reading does not use it".
uint16_t edgetxAndroidBatteryMillivolts() __attribute__((weak));

// The firmware's battery warning threshold and the range behind its percentage, all in
// 100 mV steps. Weak like the others: an older simulator library simply has no such
// entry point and then the log omits them.
void edgetxAndroidBatterySettings(uint16_t* warn, uint16_t* batMin, uint16_t* batMax)
    __attribute__((weak));

// Protocol the model selected for the module (MODULE_TYPE_*, 0 = slot off).
uint8_t edgetxAndroidExternalModuleType() __attribute__((weak));

// Switch positions the app drives, kept by the library so the board's switch driver can
// put them back after the boot has reset them (see setSwitch above). Weak like the rest:
// an older simulator library has no such entry point, and then the positions only last
// until the firmware restarts.
void edgetxAndroidSetSwitch(uint8_t index, int8_t state) __attribute__((weak));

// The remote controller's own GPS, written by DjiMsdkBridge (see listenGps). It lands in the
// firmware's gpsData - the same slot an internal GPS module fills (radio/src/gps.h) - so Radio
// Info, Statistics, the top-bar GPS view and luaGetGPSPosition() show the remote's position.
// The telemetry GPS that arrives over the RF module is a different thing and is not touched.
//
// Units are the firmware's: 1e-6 degrees, 0.1 m, 0.1 m/s, 0.1 degrees, satellites, and 0/1 for
// the fix. Weak like the others, so an older simulator library still lets the app load - check
// for null before calling, see simu::setGps().
void edgetxAndroidSetGps(int32_t latitude, int32_t longitude, int32_t altitude, uint16_t speed,
                         uint16_t course, uint8_t satellites, uint8_t fix)
    __attribute__((weak));

// What the firmware's own GPS holds after such a push (radio/src/gps.h), so the app can check
// instead of assume. Weak like the rest: an older simulator library has no such entry point.
void edgetxAndroidGetGps(int32_t* latitude, int32_t* longitude, uint8_t* satellites,
                         uint8_t* fix) __attribute__((weak));

// How many haptic events the firmware has raised so far (radio/src/haptic.cpp counts them under
// SIMU). Only the event path is counted - a continuous buzz is not something the RC's shake motor
// could follow anyway - and the app turns each new event into one shake of the remote, see
// DjiMsdkBridge.pollHaptics(). Weak for the same reason as above.
uint32_t simuGetHaptic() __attribute__((weak));

// Implemented by the simulator library (gui/colorlcd/lcd.cpp). Weak so an older
// library without it still links, and then nothing is done.
void lcdRequestFullRefresh() __attribute__((weak));

// Implemented by the simulator library too: the firmware's own running state.
bool simuIsRunning() __attribute__((weak));

// Aux serial bridge (radio/src/targets/simu/simulib.h). The firmware calls the
// sink when its module serial port starts, stops, changes baud rate or
// transmits; the app feeds bytes back in with simuAuxSerialReceive().
//
// This is what lets the RF module live on a USB serial port: the
// protocol is the one EdgeTX is configured for, and its bytes come out here.
struct edgetxAndroidSerialSink {
    void (*start)(uint8_t port_nr, uint32_t baudrate, uint8_t encoding);
    void (*stop)(uint8_t port_nr);
    void (*setBaudrate)(uint8_t port_nr, uint32_t baudrate);
    void (*send)(uint8_t port_nr, const uint8_t* data, uint32_t len);
};

// Weak for the same reason as edgetxAndroidSetBattery: an older simulator library
// without the bridge must not stop the app from loading (see module_serial::init).
void edgetxAndroidSetAuxSerialSink(const edgetxAndroidSerialSink* sink) __attribute__((weak));

// port_nr is 0 for AUX1, 1 for AUX2 - the module port uses AUX1.
void simuAuxSerialReceive(uint8_t port_nr, const uint8_t* data, uint32_t len);

// Audio (radio/src/targets/simu/android_host.cpp): the firmware queues the PCM its
// own mixer produces and the app drains it here and plays it. That is the whole
// audio path - there is nothing to synthesise on this side.
uint32_t edgetxAndroidTakeAudio(uint8_t* dst, uint32_t maxLen);
uint32_t edgetxAndroidAudioSampleRate();
uint32_t edgetxAndroidAudioWrittenBytes();
uint32_t edgetxAndroidAudioDroppedBytes();

// Readings the firmware exposes. Used to report what the UI is actually showing.
// getBatteryVoltage() returns the raw ADC value, which the UI divides by 20 to
// get tenths of a volt; usbChargerLed() drives the charging icon in the top bar.
uint16_t getBatteryVoltage();
bool usbChargerLed();

// The firmware's own reading of an analog input (hal/adc_driver.cpp), 0..2048 - half of
// what pushAnalog() takes. It is what the mixer actually sees, i.e. after the jitter
// filter, which is what makes reading it back worth while (see joystick.cpp).
uint16_t anaIn(uint8_t chan);

// The firmware's own switch state (radio/src/hal/switch_driver.h):
// switchState(3 * index + position) answers true for the position a switch is in, with
// 0 = up, 1 = middle, 2 = down. Weak like the others, so an older library still loads.
uint32_t switchState(uint8_t pos_idx) __attribute__((weak));

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
void touchCancel();

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

// Queue rotary encoder steps (positive = clockwise). One step is one encoder detent:
// it moves the focus, and edits the value of a field that is in edit mode. This is
// the navigation control on this target - the UP/DOWN/LEFT/RIGHT keys do nothing.
void rotaryEncoderEvent(int32_t steps);

// EdgeTX key index (see the EnumKeys note above); `down` = pressed.
void setKey(uint8_t key, bool down);

// Trim switch, exactly as readTrims() sees it: `bit` is the trim axis (0 = left
// stick horizontal, 1 = left vertical, 2 = right horizontal, 3 = right vertical)
// times two, plus one for the positive direction (so even = down/left, odd =
// up/right). Used to trim a stick from the 5-way, see joystick.cpp.
void setTrim(uint8_t bit, bool state);

// Hardware switch position. `index` follows the board's switch table
// (radio/src/boards/hw_defs/tx16smk3.json): 0 = SA, 1 = SB, 2 = SC, ... and
// `state` is <0 = up, 0 = middle, >0 = down, matching boardSwitchGetPosition().
//
// Every position is also handed to the library (edgetxAndroidSetSwitch below), because
// the boot resets all switches to "up" after the app has already sent them - see the
// switch driver's boardInitSwitches().
void setSwitch(uint8_t index, int8_t state);

// Battery state reported by Android (see RcBattery.java).
//
// Note the battery is the one reading the firmware does not take from the host:
// targets/simu/adc_driver.cpp recomputes it from the configured warning voltage on
// every conversion, so the firmware keeps showing its own value - logFirmwareBattery()
// records both. It takes effect once that driver prefers the host's value, which needs
// the simulator library to be rebuilt.
void setBattery(uint16_t millivolts, uint8_t percent, bool charging);

    /**
     * The remote controller's own GPS, into the firmware's own GPS (see edgetxAndroidSetGps
     * above). Called from DjiMsdkBridge when the DJI SDK publishes a new position.
     */
    void setGps(int32_t latitude, int32_t longitude, int32_t altitude, uint16_t speed,
                uint16_t course, uint8_t satellites, bool fix);

    /**
     * The firmware's own view of the same position, or false when the loaded simulator library
     * is older than this call. Used to log whether a push really landed - see setGps above.
     */
    bool firmwareGps(int32_t* latitude, int32_t* longitude, int* satellites, bool* fix);

    /**
     * How many haptic events the firmware has raised so far, or 0 when the loaded simulator
     * library is older than this call. Never decreases, so a caller can act on the difference.
     */
    uint32_t hapticEvents();
// Logs the battery the firmware itself reports, next to the RC's. Only call this once
// the firmware is running (the ADC tables are set up by simuInit()).
void logFirmwareBattery();

// Logs where the firmware's own switch driver thinks every switch is. This is the check
// that the positions pushed with setSwitch() survived the boot, which resets all of them
// (see setSwitch). Only call this once the firmware is running.
void logFirmwareSwitches();

// Drains up to maxLen bytes of the firmware's audio, oldest first. Returns how many
// were copied, 0 when nothing is queued (see RcAudio.java).
uint32_t takeAudio(uint8_t* dst, uint32_t maxLen);

// The firmware's audio format: RcAudio sizes its AudioTrack from these.
uint32_t audioSampleRate();
uint32_t audioWrittenBytes();
uint32_t audioDroppedBytes();

// How much of the firmware's audio the host device has accepted but not played yet.
// RcAudio keeps this in step with AudioTrack's playback head; the firmware uses it to
// hold the boot splash open until the greeting has actually finished. Weak because an
// older simulator library has no such entry point, and then the boot is not delayed.
void edgetxAndroidSetHostAudioPending(uint32_t bytes) __attribute__((weak));
void setAudioPending(uint32_t bytes);
uint32_t audioPendingBytes();

// Asks the firmware to repaint the whole screen, so a window that attaches after the
// firmware has already drawn its first screens has something to show.
void requestFullRefresh();

// Whether the firmware itself is up. The activity must not gate its rendering on the
// activity's own link-thread flag: RcLinkService can be holding the firmware while that
// link is stopped, and then nothing is taken or presented and the screen stays black.
bool firmwareRunning();

// Protocol the model selected for the module slot, 0 when it is off.
uint8_t externalModuleType();

}  // namespace simu
