// RF module over USB serial.
//
// EdgeTX talks to the RF module over a serial port. This app has no such
// UART, so the firmware's module port is bridged to a real USB serial port that
// RcModuleSerial.java owns:
//
//   firmware TX (the frames of whatever protocol EdgeTX is configured for:
//                CRSF, GHST, MULTI, DSM2, AFHDS3, ... - anything the firmware
//                drives over a serial port)
//        -> module_serial TX queue -> nativeTakeTx() -> Java -> USB -> module
//
//   module -> USB -> Java -> nativePushRx() -> firmware RX -> telemetry screens
//
// The protocol is never chosen here: it comes from the model's own module
// settings, because the firmware's module driver is what produces and consumes
// these bytes. Baud rate and framing follow that driver too (see wantedBaudrate
// and wantedEncoding).
//
// The line polarity does not, and cannot: an adapter cannot be inverted from
// software. So a protocol EdgeTX drives inverted (MULTI, DSM2/DSMP, SBUS,
// AFHDS3) only works if the module accepts the adapter's level; otherwise the
// wire needs an inverter, which is what the radio itself has.
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

// Framing the firmware configured (ETX_Encoding_xx: 0 = 8N1, 1 = 8E2, which
// only SBUS uses), or 0 while the port is closed. Everything except SBUS is
// 8N1, so this is normally 0.
uint8_t wantedEncoding();

// True between the firmware opening and closing the module port.
bool portOpen();

// Counters for logging / on-screen diagnostics.
uint32_t txBytes();
uint32_t rxBytes();
uint32_t droppedBytes();

}  // namespace module_serial
