package io.github.entorkrasniqi.contacts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.orekit.time.AbsoluteDate;

/** Shared helpers for the tests. Maven runs tests with the project folder as working directory. */
final class TestData {

    static final Path TLE_FILE = Path.of("data", "demo_sats.tle");
    static final Path STATIONS_FILE = Path.of("data", "stations.csv");

    private TestData() { }

    static void initOrekit() {
        OrekitSetup.init(OrekitSetup.BUILTIN);
    }

    static AbsoluteDate utc(int hour, int minute, double second) {
        return new AbsoluteDate(2026, 10, 24, hour, minute, second, OrekitSetup.utc());
    }

    static Satellite demoSatellite(String name) throws IOException {
        return InputFiles.readSatellites(TLE_FILE).stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    static GroundStation kiruna(double maskDeg) {
        return new GroundStation("Kiruna", 67.89, 21.11, 400.0, maskDeg);
    }

    /** A fake pass starting {@code aosSec} seconds after 2026-10-24T00:00Z - for scheduler tests that need no orbits. */
    static Pass pass(String satellite, String station, double aosSec, double losSec, double volumeMbit) {
        final AbsoluteDate base = utc(0, 0, 0.0);
        return new Pass(satellite, station, base.shiftedBy(aosSec), base.shiftedBy(losSec),
                base.shiftedBy((aosSec + losSec) / 2.0), 45.0, 0.0, 180.0, volumeMbit, false, false);
    }

    static List<String> lines(String... lines) {
        return List.of(lines);
    }
}
