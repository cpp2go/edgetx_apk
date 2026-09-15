// Audio playback for the ported EdgeTX firmware.
//
// The firmware's own audio engine runs inside the simulator library and queues the
// PCM its mixer produces there (see simuQueueAudio in the EdgeTX tree's
// targets/simu/android_host.cpp). This side only moves those bytes into an
// AudioTrack; RcAudio.java owns the track and drives the loop.
//
// Nothing here decides what is played. The boot greeting, the key beeps and the
// voice prompts all come from the firmware's audio engine, so wiring the samples
// out is all it takes for the startup sound to work.

#include <jni.h>

#include <cstdint>

#include "simu_host.h"

// Drains the queue into `buf`, oldest first. Returns how many bytes were copied, or
// 0 when nothing is pending - the caller backs off for a few milliseconds then.
extern "C" JNIEXPORT jint JNICALL
Java_com_edgetx_droidui_RcAudio_nativeTakeAudio(JNIEnv* env, jclass, jbyteArray buf)
{
    if (buf == nullptr) return 0;

    const jsize capacity = env->GetArrayLength(buf);
    if (capacity <= 0) return 0;

    // Chunked so this stays off the heap: the queue is only ever a little deeper
    // than one mixer buffer, so the first pass almost always empties it.
    uint8_t scratch[4096];
    jsize offset = 0;

    while (offset < capacity) {
        const jsize room = capacity - offset;
        const jsize chunk = room < static_cast<jsize>(sizeof(scratch))
                                ? room
                                : static_cast<jsize>(sizeof(scratch));

        const uint32_t got = simu::takeAudio(scratch, static_cast<uint32_t>(chunk));
        if (got == 0) break;

        env->SetByteArrayRegion(buf, offset, static_cast<jsize>(got),
                                reinterpret_cast<const jbyte*>(scratch));
        offset += static_cast<jsize>(got);

        if (got < static_cast<uint32_t>(chunk)) break;  // queue ran dry
    }

    return offset;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_edgetx_droidui_RcAudio_nativeSampleRate(JNIEnv*, jclass)
{
    return static_cast<jint>(simu::audioSampleRate());
}

// Packed as (written << 32) | dropped, so one call covers the periodic status line.
extern "C" JNIEXPORT jlong JNICALL
Java_com_edgetx_droidui_RcAudio_nativeStats(JNIEnv*, jclass)
{
    const jlong written = static_cast<jlong>(simu::audioWrittenBytes());
    const jlong dropped = static_cast<jlong>(simu::audioDroppedBytes());
    return (written << 32) | (dropped & 0xFFFFFFFFL);
}

// What RcAudio has given AudioTrack but not heard back yet, so the firmware can keep
// the boot splash up until the greeting has finished (see RcAudio.publishPending).
extern "C" JNIEXPORT void JNICALL
Java_com_edgetx_droidui_RcAudio_nativeSetAudioPending(JNIEnv*, jclass, jlong remainingBytes)
{
    simu::setAudioPending(remainingBytes > 0 ? static_cast<uint32_t>(remainingBytes) : 0u);
}
