// See module_serial.h for the architecture.
//
// Threading: the TX sink runs on the firmware's mixer task, takeTx() is called
// from the Java writer thread (through JNI), and pushRx() from the Java reader
// thread. Everything shared is behind the TX mutex or an atomic; the TX queue is
// the only buffer, and it is bounded because the module port is opened by the
// firmware whether or not a USB serial device happens to be plugged in.

#include "module_serial.h"

#include "simu_host.h"

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstring>
#include <deque>
#include <mutex>
#include <new>

#define LOG_TAG "EdgeTXModule"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Two module slots, two wires: the built-in module (the C3) is on AUX1 and the
// module on the USB-UART adapter is on AUX2. The firmware's module_drivers.cpp
// binds the internal slot to AUX1 and the external slot to AUX2 and reports that
// index in every sink call, so bytes never have to be guessed into a slot.
constexpr uint8_t kInternalModulePort = 0;
constexpr uint8_t kExternalModulePort = 1;
constexpr uint8_t kModulePortCount = 2;

// A CRSF frame is ~26 bytes and goes out every ~4 ms, so this is several
// hundred milliseconds of backlog: enough to ride out scheduling hiccups,
// small enough that a disconnected module cannot grow the heap.
constexpr size_t kMaxTxQueue = 8192;

struct ModulePort {
    std::mutex txMutex;
    std::deque<uint8_t> txQueue;

    std::atomic<uint32_t> baudrate{0};
    std::atomic<uint8_t> encoding{0};
    std::atomic<bool> open{false};
    std::atomic<uint32_t> txBytes{0};
    std::atomic<uint32_t> rxBytes{0};
    std::atomic<uint32_t> droppedBytes{0};

    // Log the first frame of each direction once, so logcat shows the link
    // coming alive without printing every frame.
    std::atomic<bool> loggedFirstTx{false};
    std::atomic<bool> loggedFirstRx{false};
};

ModulePort s_ports[kModulePortCount];

ModulePort* portAt(uint8_t portNr)
{
    return portNr < kModulePortCount ? &s_ports[portNr] : nullptr;
}

const char* portName(uint8_t portNr)
{
    return portNr == kInternalModulePort   ? "internal (C3)"
           : portNr == kExternalModulePort ? "external (USB-UART)"
                                           : "unknown";
}

// ETX_Encoding_xx from the firmware's hal/serial_driver.h. Only two of the three
// are byte-serial framings - PXX1 PWM is a soft-serial pulse encoding that never
// reaches this bridge, so anything that is not 8E2 is 8N1.
static const char* encodingName(uint8_t encoding)
{
    return encoding == 1 ? "8E2" : "8N1";
}

void sinkStart(uint8_t portNr, uint32_t baudrate, uint8_t encoding)
{
    ModulePort* port = portAt(portNr);
    if (port == nullptr) return;

    {
        std::lock_guard<std::mutex> lock(port->txMutex);
        port->txQueue.clear();
    }

    port->baudrate.store(baudrate, std::memory_order_relaxed);
    port->encoding.store(encoding, std::memory_order_relaxed);
    port->open.store(true, std::memory_order_relaxed);
    port->loggedFirstTx.store(false, std::memory_order_relaxed);
    port->loggedFirstRx.store(false, std::memory_order_relaxed);

    LOGI("%s module port opened: %u baud, encoding %u (%s)", portName(portNr),
         baudrate, encoding, encodingName(encoding));
}

void sinkStop(uint8_t portNr)
{
    ModulePort* port = portAt(portNr);
    if (port == nullptr) return;

    port->open.store(false, std::memory_order_relaxed);
    port->baudrate.store(0, std::memory_order_relaxed);
    port->encoding.store(0, std::memory_order_relaxed);

    {
        std::lock_guard<std::mutex> lock(port->txMutex);
        port->txQueue.clear();
    }

    LOGI("%s module port closed (tx %u B, rx %u B, dropped %u B)", portName(portNr),
         port->txBytes.load(std::memory_order_relaxed),
         port->rxBytes.load(std::memory_order_relaxed),
         port->droppedBytes.load(std::memory_order_relaxed));
}

void sinkSetBaudrate(uint8_t portNr, uint32_t baudrate)
{
    ModulePort* port = portAt(portNr);
    if (port == nullptr) return;

    if (port->baudrate.exchange(baudrate, std::memory_order_relaxed) != baudrate) {
        LOGI("%s module baud rate -> %u", portName(portNr), baudrate);
    }
}

void sinkSend(uint8_t portNr, const uint8_t* data, uint32_t len)
{
    ModulePort* port = portAt(portNr);
    if (port == nullptr || data == nullptr || len == 0) return;

    port->txBytes.fetch_add(len, std::memory_order_relaxed);

    if (!port->loggedFirstTx.exchange(true, std::memory_order_relaxed)) {
        LOGI("%s first module frame out: %u byte(s), %02X %02X %02X", portName(portNr),
             len, data[0], len > 1 ? data[1] : 0, len > 2 ? data[2] : 0);
    }

    std::lock_guard<std::mutex> lock(port->txMutex);
    for (uint32_t i = 0; i < len; ++i) {
        if (port->txQueue.size() >= kMaxTxQueue) {
            port->txQueue.pop_front();
            port->droppedBytes.fetch_add(1, std::memory_order_relaxed);
        }
        port->txQueue.push_back(data[i]);
    }
}

}  // namespace

namespace module_serial {

void init()
{
    // The sink has static storage duration: the firmware keeps the pointer.
    static const edgetxAndroidSerialSink sink = {
        sinkStart,
        sinkStop,
        sinkSetBaudrate,
        sinkSend,
    };

    if (edgetxAndroidSetAuxSerialSink == nullptr) {
        LOGE("simulator library has no aux-serial sink support; "
             "both module slots stay silent");
        return;
    }

    edgetxAndroidSetAuxSerialSink(&sink);
    LOGI("module serial bridge installed (AUX1 internal, AUX2 external)");
}

uint32_t wantedBaudrate(uint8_t portNr)
{
    ModulePort* port = portAt(portNr);
    return port != nullptr && port->open.load(std::memory_order_relaxed)
               ? port->baudrate.load(std::memory_order_relaxed)
               : 0;
}

uint8_t wantedEncoding(uint8_t portNr)
{
    ModulePort* port = portAt(portNr);
    return port != nullptr && port->open.load(std::memory_order_relaxed)
               ? port->encoding.load(std::memory_order_relaxed)
               : 0;
}

bool portOpen(uint8_t portNr)
{
    ModulePort* port = portAt(portNr);
    return port != nullptr && port->open.load(std::memory_order_relaxed);
}

uint32_t txBytes(uint8_t portNr)
{
    ModulePort* port = portAt(portNr);
    return port != nullptr ? port->txBytes.load(std::memory_order_relaxed) : 0;
}

uint32_t rxBytes(uint8_t portNr)
{
    ModulePort* port = portAt(portNr);
    return port != nullptr ? port->rxBytes.load(std::memory_order_relaxed) : 0;
}

uint32_t droppedBytes(uint8_t portNr)
{
    ModulePort* port = portAt(portNr);
    return port != nullptr ? port->droppedBytes.load(std::memory_order_relaxed) : 0;
}

}  // namespace module_serial

// --------------------------------------------------------------------- JNI --
// Called from RcModuleSerial.java, one link per module slot. The reader thread
// is the only caller of nativePushRx and the writer thread the only caller of
// nativeTakeTx for that port.

extern "C" JNIEXPORT jint JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativeWantedBaudrate(JNIEnv*, jclass, jint portNr)
{
    return static_cast<jint>(module_serial::wantedBaudrate(static_cast<uint8_t>(portNr)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativeWantedEncoding(JNIEnv*, jclass, jint portNr)
{
    return static_cast<jint>(module_serial::wantedEncoding(static_cast<uint8_t>(portNr)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativePortOpen(JNIEnv*, jclass, jint portNr)
{
    return module_serial::portOpen(static_cast<uint8_t>(portNr)) ? JNI_TRUE : JNI_FALSE;
}

// Drains up to buf.length bytes the firmware wants to transmit on that port.
// Returns how many were copied (0 = nothing pending).
extern "C" JNIEXPORT jint JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativeTakeTx(JNIEnv* env, jclass, jint portNr,
                                                    jbyteArray buf)
{
    ModulePort* port = portAt(static_cast<uint8_t>(portNr));
    if (port == nullptr || buf == nullptr) return 0;

    const jsize capacity = env->GetArrayLength(buf);
    if (capacity <= 0) return 0;

    uint8_t* scratch = new (std::nothrow) uint8_t[capacity];
    if (scratch == nullptr) return 0;

    jsize count = 0;
    {
        std::lock_guard<std::mutex> lock(port->txMutex);
        while (count < capacity && !port->txQueue.empty()) {
            scratch[count++] = port->txQueue.front();
            port->txQueue.pop_front();
        }
    }

    if (count > 0) env->SetByteArrayRegion(buf, 0, count, reinterpret_cast<jbyte*>(scratch));
    delete[] scratch;

    return count;
}

// Bytes the app read from a module, handed to the firmware's RX path for that
// slot (AUX1 = internal, AUX2 = external).
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativePushRx(JNIEnv* env, jclass, jint portNr,
                                                    jbyteArray buf, jint len)
{
    ModulePort* port = portAt(static_cast<uint8_t>(portNr));
    if (port == nullptr || buf == nullptr || len <= 0) return;

    const jsize available = env->GetArrayLength(buf);
    const jsize count = len < available ? len : available;
    if (count <= 0) return;

    uint8_t scratch[256];
    jsize offset = 0;
    while (offset < count) {
        const jsize chunk = (count - offset) < static_cast<jsize>(sizeof(scratch))
                                ? (count - offset)
                                : static_cast<jsize>(sizeof(scratch));

        env->GetByteArrayRegion(buf, offset, chunk, reinterpret_cast<jbyte*>(scratch));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return;
        }

        simuAuxSerialReceive(static_cast<uint8_t>(portNr), scratch,
                             static_cast<uint32_t>(chunk));
        offset += chunk;
    }

    port->rxBytes.fetch_add(static_cast<uint32_t>(count), std::memory_order_relaxed);

    if (!port->loggedFirstRx.exchange(true, std::memory_order_relaxed)) {
        LOGI("%s first module frame in: %d byte(s), %02X %02X %02X",
             portName(static_cast<uint8_t>(portNr)), count,
             scratch[0], count > 1 ? scratch[1] : 0, count > 2 ? scratch[2] : 0);
    }
}

// { txBytes, rxBytes, droppedBytes, baudrate, portOpen } - one call for the
// periodic status line.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativeStats(JNIEnv* env, jclass, jint portNr)
{
    const uint8_t nr = static_cast<uint8_t>(portNr);
    const jint values[5] = {
        static_cast<jint>(module_serial::txBytes(nr)),
        static_cast<jint>(module_serial::rxBytes(nr)),
        static_cast<jint>(module_serial::droppedBytes(nr)),
        static_cast<jint>(module_serial::wantedBaudrate(nr)),
        module_serial::portOpen(nr) ? 1 : 0,
    };

    jintArray out = env->NewIntArray(5);
    if (out != nullptr) {
        env->SetIntArrayRegion(out, 0, 5, values);
    }
    return out;
}

// Protocol the model selected for each slot (MODULE_TYPE_*, 0 = off). The Java side
// picks the one module the model actually uses: internal first, external only when
// the internal slot is off. Weak symbols: an older library reports 0 for the
// internal slot, and then the external slot is still honoured.
extern "C" JNIEXPORT jint JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativeInternalModuleType(JNIEnv*, jclass)
{
    return static_cast<jint>(simu::internalModuleType());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativeExternalModuleType(JNIEnv*, jclass)
{
    return static_cast<jint>(simu::externalModuleType());
}

// Drops what the firmware queued for a slot the model is not using. Both slots can
// be enabled in a model (EdgeTX allows it), but this app drives only the internal
// one then - the other module must not transmit, so its frames are discarded here
// instead of being sent.
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativeDiscardTx(JNIEnv*, jclass, jint portNr)
{
    ModulePort* port = portAt(static_cast<uint8_t>(portNr));
    if (port == nullptr) return;

    std::lock_guard<std::mutex> lock(port->txMutex);
    port->txQueue.clear();
}
