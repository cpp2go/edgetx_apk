package com.edgetx.droidui;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Plays the sound the ported EdgeTX firmware produces.
 *
 * <p>The firmware's audio engine runs inside the simulator library and queues the PCM its
 * mixer makes (see the EdgeTX tree's {@code targets/simu/android_host.cpp}). This class
 * only moves those bytes into an {@link AudioTrack}: every sound - the boot greeting,
 * key beeps, alarms and voice prompts - comes from the firmware, so nothing is
 * synthesised here.
 *
 * <p>Format is fixed by the firmware: mono, 16-bit, {@code AUDIO_SAMPLE_RATE} (32 kHz),
 * reported by {@link #nativeSampleRate()} so the two cannot drift apart. Mixed in the
 * simulator, that is what a real radio plays out of its speaker.
 *
 * <p>Volume is the firmware's own setting (the mixer scales the samples before they are
 * queued), multiplied by Android's media volume.
 */
final class RcAudio {
    private static final String TAG = "EdgeTXUI";

    /** Bytes requested per drain. Roughly 32 ms of 32 kHz mono 16-bit audio. */
    private static final int DRAIN_BYTES = 2048;

    private static final int IDLE_SLEEP_MS = 4;
    private static final long STATUS_LOG_INTERVAL_MS = 5000;

    static {
        // Started from Application.onCreate, before NativeActivity loads the library.
        try {
            System.loadLibrary("edgetx_ui");
        } catch (Throwable t) {
            Log.w(TAG, "audio: could not load edgetx_ui", t);
        }
    }

    /** Drains queued PCM into {@code buffer}; returns the byte count, 0 when idle. */
    private static native int nativeTakeAudio(byte[] buffer);

    /** The firmware's sample rate, in Hz. */
    private static native int nativeSampleRate();

    /** Packed (writtenBytes &lt;&lt; 32) | droppedBytes. */
    private static native long nativeStats();

    /**
     * Tells the firmware how many bytes are still queued in the audio device, so it
     * can keep the boot splash up until the greeting has finished playing.
     */
    private static native void nativeSetAudioPending(long remainingBytes);

    /** Mono 16-bit: one frame is one sample. */
    private static final int BYTES_PER_FRAME = 2;

    private static boolean sStarted;
    private static AudioTrack sTrack;
    private static Thread sThread;

    private RcAudio() {}

    static void start(Context context) {
        if (sStarted) {
            return;
        }
        sStarted = true;

        sThread = new Thread(RcAudio::run, "edgetx-audio");
        sThread.setDaemon(true);
        sThread.start();
    }

    private static void run() {
        final byte[] buffer = new byte[DRAIN_BYTES];

        try {
            final int rate = nativeSampleRate();
            final int minBytes = AudioTrack.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBytes <= 0) {
                Log.w(TAG, "audio: unsupported format " + rate + " Hz mono 16-bit");
                return;
            }

            sTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    // Two drains of headroom, and never below what the platform asks
                    // for: too small a buffer is what makes streamed audio stutter.
                    .setBufferSizeInBytes(Math.max(minBytes, DRAIN_BYTES * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            sTrack.play();
            Log.i(TAG, "audio: playing " + rate + " Hz mono 16-bit, buffer "
                    + Math.max(minBytes, DRAIN_BYTES * 2) + " B");
        } catch (Throwable t) {
            Log.w(TAG, "audio: could not open the output", t);
            return;
        }

        long lastLog = System.currentTimeMillis();
        boolean started = false;
        long writtenFrames = 0;

        while (true) {
            try {
                // Logged unconditionally, so "the firmware produced nothing" and "the
                // drain is broken" can be told apart.
                final long now = System.currentTimeMillis();
                if (now - lastLog >= STATUS_LOG_INTERVAL_MS) {
                    lastLog = now;
                    final long stats = nativeStats();
                    Log.i(TAG, "audio: firmware wrote " + (stats >>> 32) + " B, dropped "
                            + (stats & 0xFFFFFFFFL) + " B");
                }

                // Kept current before draining, so it reflects what is still in flight
                // even while the loop is idle between prompts.
                publishPending(writtenFrames);

                final int count = nativeTakeAudio(buffer);
                if (count <= 0) {
                    try {
                        Thread.sleep(IDLE_SLEEP_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }

                if (!started) {
                    started = true;
                    Log.i(TAG, "audio: first samples out (" + count + " B)");
                }

                // Blocking write: paces this loop at the playback rate, which is what
                // keeps the queue from being drained faster than it is filled.
                sTrack.write(buffer, 0, count);
                writtenFrames += count / BYTES_PER_FRAME;
            } catch (Throwable t) {
                Log.w(TAG, "audio: playback failed", t);
                return;
            }
        }
    }

    /**
     * Reports what AudioTrack still owes the speaker. The playback head can be ahead
     * of what was written right after a buffer is drained, so it is clamped at zero.
     */
    private static void publishPending(long writtenFrames) {
        final AudioTrack track = sTrack;
        if (track == null) {
            return;
        }
        final long pendingFrames = writtenFrames - track.getPlaybackHeadPosition();
        try {
            nativeSetAudioPending(pendingFrames > 0 ? pendingFrames * BYTES_PER_FRAME : 0);
        } catch (Throwable ignored) {
            // No native side to tell: nothing waits on the audio then.
        }
    }
}
