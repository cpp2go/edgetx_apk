#pragma once

#include <cstdint>

// Battery state pushed in from the Android side (see RcBattery.java).
namespace battery {

// `millivolts` 0 means "unknown". `percent` is 0..100. `charging` is Android's view of
// whether the pack is on charge.
void apply(int millivolts, int percent, bool charging);

}  // namespace battery
