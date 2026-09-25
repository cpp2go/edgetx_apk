#include "simu_host.h"

#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "joystick.h"
#include "log.h"

namespace simu {

namespace {

std::thread g_thread;
std::atomic<bool> g_started{false};
std::atomic<bool> g_stopRequested{false};

LcdInfo g_lcd;
std::vector<uint8_t> g_frame;  // scratch buffer for simuLcdCopy()
std::mutex g_frameMutex;       // takeFrame() has two possible callers, see below

void firmwareMain(const char* sdPath, const char* settingsPath) {
    LOGI("firmware: simuInit");
    simuInit();

    simuFatfsSetPaths(sdPath, settingsPath);

    // First run: let the firmware create radio.yml / model defaults.
    simuCreateDefaults();

    // The RC's latching switches are still where they were left, and EdgeTX reads
    // their positions while booting, so they go back before simuStart(). This has to
    // be after simuInit(): that is what resets every switch to "up" (see
    // joystick.cpp: restoreSwitches()).
    joystick::restoreSwitches();

    LOGI("firmware: simuStart (sd=%s settings=%s)", sdPath, settingsPath);
    // Show the boot splash (it is EdgeTX's own startup screen and was missing
    // because of the flags below) together with the normal startup checks - the
    // throttle/stick/switch warnings stay on, but the boot is held back until the
    // RC's own controls report (see link_start()), so they no longer fire on sticks
    // and switches that have simply not arrived yet. Only the first-boot calibration
    // wizard stays off: it just needs to be seen once and the settings file this port
    // ships already carries a valid calibration.
    simuSetSplashStartup();
    simuStart(false);  // blocks until the firmware shuts down
    LOGI("firmware: simuStart returned");

    g_started = false;
}

}  // namespace

LcdInfo lcdInfo() {
    if (g_lcd.width == 0) {
        g_lcd.width = static_cast<int>(simuLcdGetWidth());
        g_lcd.height = static_cast<int>(simuLcdGetHeight());
        g_lcd.depth = static_cast<int>(simuLcdGetDepth());
        LOGI("LCD %dx%d depth %d", g_lcd.width, g_lcd.height, g_lcd.depth);
    }
    return g_lcd;
}

bool start(const char* sdPath, const char* settingsPath) {
    if (g_started) return true;

    // The firmware thread clears g_started as soon as simuStart() returns, but the
    // std::thread object stays joinable until somebody joins it. Assigning over a
    // joinable thread calls std::terminate, which is the SIGABRT ("terminating") seen
    // when the host is started again inside the same process - an Activity being
    // recreated, or the firmware stopping on its own without stop() being called.
    if (g_thread.joinable()) g_thread.join();

    g_stopRequested = false;
    g_started = true;
    g_thread = std::thread(firmwareMain, sdPath, settingsPath);
    return true;
}

void stop() {
    if (!g_started) return;

    LOGI("firmware: simuStop");
    g_stopRequested = true;
    simuStop();

    if (g_thread.joinable()) g_thread.join();
    g_started = false;
}

bool running() { return g_started && !g_stopRequested; }

bool takeFrame(uint8_t* dst, uint32_t dstLen) {
    if (!simuLcdChanged()) return false;

    // Called from the activity thread while a window is attached and from the link
    // thread while none is (see native_main.cpp); the scratch buffer below is shared.
    std::lock_guard<std::mutex> lock(g_frameMutex);

    const LcdInfo info = lcdInfo();
    const uint32_t frameBytes =
        static_cast<uint32_t>(info.width) * static_cast<uint32_t>(info.height) * 2u;
    if (frameBytes == 0 || dst == nullptr || dstLen < frameBytes) {
        simuLcdFlushed();
        return false;
    }

    if (g_frame.size() < frameBytes) g_frame.resize(frameBytes);
    simuLcdCopy(g_frame.data(), frameBytes);
    std::memcpy(dst, g_frame.data(), frameBytes);

    simuLcdFlushed();
    return true;
}

void touchDown(int16_t x, int16_t y) { simuTouchDown(x, y); }

void touchUp() { simuTouchUp(); }

void pushAnalog(uint8_t idx, uint16_t value) { edgetxAndroidSetAnalog(idx, value); }

void setAnalogSource(bool external) {
    edgetxAndroidSetAnalogExternal(external ? 1 : 0);
}

void setKey(uint8_t key, bool down) { simuSetKey(key, down); }
void setSwitch(uint8_t index, int8_t state) {
    simuSetSwitch(index, state);

    // Hand it to the library as well: the boot resets every switch to "up" (twice - see
    // the switch driver's boardInitSwitches()), and that is where the values the app has
    // already sent get put back from.
    if (edgetxAndroidSetSwitch != nullptr) edgetxAndroidSetSwitch(index, state);
}
void setTrim(uint8_t trim, bool state) { simuSetTrim(trim, state); }
void rotaryEncoderEvent(int32_t steps) { simuRotaryEncoderEvent(steps); }

// VBAT's position in the board's ADC input list (radio/src/boards/hw_defs/
// tx16smk3.json): LH, LV, RV, RH, P1, P2, SL1, SL2, EXT1, EXT2, VBAT, RTC_BAT, LUX.
constexpr uint8_t kVBatChannel = 10;

std::atomic<uint16_t> g_batteryMv{0};
std::atomic<uint8_t> g_batteryPercent{0};
std::atomic<bool> g_batteryCharging{false};

void setGps(int32_t latitude, int32_t longitude, int32_t altitude, uint16_t speed,
            uint16_t course, uint8_t satellites, bool fix) {
    if (edgetxAndroidSetGps == nullptr) {
        // An older simulator library: say so once rather than pretend the position is visible.
        static bool warned = false;
        if (!warned) {
            warned = true;
            LOGE("gps: the simulator library has no GPS injection");
        }
        return;
    }
    edgetxAndroidSetGps(latitude, longitude, altitude, speed, course, satellites, fix ? 1 : 0);
}

uint32_t hapticEvents() {
    return simuGetHaptic != nullptr ? simuGetHaptic() : 0;
}

bool firmwareGps(int32_t* latitude, int32_t* longitude, int* satellites, bool* fix) {
    if (edgetxAndroidGetGps == nullptr) return false;

    int32_t lat = 0;
    int32_t lon = 0;
    uint8_t sats = 0;
    uint8_t has = 0;
    edgetxAndroidGetGps(&lat, &lon, &sats, &has);
    if (latitude) *latitude = lat;
    if (longitude) *longitude = lon;
    if (satellites) *satellites = sats;
    if (fix) *fix = has != 0;
    return true;
}

void setBattery(uint16_t millivolts, uint8_t percent, bool charging) {
    g_batteryMv = millivolts;
    g_batteryPercent = percent;
    g_batteryCharging = charging;

    // The firmware's battery and charger paths read this (see the simulator's
    // adc_driver.cpp and led_driver.cpp), and the ADC channel is set as well because
    // that is where the value ends up once the driver prefers the host's reading.
    //
    // This arrives from Application.onCreate, before the firmware exists, so nothing
    // here may call into the firmware - see logFirmwareBattery() for that.
    if (edgetxAndroidSetBattery != nullptr) {
        edgetxAndroidSetBattery(millivolts, charging ? 1 : 0);
    } else {
        static bool warned = false;
        if (!warned) {
            warned = true;
            LOGE("battery: the simulator library has no battery injection");
        }
    }
    if (millivolts > 0) {
        edgetxAndroidSetAnalog(kVBatChannel, static_cast<uint16_t>(millivolts / 5));
    }
}

uint32_t takeAudio(uint8_t* dst, uint32_t maxLen) {
    return edgetxAndroidTakeAudio(dst, maxLen);
}
uint32_t audioSampleRate() { return edgetxAndroidAudioSampleRate(); }
uint32_t audioWrittenBytes() { return edgetxAndroidAudioWrittenBytes(); }
uint32_t audioDroppedBytes() { return edgetxAndroidAudioDroppedBytes(); }

// Bytes handed to AudioTrack that have not reached the speaker yet (see RcAudio.java).
// The firmware keeps its own copy (it drives the boot splash); this is the host-side
// mirror, so both sides can be told apart when logging.
std::atomic<uint32_t> g_audioPending{0};

void setAudioPending(uint32_t bytes) {
    g_audioPending.store(bytes);
    if (edgetxAndroidSetHostAudioPending != nullptr) {
        edgetxAndroidSetHostAudioPending(bytes);
    }
}

uint32_t audioPendingBytes() { return g_audioPending.load(); }

uint8_t externalModuleType() {
    return edgetxAndroidExternalModuleType != nullptr ? edgetxAndroidExternalModuleType() : 0;
}

bool firmwareRunning() {
    return simuIsRunning != nullptr && simuIsRunning();
}

void requestFullRefresh() {
    // The firmware's lcdRequestFullRefresh() walks LVGL, which only exists once the
    // firmware has started. Called before that it invalidates a null screen and takes
    // the process down - measured on the RC Pro: SIGSEGV inside lv_obj_invalidate,
    // from a window attach that ran while the firmware was not up yet. The firmware's
    // own flag is the gate rather than the link's, because simuStart() sets it last:
    // "link down" in the log only means the app is not sure, this means it is not up.
    if (!firmwareRunning()) {
        LOGW("link: full refresh skipped, the firmware has not started");
        return;
    }
    LOGI("link: full refresh request (firmware symbol %s)",
         lcdRequestFullRefresh != nullptr ? "resolved" : "MISSING");
    if (lcdRequestFullRefresh != nullptr) {
        lcdRequestFullRefresh();
    }
}

void logFirmwareBattery() {
    // Only valid once the firmware is up: adcGetMaxInputs() walks tables that do not
    // exist before simuInit(), and calling it earlier is a null dereference.
    // getBatteryVoltage() is in 10 mV steps (see battery_driver.h), so volts = raw/100.
    const uint16_t raw = getBatteryVoltage();
    const uint16_t tenths = static_cast<uint16_t>(raw / 10);
    const uint16_t hostSeen =
        edgetxAndroidBatteryMillivolts != nullptr ? edgetxAndroidBatteryMillivolts() : 0;

    // Radio Setup -> Alarms (warning) and -> Hardware (the range that scales the %).
    // Worth logging: the range does not drive the low-voltage warning, and the two are
    // easy to confuse when the pack sits just above the default 7.4 V threshold.
    uint16_t warn = 0, batMin = 0, batMax = 0;
    if (edgetxAndroidBatterySettings != nullptr)
        edgetxAndroidBatterySettings(&warn, &batMin, &batMax);

    LOGI("battery: rc %u mV %u%% %s | firmware %u.%u V, warn %u.%u V, range %u.%u-%u.%u V, "
         "charger %d | firmware-side host mV %u",
         g_batteryMv.load(), g_batteryPercent.load(),
         g_batteryCharging.load() ? "charging" : "not charging",
         static_cast<unsigned>(tenths / 10), static_cast<unsigned>(tenths % 10),
         static_cast<unsigned>(warn / 10), static_cast<unsigned>(warn % 10),
         static_cast<unsigned>(batMin / 10), static_cast<unsigned>(batMin % 10),
         static_cast<unsigned>(batMax / 10), static_cast<unsigned>(batMax % 10),
         usbChargerLed() ? 1 : 0, static_cast<unsigned>(hostSeen));
}

void logFirmwareSwitches() {
    // Read back through the firmware's own switch driver (radio/src/hal/switch_driver.h):
    // switchState(3 * index + position) answers true for the position a switch is in, with
    // 0 = up, 1 = middle, 2 = down. Weak, so an older simulator library still loads.
    if (switchState == nullptr) return;

    std::string line;
    for (uint8_t i = 0; i < 10; i++) {   // SA..SJ, the board's switch table
        const char* position = switchState(i * 3 + 0) ? "up"
                             : switchState(i * 3 + 1) ? "mid"
                             : switchState(i * 3 + 2) ? "down"
                                                      : "?";
        if (!line.empty()) line += ' ';
        line += 'S';
        line += static_cast<char>('A' + i);
        line += '=';
        line += position;
    }

    LOGI("switches: firmware sees %s", line.c_str());
}

}  // namespace simu
