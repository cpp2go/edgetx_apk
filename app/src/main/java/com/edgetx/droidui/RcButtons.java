package com.edgetx.droidui;

import android.os.SystemClock;

/**
 * Every RC button press, whichever reader saw it, counted in one place.
 *
 * <p>This remote's buttons are reported by readers that each see only part of the set, and none
 * of them sees its part all of the time:
 *
 * <ul>
 *   <li>the <b>DJI SDK</b> ({@link DjiMsdkBridge}) is the only reader that knows some of the
 *       keys at all - and on the RC Pro it sets every one of its keys to null in waves of 30 to
 *       45 s (measured, once 63 s), so a press made during one is simply never delivered;</li>
 *   <li>the remote's own <b>button log</b> ({@link RcDpadLog}) sees every press the remote acts
 *       on and is immune to those waves, but reading it needs {@code READ_LOGS}, a hand grant
 *       that a reinstall takes away;</li>
 *   <li>the joystick's <b>raw USB reports</b> ({@link RcRawJoystick}) are the fastest and the
 *       most reliable of the three, and carry only the four buttons that are in that report.</li>
 * </ul>
 *
 * <p>Before this class each button was wired to one or two of those readers by hand, with a rule
 * such as "the log wins while it is being read" at every call site. That is what made a button go
 * dead whenever the reader that happened to own it stopped delivering: the other readers were not
 * outvoted, they were never wired in. Here every reader reports every button it can see into the
 * same place and the presses are merged, so a press lands when <em>any</em> reader saw it, and
 * lands exactly once when several did.
 *
 * <p>The merging is the whole of the trick, because the readers do not agree on when one physical
 * press happened. Measured on the RC Pro:
 *
 * <pre>
 *   the SDK's pulse for one press   the SDK and the raw report 6 to 46 ms apart
 *   the raw report's button bits    the same press as the SDK's, within a frame
 *   the remote's log line           written at the *release*: 2 ms after the SDK's release,
 *                                   and so a whole press duration after the SDK's press
 * </pre>
 *
 * <p>Which gives the three rules this class is:
 *
 * <pre>
 *   press from a reader that reports both edges (SDK, raw)
 *       dropped when the same reader's level is still down (its own repeat push), and when
 *       another reader counted a press less than MERGE_MS ago (that same press)
 *   release from such a reader
 *       closes the press; never moves a switch itself
 *   press from a reader with no release to give (the remote's log)
 *       dropped when a press was counted less than MERGE_MS ago, when a press was *released*
 *       less than MERGE_MS ago (this is the ordinary case - the log line follows the SDK's
 *       release by about 2 ms), or when another reader is still holding one of its own
 * </pre>
 *
 * <p>A deliberate double tap is about 200 ms apart (measured through the remote's log, which is
 * the strictest of the three readers), so {@link #MERGE_MS} has to stay well below that while
 * still covering the 46 ms the readers disagree by. 120 ms does both.
 */
final class RcButtons {

    /** The DJI SDK's own callbacks - see DjiMsdkBridge. */
    static final int SOURCE_SDK = 0;
    /** The joystick's raw USB reports - see RcRawJoystick. */
    static final int SOURCE_RAW = 1;
    /** The remote's own button log - see RcDpadLog. */
    static final int SOURCE_LOG = 2;

    /** The edge is news: the caller acts on it. */
    static final int PRESS = 0;
    /** The same reader reported the level it is already holding: not a second press. */
    static final int REPEAT = 1;
    /** Another reader counted this press already: one physical press, one action. */
    static final int MERGED = 2;

    /** Two edges closer together than this are one physical press, not two. */
    private static final long MERGE_MS = 120;

    /**
     * How long a press from the remote's log keeps other readers from counting their own copy of
     * it. The log line is written at the release and the readers disagree by the length of the
     * press, so this has to cover a tap - and a press whose release the SDK lost is exactly what
     * it is here for. A press is normally long over by then.
     */
    private static final long LOG_PRESS_MS = 400;

    /** True while a reader holds a level down, one bit per source. */
    private static final int[] sHeld = new int[DjiMsdkBridge.SWITCH_COUNT];

    /** When the press that is currently open was reported, 0 while none is. */
    private static final long[] sPressAt = new long[DjiMsdkBridge.SWITCH_COUNT];

    /** Which reader reported it - the log's presses are the ones with no release behind them. */
    private static final int[] sPressSource = new int[DjiMsdkBridge.SWITCH_COUNT];

    /** When a reader last released a press, the fingerprint of the log's copy of that press. */
    private static final long[] sReleaseAt = new long[DjiMsdkBridge.SWITCH_COUNT];

    private RcButtons() {}

    /** How a source is named in the log lines, so which reader delivered a press is visible. */
    static String sourceName(int source) {
        switch (source) {
            case SOURCE_RAW: return "raw";
            case SOURCE_LOG: return "log";
            default: return "sdk";
        }
    }

    /** Which reader's press is open on a button, for the log line a merged press prints. */
    static int pressSource(int index) {
        synchronized (RcButtons.class) {
            return sPressSource[index];
        }
    }

    /**
     * A press or a release from a reader that reports both - the SDK's Boolean button keys and
     * the raw report's button bits.
     *
     * @return {@link #PRESS} for a press that is news, {@link #REPEAT} for the same reader
     *         pushing a level it is already holding (and for every release - nothing moves a
     *         switch but a press), {@link #MERGED} for another reader's copy of the same
     *         physical press.
     */
    static int edge(int index, int source, boolean down) {
        final int bit = 1 << source;
        final long now = SystemClock.uptimeMillis();
        synchronized (RcButtons.class) {
            if (!down) {
                // A release only closes the press it belongs to: counting one as a press is how
                // a level-reporting button used to toggle twice for one press.
                if ((sHeld[index] & bit) == 0) {
                    return REPEAT;
                }
                sHeld[index] &= ~bit;
                sReleaseAt[index] = now;
                sPressAt[index] = 0;
                return REPEAT;
            }

            if ((sHeld[index] & bit) != 0) {
                return REPEAT;
            }
            sHeld[index] |= bit;

            if (sPressAt[index] != 0 && now - sPressAt[index] < MERGE_MS) {
                return MERGED;
            }
            sPressAt[index] = now;
            sPressSource[index] = source;
            return PRESS;
        }
    }

    /**
     * One press from a reader that has no release to give - the remote's own log, which writes a
     * single line per press and nothing when it ends (see RcDpadLog).
     *
     * @return {@link #PRESS} for a press that is news, {@link #MERGED} when one of the readers
     *         that does report both edges has already counted it.
     */
    static int tap(int index, int source) {
        final long now = SystemClock.uptimeMillis();
        synchronized (RcButtons.class) {
            final long pressed = sPressAt[index];
            if (pressed != 0 && now - pressed < MERGE_MS) {
                return MERGED;
            }
            final long released = sReleaseAt[index];
            if (released != 0 && now - released < MERGE_MS) {
                return MERGED;
            }
            if (pressed != 0 && sPressSource[index] != source
                    && now - pressed < LOG_PRESS_MS) {
                // Another reader is holding this button down and its release has not arrived:
                // the same press, reported late rather than twice.
                return MERGED;
            }
            sPressAt[index] = now;
            sPressSource[index] = source;
            return PRESS;
        }
    }
}
