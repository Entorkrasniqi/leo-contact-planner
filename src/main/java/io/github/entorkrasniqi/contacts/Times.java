package io.github.entorkrasniqi.contacts;

import java.util.Locale;

import org.orekit.time.AbsoluteDate;
import org.orekit.time.DateTimeComponents;
import org.orekit.time.TimeScale;

/** Small time-formatting helper shared by the CSV and SVG writers. */
final class Times {

    private Times() { }

    /**
     * ISO-8601 UTC timestamp rounded to the nearest millisecond, e.g. {@code 2026-10-24T08:01:46.644Z}.
     * (Orekit's own formatter prints the full double-precision fraction, which is noise in a plan.)
     */
    static String iso(AbsoluteDate date, TimeScale utc) {
        final DateTimeComponents c = date.shiftedBy(0.0005).getComponents(utc);   // +0.5 ms, then truncate = round
        final double seconds = Math.floor(c.getTime().getSecond() * 1000.0) / 1000.0;
        return String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%06.3fZ", c.getDate().getYear(),
                c.getDate().getMonth(), c.getDate().getDay(), c.getTime().getHour(), c.getTime().getMinute(), seconds);
    }
}
