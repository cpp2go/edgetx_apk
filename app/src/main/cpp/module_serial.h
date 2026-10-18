// RF modules over USB serial - one slot at a time.
//
// EdgeTX talks to an RF module over a serial port. This app has no such UART, so
// each of the firmware's two module slots is bridged to its own real USB serial
// port:
//
//   internal slot (port 0, AUX1): the built-in module, normally the C3 on its own
//                                 USB (USB Serial/JTAG, 303a:1001)
//   external slot (port 1, AUX2): a module on a USB-UART adapter
//
//   firmware TX (the frames of whatever protocol EdgeTX is configured for:
//                CRSF, GHST, MULTI, DSM2, AFHDS3, ... - anything the firmware
//                drives over a serial port)
//        -> module_serial TX queue -> nativeTakeTx() -> Java -> USB -> module
//
//   module -> USB -> Java -> nativePushRx() -> firmware RX -> telemetry screens
//
// Only the slot the model enables is driven: RcModuleSerial opens the internal
// device when the internal slot is on, otherwise the external one, and the other
// port's frames are discarded. When only one module is attached it is treated as
// the internal one. Running both slots at once would put two transmitters on the
// same model, which is not what a model with one slot enabled asks for.
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

// The firmware's AUX indexes, which are also the Java link's port number:
// the built-in module and the module on the USB-UART adapter.
constexpr uint8_t kInternalPort = 0;
constexpr uint8_t kExternalPort = 1;

// Registers the bridge with the EdgeTX simulator library.
//
// Must run before simu::start(): the firmware initialises the module ports as
// soon as the mixer task comes up, and anything it sends before the sink is
// installed is dropped.
void init();

// Baud rate the firmware configured for that slot's port, or 0 while it is
// closed. Java polls this to configure the USB serial port to match.
uint32_t wantedBaudrate(uint8_t portNr);

// Framing the firmware configured (ETX_Encoding_xx: 0 = 8N1, 1 = 8E2, which
// only SBUS uses), or 0 while the port is closed. Everything except SBUS is
// 8N1, so this is normally 0.
uint8_t wantedEncoding(uint8_t portNr);

// True between the firmware opening and closing that slot's module port.
bool portOpen(uint8_t portNr);

// Counters for logging / on-screen diagnostics.
uint32_t txBytes(uint8_t portNr);
uint32_t rxBytes(uint8_t portNr);
uint32_t droppedBytes(uint8_t portNr);

}  // namespace module_serial
