package io.github.entorkrasniqi.contacts;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;

/**
 * Command-line entry point. Typical use:
 * <pre>
 * java -cp ... io.github.entorkrasniqi.contacts.Main --tle data/demo_sats.tle --stations data/stations.csv \
 *      --start 2026-10-24T00:00:00Z --hours 24 --scheduler both --out out
 * </pre>
 * Writes {@code passes.csv}, {@code schedule_<name>.csv} and {@code timeline_<name>.svg} into {@code --out},
 * and prints a summary to the console.
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Testable entry point: returns the process exit code instead of exiting. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        final Map<String, String> opt;
        try {
            opt = parseArgs(args);
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println();
            err.println(USAGE);
            return 2;
        }
        if (opt.containsKey("help")) {
            out.println(USAGE);
            return 0;
        }
        try {
            return execute(opt, out);
        } catch (IllegalArgumentException | IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static final String USAGE = String.join("\n",
            "Usage: Main --tle FILE --stations FILE [options]",
            "",
            "  --tle FILE          TLE file (2- or 3-line format)                       [required]",
            "  --stations FILE     CSV: name,lat_deg,lon_deg,alt_m,min_elevation_deg    [required]",
            "  --start TIME        window start, ISO-8601 UTC (default: epoch of the first TLE)",
            "  --hours N           window length in hours                               [default 24]",
            "  --scheduler NAME    greedy | exact | both                                [default both]",
            "  --guard SECONDS     antenna re-pointing gap between passes at one site   [default 30]",
            "  --min-el DEG        override every station's elevation mask",
            "  --data SPEC         'builtin' (leap seconds only) or path to an orekit-data directory",
            "                                                                           [default builtin]",
            "  --out DIR           output directory                                     [default out]",
            "  --help              show this text");

    private static Map<String, String> parseArgs(String[] args) {
        final Map<String, String> opt = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            final String a = args[i];
            if (a.equals("--help") || a.equals("-h")) {
                opt.put("help", "true");
            } else if (a.startsWith("--")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("option " + a + " needs a value");
                }
                final String key = a.substring(2);
                if (!List.of("tle", "stations", "start", "hours", "scheduler", "guard", "min-el", "data", "out").contains(key)) {
                    throw new IllegalArgumentException("unknown option " + a);
                }
                opt.put(key, args[++i]);
            } else {
                throw new IllegalArgumentException("unexpected argument " + a);
            }
        }
        if (!opt.containsKey("help") && (!opt.containsKey("tle") || !opt.containsKey("stations"))) {
            throw new IllegalArgumentException("--tle and --stations are required");
        }
        return opt;
    }

    private static int execute(Map<String, String> opt, PrintStream out) throws IOException {
        OrekitSetup.init(opt.getOrDefault("data", OrekitSetup.BUILTIN));
        final TimeScale utc = OrekitSetup.utc();

        final List<Satellite> sats = InputFiles.readSatellites(Path.of(opt.get("tle")));
        List<GroundStation> stations = InputFiles.readStations(Path.of(opt.get("stations")));
        if (opt.containsKey("min-el")) {
            final double mask = number(opt, "min-el");
            final List<GroundStation> overridden = new ArrayList<>();
            stations.forEach(s -> overridden.add(s.withMinElevation(mask)));
            stations = overridden;
        }
        final double hours = opt.containsKey("hours") ? number(opt, "hours") : 24.0;
        if (hours <= 0) {
            throw new IllegalArgumentException("--hours must be positive");
        }
        final double guard = opt.containsKey("guard") ? number(opt, "guard") : 30.0;
        if (guard < 0) {
            throw new IllegalArgumentException("--guard must not be negative");
        }
        final AbsoluteDate start = opt.containsKey("start")
                ? parseDate(opt.get("start"), utc)
                : sats.get(0).tle().getDate();
        final AbsoluteDate end = start.shiftedBy(hours * 3600.0);

        final List<Scheduler> schedulers = new ArrayList<>();
        switch (opt.getOrDefault("scheduler", "both")) {
            case "greedy" -> schedulers.add(new GreedyScheduler());
            case "exact" -> schedulers.add(new ExactScheduler());
            case "both" -> {
                schedulers.add(new GreedyScheduler());
                schedulers.add(new ExactScheduler());
            }
            default -> throw new IllegalArgumentException("--scheduler must be greedy, exact or both");
        }

        // 1. predict every pass of every satellite over every station
        final PassPredictor predictor = new PassPredictor(RateModel.illustrativeDefault());
        final List<Pass> candidates = new ArrayList<>();
        for (Satellite sat : sats) {
            for (GroundStation gs : stations) {
                candidates.addAll(predictor.predict(sat, gs, start, end));
            }
        }
        candidates.sort(java.util.Comparator.comparing(Pass::aos).thenComparing(Pass::station).thenComparing(Pass::satellite));

        out.printf(Locale.ROOT, "Window: %s .. %s (%.1f h)%n", Times.iso(start, utc), Times.iso(end, utc), hours);
        out.printf(Locale.ROOT, "%d satellites x %d stations -> %d possible passes, %.0f Gbit if all could be used%n",
                sats.size(), stations.size(), candidates.size(),
                candidates.stream().mapToDouble(Pass::volumeMbit).sum() / 1000.0);

        // 2. schedule
        final Map<String, Schedule> results = new LinkedHashMap<>();
        for (Scheduler scheduler : schedulers) {
            final long t0 = System.nanoTime();
            final Schedule schedule = scheduler.schedule(candidates, guard);
            final double ms = (System.nanoTime() - t0) / 1.0e6;
            results.put(scheduler.name(), schedule);
            out.printf(Locale.ROOT, "%n[%s]%s  %d of %d passes, %.1f Gbit, %.2f h of contact (%.0f ms)%n",
                    scheduler.name(), schedule.exact() ? " (optimal)" : "", schedule.selected().size(),
                    candidates.size(), schedule.totalVolumeMbit() / 1000.0, schedule.totalContactSeconds() / 3600.0, ms);
            final Map<String, Double> busy = new TreeMap<>();
            for (GroundStation gs : stations) {
                busy.put(gs.name(), 0.0);
            }
            schedule.selected().forEach(p -> busy.merge(p.station(), p.durationSeconds(), Double::sum));
            busy.forEach((name, seconds) -> out.printf(Locale.ROOT, "    %-10s busy %5.1f%% of the window%n",
                    name, 100.0 * seconds / (hours * 3600.0)));
        }
        if (results.size() == 2) {
            final double g = results.get("greedy").totalVolumeMbit();
            final double e = results.get("exact").totalVolumeMbit();
            out.printf(Locale.ROOT, "%nexact vs greedy: %+.2f Gbit (%+.2f%%)%n", (e - g) / 1000.0, g > 0 ? 100.0 * (e - g) / g : 0.0);
        }

        // 3. files
        final Path dir = Path.of(opt.getOrDefault("out", "out"));
        Files.createDirectories(dir);
        writePasses(dir.resolve("passes.csv"), candidates, results, utc);
        for (Map.Entry<String, Schedule> e : results.entrySet()) {
            writeSchedule(dir.resolve("schedule_" + e.getKey() + ".csv"), e.getValue(), utc);
            Files.writeString(dir.resolve("timeline_" + e.getKey() + ".svg"),
                    TimelineSvg.render(candidates, e.getValue(), start, end, utc), StandardCharsets.UTF_8);
        }
        out.printf("%nWrote passes.csv, schedule_*.csv and timeline_*.svg to %s%n", dir);
        return 0;
    }

    // ------------------------------------------------------------------ helpers

    private static double number(Map<String, String> opt, String key) {
        try {
            return Double.parseDouble(opt.get(key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " needs a number, got '" + opt.get(key) + "'");
        }
    }

    private static AbsoluteDate parseDate(String text, TimeScale utc) {
        try {
            return new AbsoluteDate(text, utc);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cannot read --start '" + text
                    + "' (use ISO-8601 UTC, e.g. 2026-10-24T00:00:00Z)");
        }
    }

    private static void writePasses(Path file, List<Pass> passes, Map<String, Schedule> results, TimeScale utc)
            throws IOException {
        final StringBuilder sb = new StringBuilder("satellite,station,aos_utc,los_utc,duration_s,max_elevation_deg,"
                + "aos_azimuth_deg,los_azimuth_deg,volume_mbit,clipped_start,clipped_end");
        results.keySet().forEach(name -> sb.append(",scheduled_").append(name));
        sb.append('\n');
        for (Pass p : passes) {
            sb.append(row(p, utc));
            for (Schedule s : results.values()) {
                sb.append(',').append(s.selected().contains(p));
            }
            sb.append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeSchedule(Path file, Schedule schedule, TimeScale utc) throws IOException {
        final StringBuilder sb = new StringBuilder("satellite,station,aos_utc,los_utc,duration_s,max_elevation_deg,"
                + "aos_azimuth_deg,los_azimuth_deg,volume_mbit,clipped_start,clipped_end\n");
        schedule.selected().forEach(p -> sb.append(row(p, utc)).append('\n'));
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String row(Pass p, TimeScale utc) {
        return String.format(Locale.ROOT, "%s,%s,%s,%s,%.1f,%.2f,%.1f,%.1f,%.0f,%b,%b",
                p.satellite(), p.station(), Times.iso(p.aos(), utc), Times.iso(p.los(), utc), p.durationSeconds(),
                p.maxElevationDeg(), p.aosAzimuthDeg(), p.losAzimuthDeg(), p.volumeMbit(),
                p.clippedStart(), p.clippedEnd());
    }
}
