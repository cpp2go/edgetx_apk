#include "joystick.h"

#include <android/keycodes.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <vector>

#include "log.h"
#include "simu_host.h"

namespace joystick {
namespace {

// --------------------------------------------------------------- EdgeTX keys --
//
// Values from radio/src/hal/key_driver.h (enum EnumKeys). Only the keys the
// target defines do anything: TX16SMK3 (the 800x480 touch radio) defines
// EXIT, ENTER, PAGEUP, PAGEDN, MODEL, TELE and SYS - there are no arrow keys,
// which is why a D-pad is mapped onto the page/confirm keys instead.
// (Upstream's SDL simulator gates its own mapping on keysGetSupported() the
// same way.)
constexpr uint8_t kKeyExit = 1;
constexpr uint8_t kKeyEnter = 2;
constexpr uint8_t kKeyPageUp = 3;
constexpr uint8_t kKeyPageDn = 4;
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

const KeyChoice kKeyMap[] = {
    {AKEYCODE_BUTTON_A, kKeyEnter},
    {AKEYCODE_DPAD_CENTER, kKeyEnter},
    {AKEYCODE_ENTER, kKeyEnter},
    {AKEYCODE_NUMPAD_ENTER, kKeyEnter},

    {AKEYCODE_BUTTON_B, kKeyExit},
    {AKEYCODE_BACK, kKeyExit},
    {AKEYCODE_ESCAPE, kKeyExit},

    {AKEYCODE_BUTTON_L1, kKeyPageUp},
    {AKEYCODE_DPAD_LEFT, kKeyPageUp},
    {AKEYCODE_BUTTON_R1, kKeyPageDn},
    {AKEYCODE_DPAD_RIGHT, kKeyPageDn},

    {AKEYCODE_BUTTON_X, kKeyModel},
    {AKEYCODE_BUTTON_L2, kKeyModel},
    {AKEYCODE_BUTTON_THUMBL, kKeyModel},

    {AKEYCODE_BUTTON_Y, kKeyTele},
    {AKEYCODE_BUTTON_R2, kKeyTele},
    {AKEYCODE_BUTTON_THUMBR, kKeyTele},

    {AKEYCODE_BUTTON_START, kKeySys},
    {AKEYCODE_BUTTON_SELECT, kKeySys},
    {AKEYCODE_BUTTON_MODE, kKeySys},
};

// ------------------------------------------------------------------ state ----
std::mutex g_lock;
std::map<int32_t, DeviceInfo> g_devices;
ANativeActivity* g_activity = nullptr;
bool g_present = false;
std::vector<int32_t> g_loggedAxes;   // deviceId<<8 | axis, logged once each
std::vector<int32_t> g_loggedKeys;   // android key codes already reported
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
        default: return "?";
    }
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
        simu::pushAnalog(choice.analog, to_analog(value, range, route.invert));
        pushed = true;
    }

    if (pushed) {
        simu::setAnalogSource(true);
        if (!present()) g_present = true;
        log_analog_throttled(event, info);
    }
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
    // Android repeats DOWN while a button is held; EdgeTX does its own repeat
    // and long-press handling, so pass only the initial press and the release.
    if (action == AKEY_EVENT_ACTION_DOWN && AKeyEvent_getRepeatCount(event) > 0) return true;

    const int32_t keycode = AKeyEvent_getKeyCode(event);
    const bool down = (action == AKEY_EVENT_ACTION_DOWN);

    for (const KeyChoice& choice : kKeyMap) {
        if (choice.keycode != keycode) continue;
        simu::setKey(choice.key, down);
        return true;
    }

    log_key_once(keycode);
    return true;
}

}  // namespace joystick
