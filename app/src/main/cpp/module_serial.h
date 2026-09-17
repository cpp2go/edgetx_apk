// RF module over USB serial.
//
// EdgeTX talks to the RF module over a serial port. This app has no such
// UART, so the firmware's module port is bridged to a real USB serial port that
// RcModuleSerial.java owns:
//
//   firmware TX (the frames of whatever protocol EdgeTX is configured for:
//                CRSF, SBUS, DSM, PPM, ...)
//        -> module_serial TX queue -> nativeTakeTx() -> Java -> USB -> module
//
//   module -> USB -> Java -> nativePushRx() -> firmware RX -> telemetry screens
//
// The protocol is never chosen here: it comes from the model's own module
// settings, because the firmware's module driver is what produces and consumes
// these bytes.
#pragma once

#include <cstdint>

namespace module_serial {

// Registers the bridge with the EdgeTX simulator library.
//
// Must run before simu::start(): the firmware initialises the module port as soon
// as the mixer task comes up, and anything it sends before the sink is installed
// is dropped.
void init();

// Baud rate the firmware configured for the module, or 0 while the port is
// closed. Java polls this to configure the USB serial port to match.
uint32_t wantedBaudrate();

// True between the firmware opening and closing the module port.
bool portOpen();

// Counters for logging / on-screen diagnostics.
uint32_t txBytes();
uint32_t rxBytes();
uint32_t droppedBytes();

}  // namespace module_serial
