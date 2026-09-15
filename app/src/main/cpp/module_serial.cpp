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

// The external module port is bridged onto the first aux serial instance
// (AUX1): that is the RX queue simuAuxSerialReceive() fills and the index the
// driver installed in module_drivers.cpp reports.
constexpr uint8_t kModulePortNr = 0;

// A CRSF frame is ~26 bytes and goes out every ~4 ms, so this is several
// hundred milliseconds of backlog: enough to ride out scheduling hiccups,
// small enough that a disconnected module cannot grow the heap.
constexpr size_t kMaxTxQueue = 8192;

std::mutex s_txMutex;
std::deque<uint8_t> s_txQueue;

std::atomic<uint32_t> s_baudrate{0};
std::atomic<bool> s_portOpen{false};
std::atomic<uint32_t> s_txBytes{0};
std::atomic<uint32_t> s_rxBytes{0};
std::atomic<uint32_t> s_droppedBytes{0};

// Log the first frame of each direction once, so logcat shows the link coming
// alive without printing every frame.
std::atomic<bool> s_loggedFirstTx{false};
std::atomic<bool> s_loggedFirstRx{false};

void sinkStart(uint8_t portNr, uint32_t baudrate, uint8_t encoding)
{
    if (portNr != kModulePortNr) return;

    {
        std::lock_guard<std::mutex> lock(s_txMutex);
        s_txQueue.clear();
    }

    s_baudrate.store(baudrate, std::memory_order_relaxed);
    s_portOpen.store(true, std::memory_order_relaxed);
    s_loggedFirstTx.store(false, std::memory_order_relaxed);
    s_loggedFirstRx.store(false, std::memory_order_relaxed);

    LOGI("module port opened: %u baud, encoding %u", baudrate, encoding);
}

void sinkStop(uint8_t portNr)
{
    if (portNr != kModulePortNr) return;

    s_portOpen.store(false, std::memory_order_relaxed);
    s_baudrate.store(0, std::memory_order_relaxed);

    {
        std::lock_guard<std::mutex> lock(s_txMutex);
        s_txQueue.clear();
    }

    LOGI("module port closed (tx %u B, rx %u B, dropped %u B)",
         s_txBytes.load(std::memory_order_relaxed),
         s_rxBytes.load(std::memory_order_relaxed),
         s_droppedBytes.load(std::memory_order_relaxed));
}

void sinkSetBaudrate(uint8_t portNr, uint32_t baudrate)
{
    if (portNr != kModulePortNr) return;

    if (s_baudrate.exchange(baudrate, std::memory_order_relaxed) != baudrate) {
        LOGI("module baud rate -> %u", baudrate);
    }
}

void sinkSend(uint8_t portNr, const uint8_t* data, uint32_t len)
{
    if (portNr != kModulePortNr || data == nullptr || len == 0) return;

    s_txBytes.fetch_add(len, std::memory_order_relaxed);

    if (!s_loggedFirstTx.exchange(true, std::memory_order_relaxed)) {
        LOGI("first module frame out: %u byte(s), %02X %02X %02X",
             len, data[0], len > 1 ? data[1] : 0, len > 2 ? data[2] : 0);
    }

    std::lock_guard<std::mutex> lock(s_txMutex);
    for (uint32_t i = 0; i < len; ++i) {
        if (s_txQueue.size() >= kMaxTxQueue) {
            s_txQueue.pop_front();
            s_droppedBytes.fetch_add(1, std::memory_order_relaxed);
        }
        s_txQueue.push_back(data[i]);
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
             "the external module stays silent");
        return;
    }

    edgetxAndroidSetAuxSerialSink(&sink);
    LOGI("module serial bridge installed");
}

uint32_t wantedBaudrate()
{
    return s_portOpen.load(std::memory_order_relaxed)
               ? s_baudrate.load(std::memory_order_relaxed)
               : 0;
}

bool portOpen()
{
    return s_portOpen.load(std::memory_order_relaxed);
}

uint32_t txBytes() { return s_txBytes.load(std::memory_order_relaxed); }
uint32_t rxBytes() { return s_rxBytes.load(std::memory_order_relaxed); }
uint32_t droppedBytes() { return s_droppedBytes.load(std::memory_order_relaxed); }

}  // namespace module_serial

// --------------------------------------------------------------------- JNI --
// Called from RcModuleSerial.java. The reader thread is the only caller of
// nativePushRx and the writer thread the only caller of nativeTakeTx.

extern "C" JNIEXPORT jint JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativeWantedBaudrate(JNIEnv*, jclass)
{
    return static_cast<jint>(module_serial::wantedBaudrate());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativePortOpen(JNIEnv*, jclass)
{
    return module_serial::portOpen() ? JNI_TRUE : JNI_FALSE;
}

// Drains up to buf.length bytes the firmware wants to transmit. Returns how many
// were copied (0 = nothing pending).
extern "C" JNIEXPORT jint JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativeTakeTx(JNIEnv* env, jclass, jbyteArray buf)
{
    if (buf == nullptr) return 0;

    const jsize capacity = env->GetArrayLength(buf);
    if (capacity <= 0) return 0;

    uint8_t* scratch = new (std::nothrow) uint8_t[capacity];
    if (scratch == nullptr) return 0;

    jsize count = 0;
    {
        std::lock_guard<std::mutex> lock(s_txMutex);
        while (count < capacity && !s_txQueue.empty()) {
            scratch[count++] = s_txQueue.front();
            s_txQueue.pop_front();
        }
    }

    if (count > 0) env->SetByteArrayRegion(buf, 0, count, reinterpret_cast<jbyte*>(scratch));
    delete[] scratch;

    return count;
}

// Bytes the app read from the RF module, handed to the firmware's module RX path.
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativePushRx(JNIEnv* env, jclass, jbyteArray buf,
                                                    jint len)
{
    if (buf == nullptr || len <= 0) return;

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

        simuAuxSerialReceive(kModulePortNr, scratch, static_cast<uint32_t>(chunk));
        offset += chunk;
    }

    s_rxBytes.fetch_add(static_cast<uint32_t>(count), std::memory_order_relaxed);

    if (!s_loggedFirstRx.exchange(true, std::memory_order_relaxed)) {
        LOGI("first module frame in: %d byte(s), %02X %02X %02X", count,
             scratch[0], count > 1 ? scratch[1] : 0, count > 2 ? scratch[2] : 0);
    }
}

// { txBytes, rxBytes, droppedBytes, baudrate, portOpen } - one call for the
// periodic status line.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_edgetx_droidui_RcModuleSerial_nativeStats(JNIEnv* env, jclass)
{
    const jint values[5] = {
        static_cast<jint>(module_serial::txBytes()),
        static_cast<jint>(module_serial::rxBytes()),
        static_cast<jint>(module_serial::droppedBytes()),
        static_cast<jint>(module_serial::wantedBaudrate()),
        module_serial::portOpen() ? 1 : 0,
    };

    jintArray out = env->NewIntArray(5);
    if (out != nullptr) {
        env->SetIntArrayRegion(out, 0, 5, values);
    }
    return out;
}
