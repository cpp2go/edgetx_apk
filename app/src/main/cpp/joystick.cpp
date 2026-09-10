#include "joystick.h"

#include <jni.h>

#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <vector>

#include "log.h"
#include "simu_host.h"

namespace joystick {
namespace {

// EdgeTX simulator analog channel order - see targets/simu/adc_driver.cpp and
// the SDL reference implementation in targets/simu/sdl_simu.cpp:
//
//   0 left stick X  (rudder)             1 left stick Y  (throttle, inverted)
//   2 right stick Y (elevator, inverted) 3 right stick X (aileron)
//   4..                                  flex inputs (sliders / pots)
//
// Each EdgeTX channel lists the Android axes it accepts, most specific first.
// Only axes the device actually declares are used, so a transmitter that
// reports AXIS_RUDDER / AXIS_THROTTLE works just as well as a plain gamepad
// with only X/Y/RX/RY.
constexpr int32_t kNoAxis = -1;
constexpr int32_t kMaxAxis = 64;

struct AxisChoice {
    uint8_t analog;
    int32_t axis[2];
    bool invert;
};

const AxisChoice kMap[] = {
    {0, {AMOTION_EVENT_AXIS_X, AMOTION_EVENT_AXIS_RUDDER}, false},
    {1, {AMOTION_EVENT_AXIS_Y, AMOTION_EVENT_AXIS_THROTTLE}, true},
    {2, {AMOTION_EVENT_AXIS_RY, kNoAxis}, true},
    {3, {AMOTION_EVENT_AXIS_RX, kNoAxis}, false},
    {4, {AMOTION_EVENT_AXIS_Z, AMOTION_EVENT_AXIS_BRAKE}, false},
    {5, {AMOTION_EVENT_AXIS_RZ, AMOTION_EVENT_AXIS_GAS}, false},
};

struct AxisRange {
    bool present = false;
    float min = -1.0f;
    float max = 1.0f;
};

struct DeviceInfo {
    AxisRange axes[kMaxAxis];
};

std::mutex g_lock;
std::map<int32_t, DeviceInfo> g_devices;
ANativeActivity* g_activity = nullptr;
bool g_present = false;

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

bool range_of(int32_t deviceId, int32_t axis, AxisRange* out) {
    std::lock_guard<std::mutex> lock(g_lock);
    const auto it = g_devices.find(deviceId);
    if (it == g_devices.end() || axis < 0 || axis >= kMaxAxis) return false;
    if (!it->second.axes[axis].present) return false;
    *out = it->second.axes[axis];
    return true;
}

bool device_known(int32_t deviceId) {
    std::lock_guard<std::mutex> lock(g_lock);
    return g_devices.find(deviceId) != g_devices.end();
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
                LOGI("joystick: sticks %s", anyJoystick ? "detected, using joystick data"
                                                        : "gone, back to the demo source");
            }
            if (!anyJoystick) {
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
    if (!is_joystick_source(source)) return false;

    const int32_t deviceId = AInputEvent_getDeviceId(event);
    // Hot-plugged device: learn its axis ranges before trusting it.
    if (!device_known(deviceId) && g_activity != nullptr) init(g_activity);

    const int32_t action = AMotionEvent_getAction(event);
    if ((action & AMOTION_EVENT_ACTION_MASK) == AMOTION_EVENT_ACTION_CANCEL) {
        LOGI("joystick: device %d cancelled", deviceId);
        return true;
    }

    bool pushed = false;
    for (const AxisChoice& choice : kMap) {
        for (int32_t candidate : choice.axis) {
            if (candidate == kNoAxis) break;
            AxisRange range;
            if (!range_of(deviceId, candidate, &range)) continue;

            const float value = AMotionEvent_getAxisValue(event, candidate, 0);
            simu::pushAnalog(choice.analog, to_analog(value, range, choice.invert));
            pushed = true;
            break;  // first declared axis wins
        }
    }

    if (pushed) {
        simu::setAnalogSource(true);
        if (!present()) g_present = true;
    }
    return true;
}

}  // namespace joystick
