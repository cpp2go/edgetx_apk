package com.edgetx.droidui;

import android.os.Build;
import android.util.Log;

/**
 * The remote controller this build is running on, and what each of its buttons drives.
 *
 * <p>Every DJI remote has the same handful of controls, but which switch an app can put a
 * button on, which physical button is on which end, and which reader delivers it are not the
 * same from one remote to the next. Until this class that was one boolean - {@code isRcPro()} -
 * with the RC Plus 2's layout everywhere else, so a third remote silently inherited a layout
 * that had been measured on the second one: its photo button stepped the RC Plus 2's photo
 * switch, its return-to-home button armed the 5-way trim mode that only the RC Plus 2's H key
 * has, and its own button log was not read at all.
 *
 * <p>Every remote this port has been run on now gets a row in {@link #MODELS}, and nothing
 * outside this class decides anything per remote. An unrecognised remote still gets the RC
 * Plus 2's row - the layout the app started with - and says so in the log.
 *
 * <p>A row is a measurement, not a preference. The two rows marked "measured" were settled by
 * pressing the buttons on the remote and reading the log; the other two are the family layout
 * their remote belongs to (the Plus 2's for the RC Plus, the RC Pro's for the RC Pro 2), which
 * is what the physical button sets suggest and what the first run has to confirm. Changing one
 * value here is how a measurement is applied - nothing else has to move.
 */
final class RcModel {

    private static final String TAG = "EdgeTXUI";

    /** Indices in the board's switch table (tx16smk3.json): 0 = SA .. 9 = SJ. */
    static final int SA = 0;
    static final int SB = 1;
    static final int SC = 2;
    static final int SD = 3;
    static final int SE = 4;
    static final int SF = 5;
    static final int SG = 6;
    static final int SH = 7;
    static final int SI = 8;
    static final int SJ = 9;

    /** A control this remote has no free switch for. */
    static final int NO_SWITCH = -1;

    /**
     * The remotes, one row each. The constructor arguments are, in order: the
     * {@code Build.DEVICE} prefixes the row matches, its name, the switch each of video,
     * pause, photo, C1, C2, C3 and the round button drives, then the three flags below
     * ({@code c3OnSdk}, {@code logDrivesSwitches}, {@code goHomeLands}) and
     * {@code proFlightMode}.
     *
     * Measured rows:
     *
     * <ul>
     *   <li><b>RC Plus 2 (rc701)</b>: video SC, pause SD, photo SE, C1/C2/C3 -> SF/SG/SH, no
     *       round button (R1/R2/R3 took SI, which it used to toggle). Its SDK keys all fire and
     *       Android delivers the rest, so the remote's own log may not move a switch there - a
     *       copy of a press that is already counted is only a duplicate to merge away.</li>
     *   <li><b>RC Pro (rm510, and rm510b/rm510bv = RC Pro Enterprise)</b>: video and pause are
     *       swapped against the RC Plus 2, C1/C2 are on SG/SH, C3 is only ever in the remote's
     *       log (its SDK key fires in the same millisecond as the return button), photo is SF
     *       and the round button takes SE. Its go-home button <em>is</em> the landing button, so
     *       it drives SA instead of the 5-way trim gesture, and its flight-mode switch reports
     *       SWITCH_ONE at the top rather than SWITCH_TWO.</li>
     * </ul>
     *
     * Inherited rows (the family layout, to be confirmed on the hardware):
     *
     * <ul>
     *   <li><b>RC Plus (rm700, rm700_enterprise)</b>: the Plus family - the same button set as
     *       the RC Plus 2 (C1..C3, shutter, record, pause, L1..R3) on the same Android 11 base,
     *       so its slot table is the RC Plus 2's. Its go-home button is the real return-to-home
     *       key, not the H key that arms a trim mode on the RC Plus 2, so it drives SA like the
     *       RC Pro's landing button. Its log is read as a second reader for the buttons the SDK
     *       drops, as on the RC Pro.</li>
     *   <li><b>RC Pro 2 (rc520)</b>: the Pro family - video/pause and photo swapped like the RC
     *       Pro, C1/C2/C3 on SG/SH/SI and no round button beside the screen. DJI's own list of
     *       what can be customised on it names C1, C2, the 5-way and the right wheel, so its C3
     *       may not exist at all: the slot and the listener stay because a key that never fires
     *       costs nothing, and a C3 that does exist then already has a switch. Its flight-mode
     *       switch is expected to report SWITCH_TWO at the top like the newer remote. Both are
     *       one-line changes here if the first run says otherwise.</li>
     * </ul>
     */
    private static final RcModel[] MODELS = {
            //           video pause photo  C1  C2  C3  round      C3sdk  logSw  goHome  proMode
            new RcModel(new String[] {"rc701"}, "RC Plus 2",
                    SC,   SD,   SE,   SF, SG, SH, NO_SWITCH, true,  false, false,  false),
            new RcModel(new String[] {"rm510"}, "RC Pro",
                    SD,   SC,   SF,   SG, SH, SI, SE,        false, true,  true,   true),
            new RcModel(new String[] {"rm700"}, "RC Plus",
                    SC,   SD,   SE,   SF, SG, SH, NO_SWITCH, true,  false, false,  false),
            new RcModel(new String[] {"rc520"}, "RC Pro 2",
                    SD,   SC,   SF,   SG, SH, SI, NO_SWITCH, true,  true,  true,   false),
    };

    /**
     * This remote's row. An unrecognised one is not an error - the RC Plus 2's layout is what
     * the app has always used for anything it has not been measured on - but it is worth a line,
     * because a new remote's buttons are then worth checking against {@link #describe()}.
     */
    static final RcModel CURRENT = find(Build.DEVICE);

    /** The {@code Build.DEVICE} prefixes this row answers to; a revision (rm510b, rm700_enterprise) matches its remote's. */
    final String[] devices;
    final String label;

    final int video;
    final int pause;
    final int photo;
    final int c1;
    final int c2;
    final int c3;

    /** Where the round button beside the screen lands, {@link #NO_SWITCH} when there is none. */
    final int round;

    /**
     * Whether the SDK's own {@code KeyCustomButton3Down} is listened to. Not on the RC Pro: the
     * one time its C3 key ever fired it did so in the same millisecond as the return button's
     * raw bit, so a slot for it would move whenever the return key is pressed.
     */
    final boolean c3OnSdk;

    /**
     * Whether a button the remote reports in its own log may move a switch. On the RC Plus 2 it
     * may not - the SDK and Android already deliver all of them, and the copies are then only
     * duplicates. On the others the log is the reader that keeps arriving while the SDK's keys
     * go silent, and {@link RcButtons} merges its copy with the other readers' anyway.
     */
    final boolean logDrivesSwitches;

    /**
     * Whether the go-home button is a switch (SA) rather than the RC Plus 2's H key. The H key
     * is a modifier for the 5-way - one press puts it back to normal, two make it trim the left
     * stick, three the right one - and holding it stands in for a long press of the return
     * button. On the RC Pro (landing button), the RC Plus (return-to-home key) and the RC Pro 2
     * it is a button the user drives a switch with instead.
     */
    final boolean goHomeLands;

    /** Whether the flight-mode switch reports {@code SWITCH_ONE} at the top. */
    final boolean proFlightMode;

    private RcModel(String[] devices, String label, int video, int pause, int photo,
                    int c1, int c2, int c3, int round, boolean c3OnSdk,
                    boolean logDrivesSwitches, boolean goHomeLands, boolean proFlightMode) {
        this.devices = devices;
        this.label = label;
        this.video = video;
        this.pause = pause;
        this.photo = photo;
        this.c1 = c1;
        this.c2 = c2;
        this.c3 = c3;
        this.round = round;
        this.c3OnSdk = c3OnSdk;
        this.logDrivesSwitches = logDrivesSwitches;
        this.goHomeLands = goHomeLands;
        this.proFlightMode = proFlightMode;
    }

    private static RcModel find(String device) {
        for (RcModel model : MODELS) {
            for (String known : model.devices) {
                // A prefix, so the model revisions of one remote (rm510b/rm510bv = RC Pro
                // Enterprise, rm700_enterprise = RC Plus Enterprise) are that remote too.
                // rc_model.h matches the same way.
                if (device.startsWith(known)) {
                    return model;
                }
            }
        }
        Log.i(TAG, "dji: unrecognised remote '" + device
                + "', using the RC Plus 2's layout (see RcModel)");
        return MODELS[0];
    }

    /** The switch name the log prints for a board index, "none" for {@link #NO_SWITCH}. */
    static String switchName(int index) {
        return index < SA || index > SJ ? "none" : "S" + (char) ('A' + index);
    }

    /** Every control this row puts somewhere, as one line for the start-up log. */
    String describe() {
        return "video " + switchName(video)
                + ", pause " + switchName(pause)
                + ", photo " + switchName(photo)
                + ", C1 " + switchName(c1)
                + ", C2 " + switchName(c2)
                + ", C3 " + switchName(c3) + (c3OnSdk ? " (SDK key)" : " (log only)")
                + ", round " + switchName(round)
                + "; go-home " + (goHomeLands ? "switch SA" : "5-way trim modifier")
                + "; flight mode top = " + (proFlightMode ? "SWITCH_ONE" : "SWITCH_TWO")
                + "; the remote's log " + (logDrivesSwitches ? "may move switches" : "is only merged");
    }
}
