#include "simu_host.h"

#include <atomic>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>

#include "log.h"

namespace simu {

namespace {

std::thread g_thread;
std::atomic<bool> g_started{false};
std::atomic<bool> g_stopRequested{false};

LcdInfo g_lcd;
std::vector<uint8_t> g_frame;  // scratch buffer for simuLcdCopy()

void firmwareMain(const char* sdPath, const char* settingsPath) {
    LOGI("firmware: simuInit");
    simuInit();

    simuFatfsSetPaths(sdPath, settingsPath);

    // First run: let the firmware create radio.yml / model defaults.
    simuCreateDefaults();

    LOGI("firmware: simuStart (sd=%s settings=%s)", sdPath, settingsPath);
    // tests=false -> OPENTX_START_NO_SPLASH | NO_CALIBRATION | NO_CHECKS, i.e.
    // boot straight into the UI instead of stopping on the startup warnings.
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
void setSwitch(uint8_t index, int8_t state) { simuSetSwitch(index, state); }

}  // namespace simu
