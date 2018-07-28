package net.octyl.beatdropper.droppers;

import java.util.concurrent.TimeUnit;

public class BeatDropUtils {

    public static long requestedTimeForOneBeat(int bpm) {
        // have: beats per minute
        // want: one beat's worth of samples
        // want: time for one beat
        // want: millis per beat
        // -- get: minutes per beat
        double minPerBeat = 1.0 / bpm;
        // -- get: mills per beat
        long millisPerBeat = (long) (TimeUnit.MINUTES.toMillis(1) * minPerBeat);
        return millisPerBeat;
    }

}
