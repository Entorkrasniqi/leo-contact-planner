package io.github.entorkrasniqi.contacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;

/**
 * Draws the plan as a Gantt-style SVG: one row per ground station, time running left to right.
 * Filled bars = passes in the schedule, dashed outlines = passes that were possible but not used.
 * No libraries needed - SVG is just text.
 */
public final class TimelineSvg {

    // Okabe-Ito colour-blind-safe palette
    private static final String[] COLORS = {"#0072B2", "#D55E00", "#009E73", "#CC79A7", "#E69F00", "#56B4E9", "#7B7B7B"};
    private static final int LEFT = 150, RIGHT = 20, TOP = 76, ROW = 46, BAR = 26, WIDTH = 1100;

    private TimelineSvg() { }

    public static String render(List<Pass> candidates, Schedule schedule, AbsoluteDate start, AbsoluteDate end,
                                TimeScale utc) {
        final TreeSet<String> stations = new TreeSet<>();
        final TreeSet<String> satellites = new TreeSet<>();
        for (Pass p : candidates) {
            stations.add(p.station());
            satellites.add(p.satellite());
        }
        final List<String> stationList = new ArrayList<>(stations);
        final List<String> satList = new ArrayList<>(satellites);
        final double span = end.durationFrom(start);
        final double plotWidth = WIDTH - LEFT - RIGHT;
        final int height = TOP + ROW * stationList.size() + 70;

        final StringBuilder svg = new StringBuilder(8192);
        svg.append(String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\" "
                        + "font-family=\"Helvetica, Arial, sans-serif\" font-size=\"12\">%n", WIDTH, height, WIDTH, height));
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
        svg.append(String.format(Locale.ROOT,
                "<text x=\"%d\" y=\"24\" font-size=\"15\" font-weight=\"bold\">Contact plan (%s): %d of %d passes scheduled, %.0f Gbit</text>%n",
                LEFT, esc(schedule.scheduler()), schedule.selected().size(), candidates.size(),
                schedule.totalVolumeMbit() / 1000.0));
        svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"42\" fill=\"#555\">%s to %s (UTC). Filled = scheduled, dashed = possible but not used.</text>%n",
                LEFT, esc(Times.iso(start, utc)), esc(Times.iso(end, utc))));

        // hour grid
        final double hours = span / 3600.0;
        final int step = hours > 36 ? 6 : hours > 12 ? 2 : 1;
        for (int h = 0; h <= hours; h += step) {
            final double x = LEFT + plotWidth * (h * 3600.0 / span);
            svg.append(String.format(Locale.ROOT,
                    "<line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" stroke=\"#ddd\"/>%n", x, TOP - 8, x, TOP + ROW * stationList.size()));
            svg.append(String.format(Locale.ROOT,
                    "<text x=\"%.1f\" y=\"%d\" text-anchor=\"middle\" fill=\"#555\">+%dh</text>%n", x, TOP - 12, h));
        }
        // rows
        for (int r = 0; r < stationList.size(); r++) {
            final int y = TOP + r * ROW;
            svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\" text-anchor=\"end\" font-weight=\"bold\">%s</text>%n",
                    LEFT - 10, y + BAR / 2 + 5, esc(stationList.get(r))));
            svg.append(String.format(Locale.ROOT, "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#eee\"/>%n",
                    LEFT, y + ROW - 5, WIDTH - RIGHT, y + ROW - 5));
        }
        // bars: unused first (behind), then scheduled
        for (boolean scheduledPass : new boolean[] {false, true}) {
            for (Pass p : candidates) {
                final boolean isScheduled = schedule.selected().contains(p);
                if (isScheduled != scheduledPass) {
                    continue;
                }
                final int row = stationList.indexOf(p.station());
                final String color = COLORS[satList.indexOf(p.satellite()) % COLORS.length];
                final double x = LEFT + plotWidth * (p.aos().durationFrom(start) / span);
                final double w = Math.max(1.5, plotWidth * (p.durationSeconds() / span));
                final String tip = String.format(Locale.ROOT, "%s over %s: %s to %s UTC, %.0f s, max elevation %.1f deg, %.0f Mbit",
                        p.satellite(), p.station(), Times.iso(p.aos(), utc),
                        Times.iso(p.los(), utc), p.durationSeconds(), p.maxElevationDeg(), p.volumeMbit());
                if (isScheduled) {
                    svg.append(String.format(Locale.ROOT,
                            "<rect x=\"%.1f\" y=\"%d\" width=\"%.1f\" height=\"%d\" fill=\"%s\" rx=\"2\"><title>%s</title></rect>%n",
                            x, TOP + row * ROW, w, BAR, color, esc(tip)));
                } else {
                    svg.append(String.format(Locale.ROOT,
                            "<rect x=\"%.1f\" y=\"%d\" width=\"%.1f\" height=\"%d\" fill=\"none\" stroke=\"%s\" stroke-dasharray=\"3 2\" rx=\"2\"><title>%s</title></rect>%n",
                            x, TOP + row * ROW, w, BAR, color, esc(tip)));
                }
            }
        }
        // legend
        int lx = LEFT;
        final int ly = TOP + ROW * stationList.size() + 24;
        for (int i = 0; i < satList.size(); i++) {
            svg.append(String.format(Locale.ROOT, "<rect x=\"%d\" y=\"%d\" width=\"14\" height=\"14\" fill=\"%s\" rx=\"2\"/>%n",
                    lx, ly, COLORS[i % COLORS.length]));
            svg.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\">%s</text>%n", lx + 20, ly + 12, esc(satList.get(i))));
            lx += 30 + 8 * satList.get(i).length() + 20;
        }
        svg.append("</svg>\n");
        return svg.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
