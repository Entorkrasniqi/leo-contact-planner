package io.github.entorkrasniqi.contacts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.orekit.propagation.analytical.tle.TLE;

/**
 * Readers for the two small input files.
 *
 * <p><b>TLE file</b> - the usual 3-line format (a name line, then the two element lines), or just the
 * two lines without names. Blank lines and lines starting with '#' are ignored.
 * <pre>
 * DEMO-SAR-1
 * 1 90001U          26297.00000000  .00000000  00000-0  10000-3 0    03
 * 2 90001  97.6400  10.0000 0012000  90.0000   0.0000 15.02238327    04
 * </pre>
 *
 * <p><b>Stations CSV</b> - {@code name,lat_deg,lon_deg,alt_m,min_elevation_deg}, '#' comments allowed.
 *
 * <p>Note: parsing a TLE needs Orekit's time scales, so call {@link OrekitSetup#init} first.
 */
public final class InputFiles {

    private InputFiles() { }

    public static List<Satellite> readSatellites(Path file) throws IOException {
        return parseSatellites(Files.readAllLines(file), file.toString());
    }

    public static List<Satellite> parseSatellites(List<String> rawLines, String source) {
        final List<String> lines = new ArrayList<>();
        for (String raw : rawLines) {
            final String line = raw.stripTrailing();
            if (!line.isBlank() && !line.startsWith("#")) {
                lines.add(line);
            }
        }
        final List<Satellite> sats = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String name = null;
            if (!lines.get(i).startsWith("1 ")) {
                name = lines.get(i).strip();
                i++;
            }
            if (i + 1 >= lines.size() || !lines.get(i).startsWith("1 ") || !lines.get(i + 1).startsWith("2 ")) {
                throw new IllegalArgumentException(source + ": expected TLE lines starting with '1 ' and '2 ' near line "
                        + (i + 1) + " of the non-comment lines");
            }
            final TLE tle;
            try {
                // The TLE constructor alone does not verify the checksum digit (column 69). A single mistyped
                // character would silently give a different orbit, so check explicitly: isFormatOK throws on a
                // wrong checksum and returns false for other format problems.
                if (!TLE.isFormatOK(lines.get(i), lines.get(i + 1))) {
                    throw new IllegalArgumentException("the two lines are not a well-formed TLE");
                }
                tle = new TLE(lines.get(i), lines.get(i + 1));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(source + ": invalid TLE for " + (name == null ? "(unnamed)" : name)
                        + ": " + e.getMessage(), e);
            }
            sats.add(new Satellite(name != null ? name : "SAT-" + tle.getSatelliteNumber(), tle));
            i += 2;
        }
        if (sats.isEmpty()) {
            throw new IllegalArgumentException(source + ": no satellites found");
        }
        return sats;
    }

    public static List<GroundStation> readStations(Path file) throws IOException {
        return parseStations(Files.readAllLines(file), file.toString());
    }

    public static List<GroundStation> parseStations(List<String> lines, String source) {
        final List<GroundStation> stations = new ArrayList<>();
        int number = 0;
        for (String raw : lines) {
            number++;
            final String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final String[] f = line.split(",");
            if (f.length != 5) {
                throw new IllegalArgumentException(source + ":" + number
                        + ": expected 5 comma-separated fields (name,lat_deg,lon_deg,alt_m,min_elevation_deg)");
            }
            try {
                stations.add(new GroundStation(f[0].strip(), Double.parseDouble(f[1].strip()),
                        Double.parseDouble(f[2].strip()), Double.parseDouble(f[3].strip()),
                        Double.parseDouble(f[4].strip())));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(source + ":" + number + ": not a number in '" + line + "'", e);
            }
        }
        if (stations.isEmpty()) {
            throw new IllegalArgumentException(source + ": no stations found");
        }
        return stations;
    }
}
