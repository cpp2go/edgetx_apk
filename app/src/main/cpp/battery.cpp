// Battery state from the Android side.
//
// The remote controller is an Android device, so its pack voltage, percentage and
// charging state are read with a normal ACTION_BATTERY_CHANGED receiver (see
// RcBattery.java) and arrive here. They are then handed to the simulator host layer.
#include <jni.h>

#include "battery.h"
#include "log.h"
#include "simu_host.h"

namespace battery {

void apply(int millivolts, int percent, bool charging) {
    simu::setBattery(static_cast<uint16_t>(millivolts),
                     static_cast<uint8_t>(percent),
                     charging);
}

}  // namespace battery

extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_RcBattery_nativeOnBattery(JNIEnv* env, jclass clazz, jint millivolts,
                                                 jint percent, jboolean charging) {
    (void)env;
    (void)clazz;
    battery::apply(millivolts, percent, charging != 0);
}
