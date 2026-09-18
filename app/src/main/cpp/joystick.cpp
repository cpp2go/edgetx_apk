#include "joystick.h"

#include <android/keycodes.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <fstream>
#include <map>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "log.h"
#include "simu_host.h"

namespace joystick {
namespace {

// --------------------------------------------------------------- EdgeTX keys --
//
// Values from radio/src/hal/key_driver.h (enum EnumKeys).
//
// EnumKeys also holds UP, DOWN, LEFT and RIGHT, and on the mono radios
// EVT_KEY_FIRST(KEY_LEFT/RIGHT) is exactly equivalent to the rotary encoder
// turning - but that equivalence lives under NAVIGATION_9X / NAVIGATION_XLITE
// (gui/navigation/common.cpp), which TX16SMK3 does not use. Its colour LCD GUI
// reacts to seven keys:
//
//     RTN  ENTER  PAGEUP  PAGEDN  MODEL  TELE  SYS
//
// RTN is what the radio prints on that key; EdgeTX calls it EXIT internally
// (boards/hw_defs/tx16smk3.json: KEY_EXIT, "label": "RTN"), and the Android key
// behind the remote's return button is AKEYCODE_BACK. Both names are accepted in
// the key map file, and RTN is the one used in the log - so what is pressed on the
// remote and what the log says line up.
//
// UP and DOWN are not produced by anything: they are consumed only by the
// monochrome UIs (gui/128x64, navigation_9x) and by Lua, so nothing in
// gui/colorlcd would react to them. LEFT/RIGHT are worse still, because
// LvglWrapper's evt_to_indev_data() forwards only ENTER and EXIT into LVGL. There
// is no "step to the next field" key on this target - its navigation control is
// the rotary encoder, which the 5-way's up/down drives (see
// simu::rotaryEncoderEvent()). The two dials are left to the P1/P2 inputs, where
// they can be given a job of their own (Volume, Backlight - see requestDial).
constexpr uint8_t kKeyExit = 1;
constexpr uint8_t kKeyEnter = 2;
constexpr uint8_t kKeyPageUp = 3;
constexpr uint8_t kKeyPageDn = 4;
constexpr uint8_t kKeyUp = 5;
constexpr uint8_t kKeyDown = 6;
constexpr uint8_t kKeyModel = 11;
constexpr uint8_t kKeyTele = 12;
constexpr uint8_t kKeySys = 13;

// ------------------------------------------------------------------- axes ----
//
// EdgeTX simulator analog channel order - see targets/simu/adc_driver.cpp and
// the SDL reference implementation in targets/simu/sdl_simu.cpp:
//
//   0 left stick X (rudder)              1 left stick Y (throttle, inverted)
//   2 right stick Y (elevator, inverted) 3 right stick X (aileron)
//   4..                                   flex inputs (sliders / pots)
//
// Each channel lists the Android axes it accepts, most specific first. Only
// axes the device actually declares are used, and an axis is claimed by at most
// one channel, so a controller that exposes extra dials on Z/RZ/GENERIC_n gets
// them routed to the pot channels while a plain gamepad still works.
constexpr int32_t kNoAxis = -1;
constexpr int32_t kMaxAxis = 64;
constexpr uint8_t kAnalogSlots = 10;

struct AxisChoice {
    uint8_t analog;
    int32_t axis[3];
    bool invert;
};

const AxisChoice kAxisCandidates[] = {
    {0, {AMOTION_EVENT_AXIS_X, AMOTION_EVENT_AXIS_RUDDER, kNoAxis}, false},
    {1, {AMOTION_EVENT_AXIS_Y, AMOTION_EVENT_AXIS_THROTTLE, kNoAxis}, true},
    {2, {AMOTION_EVENT_AXIS_RY, kNoAxis, kNoAxis}, true},
    {3, {AMOTION_EVENT_AXIS_RX, kNoAxis, kNoAxis}, false},
    // flex inputs (sliders, dials, gimbal wheels)
    {4, {AMOTION_EVENT_AXIS_Z, AMOTION_EVENT_AXIS_BRAKE, kNoAxis}, false},
    {5, {AMOTION_EVENT_AXIS_RZ, AMOTION_EVENT_AXIS_GAS, kNoAxis}, false},
    {6, {AMOTION_EVENT_AXIS_GENERIC_1, AMOTION_EVENT_AXIS_THROTTLE, kNoAxis}, false},
    {7, {AMOTION_EVENT_AXIS_GENERIC_2, AMOTION_EVENT_AXIS_RUDDER, kNoAxis}, false},
    {8, {AMOTION_EVENT_AXIS_GENERIC_3, AMOTION_EVENT_AXIS_WHEEL, kNoAxis}, false},
    {9, {AMOTION_EVENT_AXIS_GENERIC_4, kNoAxis, kNoAxis}, false},
};

struct AxisRange {
    bool present = false;
    float min = -1.0f;
    float max = 1.0f;
};

struct AnalogRoute {
    int32_t axis = kNoAxis;
    bool invert = false;
};

struct DeviceInfo {
    AxisRange axes[kMaxAxis];
    AnalogRoute route[kAnalogSlots];
    bool resolved = false;
};

// ---------------------------------------------------------------- buttons ----
//
// Default mapping for a standard gamepad. Every unmapped button is logged with
// its Android key code, so the table can be adjusted for a device whose
// buttons report something unexpected.
struct KeyChoice {
    int32_t keycode;
    uint8_t key;
};

// Only what the DJI RC Plus 2 needs. Everything else is handled elsewhere: the
// 5-way directions and the go-home button come from the DJI SDK (see
// DjiMsdkBridge -> nativeOnDjiButton), because the RC firmware never dispatches
// their events to apps.
//
// The RC's right-hand shoulder buttons are EdgeTX keys rather than switches, so
// that a single press opens the setup, model and telemetry pages - the same
// thing the hardware TX16S does with its SYS/MDL/TELE buttons, which this remote
// does not have. The DJI SDK's own C1/C2/C3 custom-button event is not usable
// here: the RcCustomButtonEvent listener never fires on this remote, so the
// buttons are taken from the Android key codes the kernel reports for them.
const KeyChoice kKeyMap[] = {
    {AKEYCODE_BACK, kKeyExit},            // the remote's RTN (return) button
    {AKEYCODE_BUTTON_THUMBL, kKeyEnter},  // 5-way centre press
    {AKEYCODE_F1, kKeySys},               // L1 -> SYS  (radio setup)
    {AKEYCODE_F2, kKeyModel},             // L2 -> MDL  (model setup)
    {AKEYCODE_F3, kKeyTele},              // L3 -> TELE (telemetry)
};

// ------------------------------------------------------------------ state ----
std::mutex g_lock;
std::map<int32_t, DeviceInfo> g_devices;
ANativeActivity* g_activity = nullptr;
bool g_present = false;
std::vector<int32_t> g_loggedAxes;   // deviceId<<8 | axis, logged once each
std::vector<int32_t> g_loggedKeys;   // android key codes already reported
std::vector<int32_t> g_loggedMappedKeys;  // mapped android key codes already reported
std::vector<int32_t> g_loggedSources;  // deviceId ^ source, logged once each
std::vector<int32_t> g_dumpedDevices;  // devices whose axes were dumped once

bool is_joystick_source(int32_t source) {
    if ((source & AINPUT_SOURCE_CLASS_MASK) == AINPUT_SOURCE_CLASS_JOYSTICK) return true;
    // SOURCE_GAMEPAD is a multi-bit constant, so test for all of its bits.
    return (source & AINPUT_SOURCE_GAMEPAD) == AINPUT_SOURCE_GAMEPAD;
}

const char* axis_name(int32_t axis) {
    switch (axis) {
        case AMOTION_EVENT_AXIS_X: return "X";
        case AMOTION_EVENT_AXIS_Y: return "Y";
        case AMOTION_EVENT_AXIS_Z: return "Z";
        case AMOTION_EVENT_AXIS_RX: return "RX";
        case AMOTION_EVENT_AXIS_RY: return "RY";
        case AMOTION_EVENT_AXIS_RZ: return "RZ";
        case AMOTION_EVENT_AXIS_HAT_X: return "HAT_X";
        case AMOTION_EVENT_AXIS_HAT_Y: return "HAT_Y";
        case AMOTION_EVENT_AXIS_THROTTLE: return "THROTTLE";
        case AMOTION_EVENT_AXIS_RUDDER: return "RUDDER";
        case AMOTION_EVENT_AXIS_GAS: return "GAS";
        case AMOTION_EVENT_AXIS_BRAKE: return "BRAKE";
        case AMOTION_EVENT_AXIS_WHEEL: return "WHEEL";
        case AMOTION_EVENT_AXIS_GENERIC_1: return "G1";
        case AMOTION_EVENT_AXIS_GENERIC_2: return "G2";
        case AMOTION_EVENT_AXIS_GENERIC_3: return "G3";
        case AMOTION_EVENT_AXIS_GENERIC_4: return "G4";
        default: return "?";
    }
}

const char* key_name(int32_t keycode) {
    switch (keycode) {
        case AKEYCODE_BUTTON_A: return "BUTTON_A";
        case AKEYCODE_BUTTON_B: return "BUTTON_B";
        case AKEYCODE_BUTTON_C: return "BUTTON_C";
        case AKEYCODE_BUTTON_X: return "BUTTON_X";
        case AKEYCODE_BUTTON_Y: return "BUTTON_Y";
        case AKEYCODE_BUTTON_Z: return "BUTTON_Z";
        case AKEYCODE_BUTTON_L1: return "BUTTON_L1";
        case AKEYCODE_BUTTON_R1: return "BUTTON_R1";
        case AKEYCODE_BUTTON_L2: return "BUTTON_L2";
        case AKEYCODE_BUTTON_R2: return "BUTTON_R2";
        case AKEYCODE_BUTTON_THUMBL: return "BUTTON_THUMBL";
        case AKEYCODE_BUTTON_THUMBR: return "BUTTON_THUMBR";
        case AKEYCODE_BUTTON_START: return "BUTTON_START";
        case AKEYCODE_BUTTON_SELECT: return "BUTTON_SELECT";
        case AKEYCODE_BUTTON_MODE: return "BUTTON_MODE";
        case AKEYCODE_DPAD_UP: return "DPAD_UP";
        case AKEYCODE_DPAD_DOWN: return "DPAD_DOWN";
        case AKEYCODE_DPAD_LEFT: return "DPAD_LEFT";
        case AKEYCODE_DPAD_RIGHT: return "DPAD_RIGHT";
        case AKEYCODE_DPAD_CENTER: return "DPAD_CENTER";
        case AKEYCODE_ENTER: return "ENTER";
        case AKEYCODE_ESCAPE: return "ESCAPE";
        case AKEYCODE_BACK: return "BACK";
        case AKEYCODE_MENU: return "MENU";
        case AKEYCODE_VOLUME_UP: return "VOLUME_UP";
        case AKEYCODE_VOLUME_DOWN: return "VOLUME_DOWN";
        case AKEYCODE_CAMERA: return "CAMERA";
        case AKEYCODE_F1: return "F1";
        case AKEYCODE_F2: return "F2";
        case AKEYCODE_F3: return "F3";
        case AKEYCODE_F4: return "F4";
        case AKEYCODE_F5: return "F5";
        case AKEYCODE_F6: return "F6";
        case AKEYCODE_F7: return "F7";
        case AKEYCODE_F8: return "F8";
        case AKEYCODE_F9: return "F9";
        case AKEYCODE_F10: return "F10";
        case AKEYCODE_F11: return "F11";
        case AKEYCODE_F12: return "F12";
        default: return "?";
    }
}

// EdgeTX 键名，只在日志里用。
const char* etkey_name(uint8_t key) {
    switch (key) {
        case kKeyExit: return "RTN";
        case kKeyEnter: return "ENTER";
        case kKeyPageUp: return "PAGEUP";
        case kKeyPageDn: return "PAGEDN";
        case kKeyUp: return "UP";
        case kKeyDown: return "DOWN";
        case kKeyModel: return "MODEL";
        case kKeyTele: return "TELE";
        case kKeySys: return "SYS";
        default: return "?";
    }
}

// ----------------------------------------------------------- key map file ----
//
// The table above is only the default. A plain text file in the app's external
// data directory replaces it, so the mapping can be tuned without rebuilding:
//
//     /sdcard/Android/data/com.edgetx.droidui/files/joystick.keys
//
// One "<android key> = <EdgeTX key>" per line, '#' starts a comment:
//
//     F1 = PAGEUP
//     BUTTON_THUMBL = ENTER
//
// The Android key name is the one logcat prints, e.g.
//   joystick: button keycode 131 (F1) -> EdgeTX PAGEUP
// EdgeTX keys: RTN ENTER PAGEUP PAGEDN MODEL TELE SYS
// (RTN is the key the radio prints on its return button; EXIT is accepted as well,
// both here and in the log, because that is EdgeTX's own name for it.)
//
// The app writes a template containing the built-in defaults on first run. To
// change it, edit that file (or push a new one) and restart the app:
//
//     adb pull /sdcard/Android/data/com.edgetx.droidui/files/joystick.keys .
//     ... edit ...
//     adb push joystick.keys /sdcard/Android/data/com.edgetx.droidui/files/

struct RuntimeKey {
    int32_t keycode;
    uint8_t key;
};

std::vector<RuntimeKey> g_runtimeKeys;
bool g_keyMapLoaded = false;

struct NamedId {
    const char* name;
    int32_t id;
};

const NamedId kAndroidKeyNames[] = {
    {"F1", AKEYCODE_F1},   {"F2", AKEYCODE_F2},   {"F3", AKEYCODE_F3},
    {"F4", AKEYCODE_F4},   {"F5", AKEYCODE_F5},   {"F6", AKEYCODE_F6},
    {"F7", AKEYCODE_F7},   {"F8", AKEYCODE_F8},   {"F9", AKEYCODE_F9},
    {"F10", AKEYCODE_F10}, {"F11", AKEYCODE_F11}, {"F12", AKEYCODE_F12},
    {"DPAD_UP", AKEYCODE_DPAD_UP},       {"DPAD_DOWN", AKEYCODE_DPAD_DOWN},
    {"DPAD_LEFT", AKEYCODE_DPAD_LEFT},   {"DPAD_RIGHT", AKEYCODE_DPAD_RIGHT},
    {"DPAD_CENTER", AKEYCODE_DPAD_CENTER},
    {"ENTER", AKEYCODE_ENTER}, {"BACK", AKEYCODE_BACK}, {"ESCAPE", AKEYCODE_ESCAPE},
    {"BUTTON_A", AKEYCODE_BUTTON_A}, {"BUTTON_B", AKEYCODE_BUTTON_B},
    {"BUTTON_X", AKEYCODE_BUTTON_X}, {"BUTTON_Y", AKEYCODE_BUTTON_Y},
    {"BUTTON_L1", AKEYCODE_BUTTON_L1}, {"BUTTON_R1", AKEYCODE_BUTTON_R1},
    {"BUTTON_L2", AKEYCODE_BUTTON_L2}, {"BUTTON_R2", AKEYCODE_BUTTON_R2},
    {"BUTTON_THUMBL", AKEYCODE_BUTTON_THUMBL}, {"BUTTON_THUMBR", AKEYCODE_BUTTON_THUMBR},
    {"BUTTON_START", AKEYCODE_BUTTON_START}, {"BUTTON_SELECT", AKEYCODE_BUTTON_SELECT},
    {"BUTTON_MODE", AKEYCODE_BUTTON_MODE},
};

const NamedId kEtKeyNames[] = {
    {"RTN", kKeyExit},      {"EXIT", kKeyExit},  {"ENTER", kKeyEnter},
    {"PAGEUP", kKeyPageUp}, {"PAGEDN", kKeyPageDn},
    {"UP", kKeyUp},         {"DOWN", kKeyDown},
    {"MODEL", kKeyModel},   {"TELE", kKeyTele},  {"SYS", kKeySys},
};

std::string trim(const std::string& s) {
    const char* ws = " \t\r\n";
    const size_t b = s.find_first_not_of(ws);
    if (b == std::string::npos) return std::string();
    const size_t e = s.find_last_not_of(ws);
    return s.substr(b, e - b + 1);
}

int32_t android_keycode_by_name(const std::string& name) {
    for (const NamedId& n : kAndroidKeyNames) {
        if (name == n.name) return n.id;
    }
    if (!name.empty() && isdigit(static_cast<unsigned char>(name[0]))) {
        return static_cast<int32_t>(strtol(name.c_str(), nullptr, 10));
    }
    return -1;
}

const char* android_keycode_name(int32_t keycode) {
    for (const NamedId& n : kAndroidKeyNames) {
        if (n.id == keycode) return n.name;
    }
    return nullptr;
}

uint8_t etkey_by_name(const std::string& name) {
    for (const NamedId& n : kEtKeyNames) {
        if (name == n.name) return static_cast<uint8_t>(n.id);
    }
    return 0;
}

std::string key_map_path(ANativeActivity* activity) {
    if (activity == nullptr || activity->externalDataPath == nullptr) return std::string();
    return std::string(activity->externalDataPath) + "/joystick.keys";
}

// Seed the file with the built-in defaults so it is obvious what to edit.
void write_key_map_template(const std::string& path) {
    std::ofstream out(path.c_str(), std::ios::trunc);
    if (!out) return;
    out << "# Which EdgeTX key each controller button drives.\n"
        << "# One \"<android key> = <EdgeTX key>\" per line, '#' starts a comment.\n"
        << "# The android key name is what logcat prints, e.g.\n"
        << "#   joystick: button keycode 4 (BACK) -> EdgeTX RTN\n"
        << "# EdgeTX keys: RTN ENTER PAGEUP PAGEDN MODEL TELE SYS\n"
        << "# (RTN is what the radio prints on its return button; EXIT works too.)\n"
        << "# Edit this file, then restart the app.\n\n";
    for (const KeyChoice& c : kKeyMap) {
        const char* kn = android_keycode_name(c.keycode);
        if (kn != nullptr) out << kn << " = " << etkey_name(c.key) << "\n";
    }
}

void load_key_map(ANativeActivity* activity) {
    if (g_keyMapLoaded) return;
    g_keyMapLoaded = true;

    const std::string path = key_map_path(activity);
    if (path.empty()) return;

    std::ifstream in(path.c_str());
    if (!in) {
        LOGI("joystick: no key map at %s, using the built-in mapping", path.c_str());
        write_key_map_template(path);
        return;
    }

    std::string line;
    while (std::getline(in, line)) {
        const size_t hash = line.find('#');
        if (hash != std::string::npos) line.erase(hash);
        const size_t eq = line.find('=');
        if (eq == std::string::npos) continue;

        const std::string left = trim(line.substr(0, eq));
        const std::string right = trim(line.substr(eq + 1));
        if (left.empty() || right.empty()) continue;

        const int32_t keycode = android_keycode_by_name(left);
        const uint8_t key = etkey_by_name(right);
        if (keycode < 0 || key == 0) {
            LOGW("joystick: ignoring key map line \"%s = %s\"", left.c_str(), right.c_str());
            continue;
        }
        g_runtimeKeys.push_back(RuntimeKey{keycode, key});
    }

    if (g_runtimeKeys.empty()) {
        LOGW("joystick: %s has no usable entries, using the built-in mapping", path.c_str());
    } else {
        LOGI("joystick: loaded %u mapping(s) from %s", (unsigned)g_runtimeKeys.size(),
             path.c_str());
    }
}

// Runtime file wins outright; otherwise fall back to the built-in table.
bool lookup_key(int32_t keycode, uint8_t* key) {
    if (!g_runtimeKeys.empty()) {
        for (const RuntimeKey& rk : g_runtimeKeys) {
            if (rk.keycode == keycode) {
                *key = rk.key;
                return true;
            }
        }
        return false;
    }
    for (const KeyChoice& c : kKeyMap) {
        if (c.keycode == keycode) {
            *key = c.key;
            return true;
        }
    }
    return false;
}

// Claim each declared axis for one EdgeTX channel, in candidate order. Axes the
// device does not declare are skipped, and an axis is never used twice - so a
// transmitter reporting both X/Y and RUDDER/THROTTLE does not end up driving
// the sticks and the dials from the same physical control.
void resolve_routes(DeviceInfo& info) {
    uint64_t used = 0;
    for (const AxisChoice& choice : kAxisCandidates) {
        if (choice.analog >= kAnalogSlots) continue;
        for (int32_t axis : choice.axis) {
            if (axis == kNoAxis) break;
            if (!info.axes[axis].present) continue;
            if (used & (1ULL << axis)) continue;
            info.route[choice.analog].axis = axis;
            info.route[choice.analog].invert = choice.invert;
            used |= (1ULL << axis);
            break;
        }
    }
    info.resolved = true;
}

bool range_of(const DeviceInfo& info, int32_t axis, AxisRange* out) {
    if (axis < 0 || axis >= kMaxAxis) return false;
    if (!info.axes[axis].present) return false;
    *out = info.axes[axis];
    return true;
}

// Android axes are floats within the range the device declares. EdgeTX wants
// 0..4096 with centre 2048.
uint16_t to_analog(float value, const AxisRange& range, bool invert) {
    const float span = range.max - range.min;
    float n = (span > 0.0f) ? (value - range.min) / span : 0.5f;
    if (invert) n = 1.0f - n;
    if (n < 0.0f) n = 0.0f;
    if (n > 1.0f) n = 1.0f;

    int32_t out = static_cast<int32_t>(n * 4096.0f + 0.5f);
    if (out < 0) out = 0;
    if (out > 4096) out = 4096;
    return static_cast<uint16_t>(out);
}

void log_axis_once(int32_t deviceId, int32_t axis) {
    const int32_t tag = (deviceId << 8) | (axis & 0xFF);
    if (std::find(g_loggedAxes.begin(), g_loggedAxes.end(), tag) != g_loggedAxes.end()) return;
    g_loggedAxes.push_back(tag);
    LOGI("joystick: device %d reported axis %s (0x%x)", deviceId, axis_name(axis), axis);
}

void log_key_once(int32_t keycode) {
    if (std::find(g_loggedKeys.begin(), g_loggedKeys.end(), keycode) != g_loggedKeys.end()) return;
    g_loggedKeys.push_back(keycode);
    LOGI("joystick: unmapped button keycode %d (%s)", keycode, key_name(keycode));
}

// Mapped buttons are otherwise silent, which makes it impossible to tell from
// logcat whether a physical button reached the app and what it drove. Report
// each keycode once, with both its Android name and the EdgeTX key it maps to.
void log_mapped_key_once(int32_t keycode, uint8_t key) {
    if (std::find(g_loggedMappedKeys.begin(), g_loggedMappedKeys.end(), keycode) !=
        g_loggedMappedKeys.end()) {
        return;
    }
    g_loggedMappedKeys.push_back(keycode);
    LOGI("joystick: button keycode %d (%s) -> EdgeTX %s", keycode, key_name(keycode),
         etkey_name(key));
}

// Report every distinct device/source pair once. This is what proves whether a
// vendor remote reports its controls to Android at all, and under which class.
void log_source_once(int32_t deviceId, int32_t source) {
    const int32_t tag = (deviceId << 16) ^ (source & 0xFFFF);
    if (std::find(g_loggedSources.begin(), g_loggedSources.end(), tag) != g_loggedSources.end()) return;
    g_loggedSources.push_back(tag);
    LOGI("joystick: motion from device %d source=0x%08x (class=0x%02x)", deviceId,
         source, source & AINPUT_SOURCE_CLASS_MASK);
}

// Dump the live value of every axis the device declares, once per device. The
// values are Android raw (usually -1..1), which is exactly what is needed to
// work out a mapping for an unknown controller.
void dump_axes_once(int32_t deviceId, AInputEvent* event, const DeviceInfo& info) {
    if (std::find(g_dumpedDevices.begin(), g_dumpedDevices.end(), deviceId) != g_dumpedDevices.end()) return;
    g_dumpedDevices.push_back(deviceId);

    char buf[512];
    buf[0] = '\0';
    for (int32_t a = 0; a < kMaxAxis; ++a) {
        if (!info.axes[a].present) continue;
        const float v = AMotionEvent_getAxisValue(event, a, 0);
        snprintf(buf + strlen(buf), sizeof(buf) - strlen(buf), "%s%s=%.3f",
                 buf[0] ? " " : "", axis_name(a), v);
    }
    LOGI("joystick: device %d raw axes: %s", deviceId, buf[0] ? buf : "(none declared)");
}

// The sticks can only be read while they move: Android stops sending motion
// events once a value settles, and a device that never sends any is
// indistinguishable from a stick held at rest. A throttled dump of what is
// actually handed to the firmware is therefore the quickest way to confirm real
// stick data reaches EdgeTX - and to spot an inverted axis. 2 Hz.
void log_analog_throttled(AInputEvent* event, const DeviceInfo& info) {
    using clock = std::chrono::steady_clock;
    static clock::time_point last{};
    const clock::time_point now = clock::now();
    if (last != clock::time_point{} && now - last < std::chrono::milliseconds(500)) return;
    last = now;

    char buf[320];
    buf[0] = '\0';
    for (uint8_t ch = 0; ch < kAnalogSlots; ++ch) {
        const AnalogRoute& route = info.route[ch];
        if (route.axis == kNoAxis) continue;

        AxisRange range;
        if (!range_of(info, route.axis, &range)) continue;

        const float raw = AMotionEvent_getAxisValue(event, route.axis, 0);
        snprintf(buf + strlen(buf), sizeof(buf) - strlen(buf), "%sA%u(%s)=%.3f->%u",
                 buf[0] ? " " : "", ch, axis_name(route.axis), raw,
                 to_analog(raw, range, route.invert));
    }
    if (buf[0] != '\0') LOGI("joystick: analog %s", buf);
}

}  // namespace

void init(ANativeActivity* activity) {
    if (activity == nullptr || activity->vm == nullptr) return;
    g_activity = activity;
    load_key_map(activity);

    // The sticks come from the DJI SDK instead (see nativeOnDjiStick): the RC
    // firmware never dispatches their MotionEvents to Android, and this process
    // may not open /dev/input/event4 to read them from the kernel either - it is
    // root:input mode 0660, and the only permission that grants that group,
    // android.permission.DIAGNOSTIC, is signature-level.

    JavaVM* vm = activity->vm;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGW("joystick: cannot attach to the JVM");
            return;
        }
        attached = true;
    }

    jclass deviceCls = env->FindClass("android/view/InputDevice");
    jclass rangeCls = env->FindClass("android/view/InputDevice$MotionRange");
    jclass listCls = env->FindClass("java/util/List");

    jmethodID getIds = nullptr;
    jmethodID getDev = nullptr;
    jmethodID getName = nullptr;
    jmethodID getSources = nullptr;
    jmethodID getRanges = nullptr;
    jmethodID rangeAxis = nullptr;
    jmethodID rangeMin = nullptr;
    jmethodID rangeMax = nullptr;
    jmethodID listSize = nullptr;
    jmethodID listGet = nullptr;

    if (deviceCls != nullptr) {
        getIds = env->GetStaticMethodID(deviceCls, "getDeviceIds", "()[I");
        getDev = env->GetStaticMethodID(deviceCls, "getDevice",
                                        "(I)Landroid/view/InputDevice;");
        getName = env->GetMethodID(deviceCls, "getName", "()Ljava/lang/String;");
        getSources = env->GetMethodID(deviceCls, "getSources", "()I");
        getRanges = env->GetMethodID(deviceCls, "getMotionRanges", "()Ljava/util/List;");
    }
    if (rangeCls != nullptr) {
        rangeAxis = env->GetMethodID(rangeCls, "getAxis", "()I");
        rangeMin = env->GetMethodID(rangeCls, "getMin", "()F");
        rangeMax = env->GetMethodID(rangeCls, "getMax", "()F");
    }
    if (listCls != nullptr) {
        listSize = env->GetMethodID(listCls, "size", "()I");
        listGet = env->GetMethodID(listCls, "get", "(I)Ljava/lang/Object;");
    }

    if (getIds == nullptr || getDev == nullptr || getName == nullptr ||
        getSources == nullptr || getRanges == nullptr || rangeAxis == nullptr ||
        rangeMin == nullptr || rangeMax == nullptr || listSize == nullptr ||
        listGet == nullptr) {
        LOGE("joystick: InputDevice reflection failed");
    } else {
        jintArray ids = static_cast<jintArray>(env->CallStaticObjectMethod(deviceCls, getIds));
        if (ids != nullptr) {
            const jsize count = env->GetArrayLength(ids);
            std::vector<jint> idBuf(count > 0 ? static_cast<size_t>(count) : 0);
            if (count > 0) env->GetIntArrayRegion(ids, 0, count, idBuf.data());
            env->DeleteLocalRef(ids);

            bool anyJoystick = false;
            for (jsize i = 0; i < count; ++i) {
                jobject dev = env->CallStaticObjectMethod(deviceCls, getDev, idBuf[i]);
                if (dev == nullptr) continue;

                jstring jname = static_cast<jstring>(env->CallObjectMethod(dev, getName));
                const char* name = (jname != nullptr) ? env->GetStringUTFChars(jname, nullptr) : nullptr;
                const jint sources = env->CallIntMethod(dev, getSources);

                DeviceInfo info;
                char axisList[192];
                axisList[0] = '\0';

                jobject ranges = env->CallObjectMethod(dev, getRanges);
                if (ranges != nullptr) {
                    const jint n = env->CallIntMethod(ranges, listSize);
                    for (jint k = 0; k < n; ++k) {
                        jobject r = env->CallObjectMethod(ranges, listGet, k);
                        if (r == nullptr) continue;
                        const jint axis = env->CallIntMethod(r, rangeAxis);
                        if (axis >= 0 && axis < kMaxAxis) {
                            info.axes[axis].present = true;
                            info.axes[axis].min = env->CallFloatMethod(r, rangeMin);
                            info.axes[axis].max = env->CallFloatMethod(r, rangeMax);
                            snprintf(axisList + strlen(axisList),
                                     sizeof(axisList) - strlen(axisList), "%s%s",
                                     axisList[0] ? "," : "", axis_name(axis));
                        }
                        env->DeleteLocalRef(r);
                    }
                    env->DeleteLocalRef(ranges);
                }

                const bool joy = is_joystick_source(sources);
                LOGI("input device %d: \"%s\" sources=0x%08x %s axes=[%s]", idBuf[i],
                     name != nullptr ? name : "?", sources, joy ? "JOYSTICK" : "", axisList);

                if (joy) resolve_routes(info);
                {
                    std::lock_guard<std::mutex> lock(g_lock);
                    g_devices[idBuf[i]] = info;
                }
                if (joy) anyJoystick = true;

                if (name != nullptr) env->ReleaseStringUTFChars(jname, name);
                if (jname != nullptr) env->DeleteLocalRef(jname);
                env->DeleteLocalRef(dev);
            }

            if (anyJoystick != g_present) {
                g_present = anyJoystick;
                LOGI("joystick: controller %s",
                     anyJoystick ? "detected, using its sticks and buttons"
                                 : "gone, back to the demo source");
            }
            if (!anyJoystick && g_loggedAxes.empty()) {
                LOGI("joystick: none found - the analog channels keep the demo sine wave");
            }
        }
    }

    if (listCls != nullptr) env->DeleteLocalRef(listCls);
    if (rangeCls != nullptr) env->DeleteLocalRef(rangeCls);
    if (deviceCls != nullptr) env->DeleteLocalRef(deviceCls);

    if (attached) vm->DetachCurrentThread();
}

bool present() { return g_present; }

// ---------------------------------------------------------------------------
// Firmware write serialisation.
//
// Two threads reach the firmware: the link thread applies the queued input in
// tick() (DJI SDK sticks, dials, the 5-way, switches), and the activity's thread
// delivers Android input events. They must not write the firmware's key/analog
// state at the same time, so every write goes through these two helpers.
// ---------------------------------------------------------------------------
std::mutex g_firmwareMutex;

void set_key(uint8_t key, bool down) {
    std::lock_guard<std::mutex> lock(g_firmwareMutex);
    simu::setKey(key, down);
}

void push_analog(uint8_t channel, uint16_t value) {
    std::lock_guard<std::mutex> lock(g_firmwareMutex);
    simu::pushAnalog(channel, value);
    simu::setAnalogSource(true);
}

bool handleMotionEvent(AInputEvent* event) {
    const int32_t source = AInputEvent_getSource(event);

    // Touch (and a stylus, which shares the pointer class) belongs to the
    // touchscreen path.
    if ((source & AINPUT_SOURCE_TOUCHSCREEN) == AINPUT_SOURCE_TOUCHSCREEN) return false;

    const int32_t deviceId = AInputEvent_getDeviceId(event);
    // Learn about any device we have not seen yet, whatever its class: a remote
    // that does not report itself as a joystick must still show up in the log.
    if (!g_devices.count(deviceId) && g_activity != nullptr) init(g_activity);

    log_source_once(deviceId, source);

    if (!is_joystick_source(source)) {
        DeviceInfo other;
        {
            std::lock_guard<std::mutex> lock(g_lock);
            const auto it = g_devices.find(deviceId);
            if (it != g_devices.end()) other = it->second;
        }
        dump_axes_once(deviceId, event, other);
        return false;   // not a controller: leave it for the caller to decide
    }

    const int32_t action = AMotionEvent_getAction(event);
    if ((action & AMOTION_EVENT_ACTION_MASK) == AMOTION_EVENT_ACTION_CANCEL) {
        LOGI("joystick: device %d cancelled", deviceId);
        return true;
    }

    // One snapshot for the whole event (DeviceInfo is ~850 bytes).
    DeviceInfo info;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        const auto it = g_devices.find(deviceId);
        if (it == g_devices.end()) return true;
        info = it->second;
    }

    dump_axes_once(deviceId, event, info);

    bool pushed = false;
    for (const AxisChoice& choice : kAxisCandidates) {
        if (choice.analog >= kAnalogSlots) continue;

        const AnalogRoute& route = info.route[choice.analog];
        if (route.axis == kNoAxis) continue;

        AxisRange range;
        if (!range_of(info, route.axis, &range)) continue;

        log_axis_once(deviceId, route.axis);
        const float value = AMotionEvent_getAxisValue(event, route.axis, 0);
        push_analog(choice.analog, to_analog(value, range, route.invert));
        pushed = true;
    }

    if (pushed) {
        if (!present()) g_present = true;
        log_analog_throttled(event, info);
    }
    return true;
}

// The RC Plus 2's 5-way centre doubles as AKEYCODE_BUTTON_THUMBL, so one press
// reaches us twice: first through Android's input layer, then a few tens of
// milliseconds later through the DJI SDK. Android's copy is the one that keeps
// working when the SDK is disabled, so it is the SDK copy that gets dropped.
std::chrono::steady_clock::time_point g_thumbLAt{};
bool g_thumbLSeen = false;
constexpr auto kThumbLDedup = std::chrono::milliseconds(500);

// The RC's R1/R2/R3 buttons arrive as plain Android key codes F4..F6 (measured - the SDK's
// own boolean button keys are all silent for them) and each drives one momentary EdgeTX
// switch: pressed = down, released = up. They are SG/SH/SI, the switches the RC's C1/C2/C3
// buttons used to drive; C1/C2/C3 now pick the position of the 3-position switch SF
// instead, and they can do that from the DJI SDK (see DjiMsdkBridge.listenPositionButton) -
// which is also why SF is the switch that keeps being usable with the UI closed.
//
// The L1/L2/L3 buttons (F1..F3) are EdgeTX keys instead, see kKeyMap above.
//
// Android only delivers key events to the window that has focus, so these three - and
// with them L1/L2/L3 (F1..F3) and the RC's RTN button (BACK) - stop arriving as soon as
// the UI is gone (the app swiped away; RcLinkService keeps the *firmware* running, but
// there is no window left to receive keys). Everything the DJI SDK reports keeps coming:
// the sticks, the dials and the switches DjiMsdkBridge drives (SA/SD/SE/SF/SC/SJ).
// There is no way around it from here - the kernel's input node is root:input 0660 and
// the only permission that grants that group is signature-level - so a switch that has to
// be usable with the UI closed has to come from the SDK. The positions the switches are
// left in are kept, they simply cannot be moved while no window exists.
//
// The photo, video and pause buttons are NOT here: their kernel key codes never
// reach an app (the RC's own dpad service consumes them, and pause is not in the
// kernel input stream at all), so SC/SD/SE are driven from the DJI SDK instead -
// see DjiMsdkBridge.listenButtonKeys().
//
// A key code listed in joystick.keys wins, which is how any of these can be made
// an EdgeTX key instead - L1/L2/L3 (F1..F3) are mapped that way above, so they
// open the SYS/MODEL/TELE pages.
struct SwitchKey {
    int32_t keycode;
    uint8_t index;
    int8_t  state;   // the position the switch takes while the button is held
};

// Board switch table (tx16smk3.json): 6 = SG, 7 = SH, 8 = SI. All three are momentary, so
// the release puts them back up.
const SwitchKey kSwitchKeys[] = {
    {AKEYCODE_F4, 6, 1},  // R1 -> SG down while held
    {AKEYCODE_F5, 7, 1},  // R2 -> SH down while held
    {AKEYCODE_F6, 8, 1},  // R3 -> SI down while held
};

std::vector<int32_t> g_loggedSwitchKeys;

const char* switch_name(uint8_t index) {
    static char buf[4];
    std::snprintf(buf, sizeof(buf), "S%c", 'A' + index);
    return buf;
}

void log_switch_key_once(int32_t keycode, uint8_t index, int8_t state) {
    if (std::find(g_loggedSwitchKeys.begin(), g_loggedSwitchKeys.end(), keycode) !=
        g_loggedSwitchKeys.end()) {
        return;
    }
    g_loggedSwitchKeys.push_back(keycode);
    LOGI("joystick: button keycode %d (%s) -> EdgeTX switch %s position %d", keycode,
         key_name(keycode), switch_name(index), (int)state);
}

// How long each mapped button was held, plus the repeats Android sends while one is held.
//
// All this side has to do for a long press is keep the key down for as long as the button
// is: EdgeTX turns a hold of about 320 ms into one of its own (radio/src/keys.cpp,
// KEY_LONG_DELAY = 32 x 10 ms), which is what the menu's "long RTN" and a Lua script's
// "force exit" hang off (gui/colorlcd/libui/window.cpp:625, lua/interface.cpp:1228). So
// the two lines below are what tells the two possible failures apart: a hold that never
// arrives from Android (the remote's own service may hand the button over as a plain tap -
// then the duration here stays short no matter how long it is pressed) versus one that
// arrives and is acted on.
constexpr auto kLongPressMs = std::chrono::milliseconds(320);  // EdgeTX's KEY_LONG_DELAY
constexpr uint8_t kMaxReportedKeys = 16;
std::chrono::steady_clock::time_point g_keyDownAt[kMaxReportedKeys];
bool g_keyIsDown[kMaxReportedKeys] = {};

void report_key_hold(uint8_t key, bool down) {
    if (key == 0 || key >= kMaxReportedKeys) return;

    const auto now = std::chrono::steady_clock::now();
    if (down) {
        g_keyDownAt[key] = now;
        g_keyIsDown[key] = true;
        LOGI("joystick: %s down", etkey_name(key));
        return;
    }
    if (!g_keyIsDown[key]) return;
    g_keyIsDown[key] = false;

    const auto held =
        std::chrono::duration_cast<std::chrono::milliseconds>(now - g_keyDownAt[key]);
    LOGI("joystick: %s up after %d ms%s", etkey_name(key), static_cast<int>(held.count()),
         held >= kLongPressMs ? " (EdgeTX sees a long press)" : "");
}

// Android repeats a DOWN while a key is held. The repeats are dropped - EdgeTX does its own
// repeating and its own long-press timing - but the first one is worth a line: it is the
// evidence that the button is being held as far as Android is concerned.
void log_key_repeat(int32_t keycode) {
    static int32_t logged = 0;
    if (logged == keycode) return;
    logged = keycode;
    LOGI("joystick: %d (%s) is being held (android repeat)", keycode, key_name(keycode));
}

// The remote's return button, and the long press it cannot produce.
//
// A hold never reaches the app: the RC's own input stack hands KEY_BACK over as a tap,
// whichever way it is pressed. Measured by writing a 900 ms press straight to the input
// device - it arrives as an 8 ms tap that lands when the button is released - while the
// same hold on the gpio-keys device arrives intact (that one carries the shoulder keys).
// EdgeTX's long press is what closes a page group (gui/colorlcd/libui/window.cpp:625) and
// what stops a running Lua script (lua/interface.cpp:1228), and no Android-delivered button
// on this remote can produce one - so two gestures stand in for it: the second click of a
// double click (below), and holding the H button, which the DJI SDK does report with its
// real duration (see nativeOnDjiGoHomeHeld). One click is passed through untouched, and two
// slow clicks are still two short presses; a *quick* pair does not always reach us, because
// the remote takes a rapid double press of its own return button for itself - which is why
// the H button is the one to use in practice.
//
// Both gestures stay, but the raw joystick reader has since changed the premise: it reads
// the button's own bit out of the reports, so a hold now arrives as a hold and EdgeTX's
// long press fires by itself. The gestures are simply a second way to get there.
constexpr auto kDoubleClickWindow = std::chrono::milliseconds(400);
constexpr uint32_t kLongPressHoldMs = 450;   // EdgeTX's own threshold is ~320 ms

bool g_exitDownSwallowed = false;   // the press of the second click, not yet released
bool g_exitTapSeen = false;         // the previous click ended within the window
std::chrono::steady_clock::time_point g_exitTapAt;

// Returns true when the click was dealt with here, which for the return key is always.
bool handle_exit_click(bool down) {
    const auto now = std::chrono::steady_clock::now();

    if (down) {
        if (g_exitTapSeen && now - g_exitTapAt <= kDoubleClickWindow) {
            g_exitTapSeen = false;
            g_exitDownSwallowed = true;
            LOGI("joystick: RTN double click -> long press");
            requestKey(kKeyExit, kLongPressHoldMs);
            return true;
        }
        g_exitDownSwallowed = false;
        report_key_hold(kKeyExit, true);
        set_key(kKeyExit, true);
        return true;
    }

    if (g_exitDownSwallowed) {
        g_exitDownSwallowed = false;
        return true;   // the press this release belongs to never reached the firmware
    }

    report_key_hold(kKeyExit, false);
    set_key(kKeyExit, false);
    g_exitTapSeen = true;
    g_exitTapAt = now;
    return true;
}

// One button edge, whoever it came from: Android's input queue, or the raw joystick
// reader (see RcRawJoystick.java). The second source exists because of the return button:
// Android only ever sees RTN as a tap re-injected by the RC's own service, and that
// service loses the joystick device the moment an app holds its USB interface. The bit in
// the reports is the button itself, so RTN is fed from there now - with its real duration,
// which is the first time a genuine long press of it can reach EdgeTX on this remote.
bool handleKeyCode(int32_t keycode, bool down) {
    if (keycode == AKEYCODE_BUTTON_THUMBL && down) {
        g_thumbLAt = std::chrono::steady_clock::now();
        g_thumbLSeen = true;
    }

    uint8_t key = 0;
    if (lookup_key(keycode, &key)) {
        log_mapped_key_once(keycode, key);
        if (key == kKeyExit) return handle_exit_click(down);
        report_key_hold(key, down);
        set_key(key, down);
        return true;
    }

    // Not remapped, so fall back to the built-in role of these buttons. They are
    // momentary switches: the switch follows the button, so both edges matter.
    for (const SwitchKey& entry : kSwitchKeys) {
        if (entry.keycode == keycode) {
            const int8_t state = down ? entry.state : static_cast<int8_t>(-entry.state);
            log_switch_key_once(keycode, entry.index, state);
            requestSwitch(entry.index, state);
            return true;
        }
    }

    log_key_once(keycode);
    return true;
}

bool handleKeyEvent(AInputEvent* event) {
    const int32_t source = AInputEvent_getSource(event);

    // Touch/rotary sources are not buttons; accept anything that is not the
    // touchscreen so a controller reported as a keyboard still works.
    if ((source & AINPUT_SOURCE_CLASS_MASK) == AINPUT_SOURCE_CLASS_POINTER) return false;
    if ((source & AINPUT_SOURCE_TOUCHSCREEN) == AINPUT_SOURCE_TOUCHSCREEN) return false;

    const int32_t action = AKeyEvent_getAction(event);
    if (action != AKEY_EVENT_ACTION_DOWN && action != AKEY_EVENT_ACTION_UP) return false;
    const int32_t keycode = AKeyEvent_getKeyCode(event);
    // Android repeats DOWN while a button is held; EdgeTX does its own repeat and
    // long-press handling, so only the initial press and the release reach the firmware.
    // The repeat is reported once anyway, see log_key_repeat().
    if (action == AKEY_EVENT_ACTION_DOWN && AKeyEvent_getRepeatCount(event) > 0) {
        log_key_repeat(keycode);
        return true;
    }

    const bool down = (action == AKEY_EVENT_ACTION_DOWN);
    return handleKeyCode(keycode, down);
}

// ---------------------------------------------------------------------------
// Synthetic key presses, for buttons the Android input layer never delivers.
//
// These cannot simply call setKey(true) followed by setKey(false):
//
//  * EdgeTX samples its key state from its own thread, so a key that goes down
//    and up within a single call is never observed. It has to stay down for a
//    few frames, which is what tick() is for.
//  * The DJI SDK hands us button events on the Java main thread, whereas the
//    Android input path feeds keys from the activity's thread. Doing the
//    transition in tick() keeps the two apart, and tick() runs on the link thread
//    - see native_main.cpp, which keeps it running with no UI on screen.
// ---------------------------------------------------------------------------
constexpr auto kSynthHold = std::chrono::milliseconds(60);

std::mutex g_synthMutex;
bool g_synthQueued = false;
uint8_t g_synthQueuedKey = 0;
std::chrono::milliseconds g_synthQueuedHold = kSynthHold;
bool g_synthHeld = false;
uint8_t g_synthHeldKey = 0;
std::chrono::steady_clock::time_point g_synthReleaseAt{};

void requestKey(uint8_t key, uint32_t holdMs) {
    std::lock_guard<std::mutex> lock(g_synthMutex);
    g_synthQueued = true;
    g_synthQueuedKey = key;
    g_synthQueuedHold = std::chrono::milliseconds(holdMs);
}

// ---------------------------------------------------------------------------
// Stick positions from the DJI SDK.
//
// Android never dispatches the RC's stick MotionEvents (see native_main.cpp),
// but the SDK does expose them as four integer keys. They are queued and pushed
// from tick() so all firmware entry points stay on the android_main thread.
// ---------------------------------------------------------------------------

// DjiMsdkBridge axis ids -> EdgeTX analog channel (see simu_host.h):
//   0 left horizontal  -> 0 rudder
//   1 left vertical    -> 1 throttle
//   2 right horizontal -> 3 aileron
//   3 right vertical   -> 2 elevator
//
// No inversion: confirmed on device that the SDK already reports the sticks the
// way EdgeTX expects. The HID joystick path further down *does* flip the vertical
// axes, because the RC's raw kernel axes are the other way round - the two
// sources disagree, so they cannot share a helper.
constexpr uint8_t kStickChannels[4] = {0, 1, 3, 2};

// The SDK reports each stick, and each dial, as an integer centred on zero,
// spanning -660..660 (measured at full deflection for all six on device).
constexpr int32_t kDjiStickRange = 660;

// Switches we drive, indexed by the board's switch table
// (radio/src/boards/hw_defs/tx16smk3.json): 0 = SA .. 9 = SJ.
//   0    takeoff button                 (DJI SDK, press toggles high/low)
//   1    flight-mode switch             (DJI SDK, three-position)
//   2-7  L1/L2/L3/R1/R2/R3              (Android key codes, see kSwitchKeys)
//   9    aircraft arm state             (DJI SDK KeyAreMotorsOn; SJ is unused otherwise)
constexpr int kSwitchCount = 10;

std::mutex g_switchMutex;
bool g_switchQueued[kSwitchCount] = {};
int8_t g_switchQueuedValue[kSwitchCount] = {};

// Analog channels we can drive: 0-3 are the stick axes, then the flex inputs
// from the same board file - 4 = P1, 5 = P2, 6 = SL1, 7 = SL2.
constexpr int kMaxAnalogChannels = 8;
constexpr uint8_t kScrollWheelChannel = 7;  // SL2

std::mutex g_analogMutex;
bool g_analogQueued[kMaxAnalogChannels] = {};
uint16_t g_analogValue[kMaxAnalogChannels] = {};
int32_t g_scrollWheelValue = 0;

// ---- how much of the stick lag is the firmware's own filter -----------------
//
// The firmware smooths the main inputs with a 16-sample moving average (see
// radio/src/hal/adc_driver.cpp: changes below 10 * ANALOG_MULTIPLIER are averaged, larger
// ones pass straight through), so what the mixer sees can trail what the app pushed.
// Once a second, and only while a stick is being moved, both are logged side by side:
// the two differing is that filter catching up, and it is lag no app-side work can take
// out (the same filter runs on a real radio).
constexpr uint8_t kStickAxes = 4;

std::chrono::steady_clock::time_point g_stickLogAt;
bool g_stickMoved = false;
uint16_t g_stickPushed[kStickAxes] = {};

void log_sticks_if_moved() {
    const auto now = std::chrono::steady_clock::now();
    if (now - g_stickLogAt < std::chrono::seconds(1)) return;
    if (!g_stickMoved) return;

    g_stickLogAt = now;
    g_stickMoved = false;

    std::string pushed;
    std::string firmware;
    for (uint8_t axis = 0; axis < kStickAxes; axis++) {
        if (axis) {
            pushed += '/';
            firmware += '/';
        }
        pushed += std::to_string(g_stickPushed[axis]);
        // anaIn() is 0..2048, half of what the app pushes: doubled to compare.
        firmware += std::to_string(anaIn(kStickChannels[axis]) * 2);
    }
    LOGI("joystick: sticks LH/LV/RH/RV pushed %s, firmware reads %s", pushed.c_str(),
         firmware.c_str());
}

// ---- dials -> P1/P2 ---------------------------------------------------------
//
// The two dials are absolute, the same -660..660 the SDK reports for the sticks, and
// they become the board's P1 and P2 inputs. That is what makes them usable as the
// source of a special function - Volume, Backlight - which is how a radio with two
// dials is normally set up; driving the 5-way's encoder instead would leave them
// with nothing to do that the 5-way cannot already do.
constexpr uint8_t kDialCount = 2;  // 0 = left dial, 1 = right dial

constexpr uint8_t kDialChannels[kDialCount] = {
    4,  // left dial  -> P1
    5,  // right dial -> P2
};

std::mutex g_rotaryMutex;
int32_t g_rotaryQueued = 0;

// One wheel detent covers this much of the range, so ~20 detents is full travel.
constexpr int32_t kScrollWheelStep = 33;

uint16_t dji_analog(int32_t value) {
    if (value > kDjiStickRange) value = kDjiStickRange;
    if (value < -kDjiStickRange) value = -kDjiStickRange;
    return static_cast<uint16_t>(2048 + (value * 2048) / kDjiStickRange);
}

void requestAnalog(uint8_t channel, uint16_t value) {
    if (channel >= kMaxAnalogChannels) return;
    std::lock_guard<std::mutex> lock(g_analogMutex);
    g_analogValue[channel] = value;
    g_analogQueued[channel] = true;
}

void requestStick(int axis, int32_t value) {
    if (axis < 0 || axis > 3) return;
    requestAnalog(kStickChannels[axis], dji_analog(value));
}

void requestDial(uint8_t dial, int32_t value) {
    if (dial >= kDialCount) return;
    requestAnalog(kDialChannels[dial], dji_analog(value));
}

// Queue rotary encoder steps directly (positive = clockwise = right). Used by the
// 5-way's up/down and drained by tick(); the dials no longer contribute.
void requestRotary(int32_t steps) {
    std::lock_guard<std::mutex> lock(g_rotaryMutex);
    g_rotaryQueued += steps;
}

// ---- 5-way stick trim mode --------------------------------------------------
//
// The RC's go-home button (the one Android reports as FN and the SDK as
// KeyGoHomeButtonDown) picks what the 5-way does, by how many times it is pressed
// in one go:
//
//     one press     -> back to normal (rotary encoder, page keys, ENTER)
//     two presses   -> the four directions trim the left stick
//     three presses -> the four directions trim the right stick
//
// Up/down move that stick's vertical trim, left/right its horizontal one, one step
// per press - the same step a physical trim switch makes. The centre press keeps
// sending ENTER, so a trim mode never locks the user out of the UI.
//
// A step has to be a pulse rather than a single call: EdgeTX samples the trim
// switches from its own timer, so a switch that goes down and up inside one tick()
// is never seen - the same reason the synthetic keys are held in tick().
constexpr auto kTrimPressWindow = std::chrono::milliseconds(800);
constexpr auto kTrimPulseLength = std::chrono::milliseconds(60);
// A trim mode is for a quick correction, not a mode to get stuck in: leave it after
// this long without a 5-way press.
constexpr auto kTrimModeIdle = std::chrono::seconds(3);

std::mutex g_trimMutex;
int g_trimMode = 0;   // 0 = off, 1 = left stick, 2 = right stick
int g_trimPressCount = 0;
std::chrono::steady_clock::time_point g_trimLastPress;
std::chrono::steady_clock::time_point g_trimLastActivity;
std::deque<uint8_t> g_trimQueued;
std::deque<std::pair<uint8_t, std::chrono::steady_clock::time_point>> g_trimActive;

const char* trim_mode_name(int mode) {
    switch (mode) {
        case 1: return "left stick";
        case 2: return "right stick";
        default: return "off";
    }
}

// One press of the go-home button.
void requestTrimModePress() {
    std::lock_guard<std::mutex> lock(g_trimMutex);
    const auto now = std::chrono::steady_clock::now();
    if (g_trimPressCount > 0 && now - g_trimLastPress > kTrimPressWindow) {
        g_trimPressCount = 0;   // a press this late starts a new count
    }
    g_trimPressCount++;
    g_trimLastPress = now;
    LOGI("joystick: go-home press %d", g_trimPressCount);
}

// A 5-way direction while a trim mode is active. Returns true when it was consumed,
// which is what stops it from turning the encoder or paging instead.
bool handleTrimDirection(int id) {
    std::lock_guard<std::mutex> lock(g_trimMutex);
    if (g_trimMode == 0) return false;

    // The trim bits are numbered in the board's ADC order, which is
    // LH, LV, RV, RH (radio/src/boards/hw_defs/tx16smk3.json) - so on the right stick
    // the vertical axis comes before the horizontal one, not after.
    static constexpr uint8_t kAxis[2][2] = {
        // horizontal, vertical
        {0, 1},  // left stick
        {3, 2},  // right stick
    };

    const bool vertical = (id == 1 || id == 2);   // 1 = up, 2 = down
    const bool positive = (id == 1 || id == 4);   // 4 = right
    const int axis = kAxis[g_trimMode - 1][vertical ? 1 : 0];
    const uint8_t bit = static_cast<uint8_t>(axis * 2 + (positive ? 1 : 0));
    g_trimQueued.push_back(bit);
    g_trimLastActivity = std::chrono::steady_clock::now();
    LOGI("joystick: trim %s axis %d %s", trim_mode_name(g_trimMode), axis,
         positive ? "+" : "-");
    return true;
}

// The wheel reports relative steps that fall back to 0 after each detent, so they
// are accumulated into a slider.
//
// Nothing produces the MODEL or TELE keys: they remain valid EdgeTX keys and can
// still be assigned through the key-map file, but no built-in control sends them.
void requestScrollWheel(int32_t steps) {
    std::lock_guard<std::mutex> lock(g_analogMutex);
    g_scrollWheelValue += steps * kScrollWheelStep;
    if (g_scrollWheelValue > kDjiStickRange) g_scrollWheelValue = kDjiStickRange;
    if (g_scrollWheelValue < -kDjiStickRange) g_scrollWheelValue = -kDjiStickRange;
    g_analogValue[kScrollWheelChannel] = dji_analog(g_scrollWheelValue);
    g_analogQueued[kScrollWheelChannel] = true;
}

// ---- switch positions across restarts ---------------------------------------
//
// The RC's latching switches stay where the user put them while the app is not
// looking: the takeoff button latches in the remote's own hardware, and the pause
// and L1/L2/L3 buttons work the same way for whoever is pressing them. The DJI SDK
// only ever reports a press, never the latched level, so the position has to be
// remembered here - and remembered across a restart, because EdgeTX reads its switch
// positions once while booting and warns about every one that is not where the model
// expects it.
//
// Only the latch that this module is the only witness of is kept here:
//
//   5  SF  L1/L2/L3  (Android key codes F1..F3, see kSwitchKeys)
//
// SA (takeoff), SD (pause) and SE (photo) latch in exactly the same way, but they come
// from DjiMsdkBridge.java, which keeps their positions itself: the SDK's registration is
// what first makes them known, and Java can read its own store before that, whereas a
// call into here would race with this file being loaded (link_start() does that, which
// runs after Application.onCreate has already called the bridge).
//
// The momentary ones (SC/SG/SH from the buttons, SI from C3) are back where they
// belong as soon as nothing is held, and SJ carries the aircraft's arm state, about
// which only the aircraft can be authoritative.
//
// Caveat, and it cannot be fixed from here: the remote's latch moves even while the app
// is dead, and a press made in that window is invisible. The restored position is
// therefore the app's best guess, and the next press puts both sides back in step.
constexpr uint8_t kPersistedSwitches[] = {5};

bool is_latching_switch(int index) {
    for (uint8_t persisted : kPersistedSwitches) {
        if (persisted == index) return true;
    }
    return false;
}

const char* switch_position_name(int8_t state) {
    if (state < 0) return "up";
    if (state == 0) return "middle";
    return "down";
}

std::mutex g_stateMutex;
std::string g_statePath;                 // empty until setStateDir() has run
int8_t g_stateValue[kSwitchCount] = {};  // last position seen, per switch
bool g_stateKnown[kSwitchCount] = {};    // false = never seen, i.e. nothing saved

// The whole table is rewritten on every change: it holds at most four lines, and a
// rewrite cannot leave a half-applied one behind.
void save_switch_state_locked() {
    if (g_statePath.empty()) return;

    std::ofstream out(g_statePath.c_str(), std::ios::trunc);
    if (!out) {
        LOGW("joystick: cannot write %s", g_statePath.c_str());
        return;
    }
    out << "# Where the RC's latching switches were when the app last ran.\n"
           "# <switch index> <position>; -1 up, 0 middle, 1 down. Rewritten whenever\n"
           "# a switch moves - editing it while the app runs has no effect.\n";
    for (int i = 0; i < kSwitchCount; i++) {
        if (g_stateKnown[i]) out << i << " " << (int)g_stateValue[i] << "\n";
    }
}

void requestSwitch(int index, int8_t state) {
    if (index < 0 || index >= kSwitchCount) return;
    {
        std::lock_guard<std::mutex> lock(g_switchMutex);
        g_switchQueuedValue[index] = state;
        g_switchQueued[index] = true;
    }

    // The switch this module alone sees is remembered for the next run - see
    // kPersistedSwitches.
    if (!is_latching_switch(index)) return;

    std::lock_guard<std::mutex> lock(g_stateMutex);
    if (g_stateKnown[index] && g_stateValue[index] == state) return;
    g_stateValue[index] = state;
    g_stateKnown[index] = true;
    LOGI("joystick: switch %s -> %s (kept for the next start)", switch_name(index),
         switch_position_name(state));
    save_switch_state_locked();
}

void tick() {
    bool release = false;
    uint8_t releasedKey = 0;
    bool press = false;
    uint8_t pressedKey = 0;
    std::chrono::milliseconds hold = kSynthHold;
    {
        std::lock_guard<std::mutex> lock(g_synthMutex);
        const auto now = std::chrono::steady_clock::now();
        if (g_synthHeld && now >= g_synthReleaseAt) {
            release = true;
            releasedKey = g_synthHeldKey;
            g_synthHeld = false;
        }
        if (!g_synthHeld && g_synthQueued) {
            press = true;
            pressedKey = g_synthQueuedKey;
            hold = g_synthQueuedHold;
            g_synthQueued = false;
        }
    }

    if (release) set_key(releasedKey, false);
    if (press) {
        set_key(pressedKey, true);
        std::lock_guard<std::mutex> lock(g_synthMutex);
        g_synthHeld = true;
        g_synthHeldKey = pressedKey;
        g_synthReleaseAt = std::chrono::steady_clock::now() + hold;
    }

    for (int channel = 0; channel < kMaxAnalogChannels; channel++) {
        bool queued;
        uint16_t value;
        {
            std::lock_guard<std::mutex> lock(g_analogMutex);
            queued = g_analogQueued[channel];
            value = g_analogValue[channel];
            g_analogQueued[channel] = false;
        }
        if (queued) {
            push_analog(static_cast<uint8_t>(channel), value);
            for (uint8_t axis = 0; axis < kStickAxes; axis++) {
                if (kStickChannels[axis] == channel) {
                    g_stickPushed[axis] = value;
                    g_stickMoved = true;
                }
            }
        }
    }

    log_sticks_if_moved();

    for (int index = 0; index < kSwitchCount; index++) {
        bool queued;
        int8_t state;
        {
            std::lock_guard<std::mutex> lock(g_switchMutex);
            queued = g_switchQueued[index];
            state = g_switchQueuedValue[index];
            g_switchQueued[index] = false;
        }
        if (queued) simu::setSwitch(static_cast<uint8_t>(index), state);
    }

    // Trim mode: settle on the mode once the go-home presses stop, then hold every
    // queued trim step long enough for the firmware's own polling to see it.
    {
        std::lock_guard<std::mutex> lock(g_trimMutex);
        const auto now = std::chrono::steady_clock::now();

        if (g_trimPressCount > 0 && now - g_trimLastPress > kTrimPressWindow) {
            int mode = 0;
            switch (g_trimPressCount) {
                case 2: mode = 1; break;
                case 3: mode = 2; break;
                default: break;   // one press cancels; four or more are ignored
            }
            g_trimPressCount = 0;
            if (mode != g_trimMode) {
                g_trimMode = mode;
                g_trimLastActivity = now;
                LOGI("joystick: 5-way trim mode -> %s", trim_mode_name(mode));
            }
        }

        // Not used for a while: the mode is meant for a quick correction, so put the
        // 5-way back to its normal job by itself.
        if (g_trimMode != 0 && now - g_trimLastActivity > kTrimModeIdle) {
            g_trimMode = 0;
            LOGI("joystick: 5-way trim mode -> off (idle)");
        }

        while (!g_trimQueued.empty()) {
            g_trimActive.emplace_back(g_trimQueued.front(), now + kTrimPulseLength);
            g_trimQueued.pop_front();
        }
        for (auto it = g_trimActive.begin(); it != g_trimActive.end();) {
            simu::setTrim(it->first, true);
            if (now >= it->second) {
                simu::setTrim(it->first, false);
                it = g_trimActive.erase(it);
            } else {
                ++it;
            }
        }
    }

    // Rotary steps collected since the last frame, from the 5-way's up/down.
    int32_t steps;
    {
        std::lock_guard<std::mutex> lock(g_rotaryMutex);
        steps = g_rotaryQueued;
        g_rotaryQueued = 0;
    }
    if (steps != 0) {
        // Rate-limited: turning a dial or leaning on the 5-way produces a burst of
        // steps and only the fact that they are flowing is interesting in the log.
        static auto lastLog = std::chrono::steady_clock::time_point{};
        const auto now = std::chrono::steady_clock::now();
        if (now - lastLog >= std::chrono::milliseconds(500)) {
            lastLog = now;
            LOGI("joystick: rotary -> %d step(s)", static_cast<int>(steps));
        }
        simu::rotaryEncoderEvent(steps);
    }
}

// ---------------------------------------------------------------------------
// Startup readiness - see joystick.h.
// ---------------------------------------------------------------------------
#if defined(EDGETX_MSDK)
std::atomic<bool> g_inputsReady{false};

void setInputsReady(bool ready) { g_inputsReady.store(ready); }
bool inputsReady() { return g_inputsReady.load(); }
#else
// Nothing to wait for: without the SDK there is no source for the RC's controls.
void setInputsReady(bool) {}
bool inputsReady() { return true; }
#endif

// ---------------------------------------------------------------------------
// Saved switch positions - see kPersistedSwitches further up.
// ---------------------------------------------------------------------------

void setStateDir(const char* dir) {
    if (dir == nullptr || dir[0] == '\0') return;

    std::lock_guard<std::mutex> lock(g_stateMutex);
    const std::string path = std::string(dir) + "/joystick.switches";
    if (g_statePath == path) return;
    g_statePath = path;

    std::ifstream in(path.c_str());
    if (!in) {
        LOGI("joystick: no saved switch positions in %s yet", path.c_str());
        return;
    }

    int loaded = 0;
    std::string line;
    while (std::getline(in, line)) {
        const size_t hash = line.find('#');
        if (hash != std::string::npos) line.erase(hash);

        int index = -1;
        int state = 0;
        if (std::sscanf(line.c_str(), "%d %d", &index, &state) != 2) continue;
        if (!is_latching_switch(index)) continue;
        if (state < -1) state = -1;
        if (state > 1) state = 1;

        g_stateValue[index] = static_cast<int8_t>(state);
        g_stateKnown[index] = true;
        ++loaded;
    }
    LOGI("joystick: %d saved switch position(s) from %s", loaded, path.c_str());
}

void restoreSwitches() {
    std::lock_guard<std::mutex> lock(g_stateMutex);
    for (int i = 0; i < kSwitchCount; i++) {
        if (!g_stateKnown[i]) continue;
        // Into the firmware *and* into the library's copy of it (see simu::setSwitch): the
        // boot resets the switches after this runs, and the library puts its copy back.
        simu::setSwitch(static_cast<uint8_t>(i), g_stateValue[i]);
        LOGI("joystick: switch %s restored to %s", switch_name(static_cast<uint8_t>(i)),
             switch_position_name(g_stateValue[i]));
    }
}

int switchState(int index) {
    if (index < 0 || index >= kSwitchCount) return 0;
    std::lock_guard<std::mutex> lock(g_stateMutex);
    return g_stateKnown[index] ? g_stateValue[index] : 0;
}

// Called from DjiMsdkBridge.java when the SDK is registered and the sticks are
// reporting - or when it is clear that they never will be. This is what
// link_start() waits for (see inputsReady() above).
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_DjiMsdkBridge_nativeSetInputsReady(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    LOGI("joystick: RC controls reporting, the firmware may boot");
    setInputsReady(true);
}

// Called from RcRawJoystick.java for the buttons that only exist in the joystick's raw
// reports - the return button (see handleKeyCode above). It goes through the same handler
// as Android's key events, so the key map file and EdgeTX's own long-press timing apply
// exactly as they do to a key the framework delivered.
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_RcRawJoystick_nativeOnRawKey(JNIEnv* env, jclass clazz, jint keycode,
                                                     jboolean down) {
    (void)env;
    (void)clazz;
    handleKeyCode(keycode, down != JNI_FALSE);
}

// Called from DjiMsdkBridge.java (DJI Mobile SDK) for RC buttons that Android's
// input layer never delivers. The ones we care about are the RC Plus 2's 5-way
// switch directions: the device reports them as ABS_HAT0X / ABS_HAT0Y motion
// events, which the RC firmware does not dispatch to apps, so the SDK is the
// only documented source for them.
//
// Ids come from DjiMsdkBridge.BTN_*; keep the two sides in sync.
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_DjiMsdkBridge_nativeOnDjiButton(JNIEnv* env, jclass clazz, jint id) {
    (void)env;
    (void)clazz;

    // While a trim mode is active the four directions trim the selected stick
    // instead of turning the encoder and paging. The centre press is untouched.
    if (id >= 1 && id <= 4 && handleTrimDirection(id)) return;

    uint8_t key = 0;
    switch (id) {
        // Up and down drive EdgeTX's rotary encoder, one detent per press: up turns
        // it left, down turns it right. They do not report UP/DOWN (the colour UI
        // ignores those), and nothing maps to MODEL/TELE any more either.
        case 1:
            LOGI("joystick: DJI SDK button id %d -> rotary left", id);
            requestRotary(-1);
            return;
        case 2:
            LOGI("joystick: DJI SDK button id %d -> rotary right", id);
            requestRotary(1);
            return;
        // The SDK's leftwards/rightwards flags do match the physical directions
        // (verified against the log), so this is purely about which way the page
        // should turn: left goes back, right goes forward.
        case 3: key = kKeyPageUp; break;   // 5-way left  -> previous page
        case 4: key = kKeyPageDn; break;   // 5-way right -> next page
        case 5:
            // Android already delivers this same button as AKEYCODE_BUTTON_THUMBL.
            if (g_thumbLSeen &&
                std::chrono::steady_clock::now() - g_thumbLAt < kThumbLDedup) {
                LOGI("joystick: DJI SDK 5-way press ignored (Android sent it)");
                return;
            }
            key = kKeyEnter;               // 5-way press -> confirm
            break;
        default:
            LOGI("joystick: DJI SDK button id %d (unmapped)", id);
            return;
    }

    LOGI("joystick: DJI SDK button id %d -> EdgeTX %s", id, etkey_name(key));
    requestKey(key);
}

// Called from DjiMsdkBridge.java with the RC's stick positions. `axis` is
// 0 left-horizontal, 1 left-vertical, 2 right-horizontal, 3 right-vertical;
// `value` is the SDK's raw reading, centred on zero.
//
// This is the only way to get the sticks: the RC firmware never dispatches
// their MotionEvents to Android, and this process may not open
// /dev/input/event4 (root:input, mode 0660) to read them from the kernel.
// The RC's go-home button, one call per press. It only changes what the 5-way
// does, see the trim mode above.
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_DjiMsdkBridge_nativeOnDjiGoHome(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    requestTrimModePress();
}

// Called from DjiMsdkBridge.java when the RC's H (go-home) button was *held*, not tapped.
//
// H is the stand-in for a long press of the return button, which no source can deliver: the
// return button arrives as an 8 ms tap however long it is pressed (measured by writing a
// 900 ms press to its input device; see the note on handle_exit_click), while the SDK reports
// H's press and release with the real duration - 99-110 ms for a tap and 1160-2053 ms for a
// hold, measured on the device. EdgeTX's long press is what closes a page group and what
// stops a running Lua script (lua/interface.cpp:1228).
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_DjiMsdkBridge_nativeOnDjiGoHomeHeld(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    LOGI("joystick: H held -> long press RTN");
    requestKey(kKeyExit, kLongPressHoldMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_DjiMsdkBridge_nativeOnDjiStick(JNIEnv* env, jclass clazz, jint axis,
                                                       jint value) {
    (void)env;
    (void)clazz;
    requestStick(axis, value);
}

// Called from DjiMsdkBridge.java with a dial position. `dial` is 0 = left,
// 1 = right; `value` is the SDK's absolute reading in the same -660..660 range
// as the sticks. The dial becomes one of the board's two P1/P2 inputs (see
// requestDial).
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_DjiMsdkBridge_nativeOnDjiDial(JNIEnv* env, jclass clazz, jint dial,
                                                      jint value) {
    (void)env;
    (void)clazz;
    requestDial(static_cast<uint8_t>(dial), value);
}

// Called from DjiMsdkBridge.java with scroll-wheel movement in detents.
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_DjiMsdkBridge_nativeOnDjiScrollWheel(JNIEnv* env, jclass clazz,
                                                            jint steps) {
    (void)env;
    (void)clazz;
    requestScrollWheel(steps);
}

// Called from DjiMsdkBridge.java for the RC's physical switches. `index` 0 = SA,
// 1 = SB (see requestSwitch); `state` is <0 up, 0 middle, >0 down, which is
// exactly what boardSwitchGetPosition() expects.
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_DjiMsdkBridge_nativeOnDjiSwitch(JNIEnv* env, jclass clazz, jint index,
                                                        jint state) {
    (void)env;
    (void)clazz;
    requestSwitch(index, static_cast<int8_t>(state));
}

}  // namespace joystick
