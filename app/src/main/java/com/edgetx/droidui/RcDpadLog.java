package com.edgetx.droidui;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The remote's own button log, read out of logcat.
 *
 * <p>This exists because the DJI SDK is not a reliable source for this remote's buttons. On the
 * RC Pro the SDK sets every one of its keys to null in waves - measured, 30 to 45 seconds and
 * once 63 - and a press made during one is simply lost: the listener gets nothing, getValue()
 * returns null rather than the last value, and cancelling and re-issuing the subscription does
 * not bring the key back (all three measured). The remote's own handler, on the other hand,
 * sees every control and says so in its log, under the tag {@code KeyCodeListener}:
 *
 * <pre>
 *   D/KeyCodeListener: send key action >> dpad-service.keys.ACTION_KEY_PAUSE
 *   D/KeyCodeListener: mKeyCodeList == [..ACTION_KEY_RELEASE, ..ACTION_KEY_PAUSE]
 * </pre>
 *
 * <p>Reading another process's log needs {@code READ_LOGS}, which is signature-level and can only
 * be granted by hand, once per remote:
 * {@code adb shell pm grant com.edgetx.droidui android.permission.READ_LOGS}. Without it logcat
 * prints nothing and this class stays inactive, which is deliberate: the app then behaves exactly
 * as it did before, with the buttons the SDK cannot deliver simply missing.
 *
 * <p>Only what the other sources cannot do is handled from here. The 5-way, the shutter, the
 * record and the return button are all in the joystick's raw USB reports (see RcRawJoystick),
 * which are faster and unaffected by the SDK's silences - handling them here as well would make
 * every press land twice.
 */
final class RcDpadLog {

    private static final String TAG = "EdgeTXUI";

    /** The tag the remote's own key handler logs under. */
    private static final String LISTENER = "KeyCodeListener";

    /** What it prints in front of the action it handled. */
    private static final String ACTION = "ACTION_KEY_";

    /** The line that carries the action, as opposed to the key list beside it. */
    private static final String SEND = "send key action";

    /** True while the log is being read: the SDK's copies of those buttons are dropped. */
    private static volatile boolean sActive;

    private RcDpadLog() {}

    /** Called from {@code EdgeTxApplication.onCreate}. */
    static void start() {
        final Thread thread = new Thread(RcDpadLog::read, "rc-dpad-log");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Whether the remote's button log is being read. While it is, the SDK's own callbacks for the
     * same buttons are the second copy of one press - see DjiMsdkBridge.listenToggleSwitch.
     */
    static boolean active() {
        return sActive;
    }

    private static void read() {
        Process process = null;
        try {
            // -s silences every other tag, so only this one comes through.
            process = new ProcessBuilder("logcat", "-v", "brief", "-s", LISTENER + ":D")
                    .redirectErrorStream(true)
                    .start();
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            String line;
            boolean announced = false;
            while ((line = reader.readLine()) != null) {
                if (!announced) {
                    announced = true;
                    sActive = true;
                    Log.i(TAG, "rc: reading the remote's own button log (" + LISTENER + ")");
                }
                handle(line);
            }

            // Endless while the app lives; if it ends, logcat refused us - the permission is
            // missing, or logd was restarted. Either way the SDK paths carry on alone.
            Log.i(TAG, "rc: the remote's button log ended, its buttons fall back to the SDK");
        } catch (Throwable t) {
            Log.w(TAG, "rc: could not read the remote's button log", t);
        } finally {
            sActive = false;
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * One line of the remote's log. A single physical press is written as <b>two</b> lines with
     * the same timestamp - the key list, and the action it belongs to:
     *
     * <pre>
     *   mKeyCodeList == [..ACTION_KEY_RELEASE, ..ACTION_KEY_C1]
     *   send key action >> ..ACTION_KEY_C1
     * </pre>
     *
     * Only the second is acted on. They are one event, and treating both as a press toggles the
     * switch twice for one press - measured: "C1 -> high" 143 ms before "C1 -> low", with one
     * press behind it. The pair is that press: the remote reports each button once per press and
     * never on its own release, which is why DjiMsdkBridge.onDpadPress() does not latch.
     */
    private static void handle(String line) {
        try {
            if (!line.contains(SEND)) {
                return;
            }
            for (String name : actionsIn(line)) {
                if (!"RELEASE".equals(name)) {
                    DjiMsdkBridge.onDpadPress(name);
                }
            }
        } catch (Throwable t) {
            // Never let a log line take the app down: this runs on its own thread, but the
            // dispatch behind it touches the firmware's entry points.
            Log.w(TAG, "rc: could not handle a button log line", t);
        }
    }

    /** Every action named in one line, without the prefix and upper case. */
    private static List<String> actionsIn(String line) {
        final List<String> found = new ArrayList<>(2);
        int at = line.indexOf(ACTION);
        while (at >= 0) {
            int end = at + ACTION.length();
            while (end < line.length()) {
                final char c = line.charAt(end);
                if (c != '_' && !Character.isLetterOrDigit(c)) {
                    break;
                }
                end++;
            }
            found.add(line.substring(at + ACTION.length(), end).toUpperCase(Locale.US));
            at = line.indexOf(ACTION, end);
        }
        return found;
    }
}
