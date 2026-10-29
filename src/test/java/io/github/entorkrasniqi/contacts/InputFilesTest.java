package io.github.entorkrasniqi.contacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InputFilesTest {

    private static final String L1 = "1 90001U          26297.00000000  .00000000  00000-0  10000-3 0    03";
    private static final String L2 = "2 90001  97.6400  10.0000 0012000  90.0000   0.0000 15.02238327    04";

    @BeforeAll
    static void setUp() {
        TestData.initOrekit();
    }

    @Test
    void readsTheBundledDemoFiles() throws IOException {
        final List<Satellite> sats = InputFiles.readSatellites(TestData.TLE_FILE);
        assertEquals(List.of("DEMO-SAR-1", "DEMO-SAR-2", "DEMO-SAR-3", "DEMO-SAR-4"), sats.stream().map(Satellite::name).toList());
        final List<GroundStation> stations = InputFiles.readStations(TestData.STATIONS_FILE);
        assertEquals(List.of("Svalbard", "Kiruna", "Inuvik", "Helsinki"), stations.stream().map(GroundStation::name).toList());
        assertEquals(10.0, stations.get(3).minElevationDeg(), 0.0);
    }

    /** Replace the catalogue number (columns 3-7) and recompute the checksum digit (column 69) of a TLE line. */
    private static String renumbered(String line, String number) {
        final String body = line.substring(0, 2) + number + line.substring(7, 68);
        int sum = 0;
        for (char c : body.toCharArray()) {
            if (Character.isDigit(c)) {
                sum += c - '0';
            } else if (c == '-') {
                sum += 1;
            }
        }
        return body + (sum % 10);
    }

    @Test
    void parsesNamedAndUnnamedElementSetsSkippingCommentsAndBlankLines() {
        final List<Satellite> sats = InputFiles.parseSatellites(
                List.of("# a comment", "", "MY-SAT", L1, L2, "", renumbered(L1, "90002"), renumbered(L2, "90002")), "test");
        assertEquals(2, sats.size());
        assertEquals("MY-SAT", sats.get(0).name());
        assertEquals("SAT-90002", sats.get(1).name());   // no name line: derived from the catalogue number
    }

    @Test
    void aCorruptedChecksumIsRejectedWithAUsefulMessage() {
        final String broken = L1.substring(0, 68) + "9";   // the correct checksum digit is 3
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InputFiles.parseSatellites(List.of("BROKEN", broken, L2), "sats.tle"));
        assertTrue(e.getMessage().contains("sats.tle"));
        assertTrue(e.getMessage().contains("BROKEN"));
    }

    @Test
    void missingSecondLineIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> InputFiles.parseSatellites(List.of("NAME", L1), "t"));
    }

    @Test
    void emptyTleFileIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> InputFiles.parseSatellites(List.of("# nothing here"), "t"));
    }

    @Test
    void parsesStationsAndIgnoresCommentsAndWhitespace() {
        final List<GroundStation> s = InputFiles.parseStations(
                List.of("# header", "", " Kiruna , 67.89 , 21.11 , 400 , 5 "), "stations.csv");
        assertEquals(1, s.size());
        assertEquals("Kiruna", s.get(0).name());
        assertEquals(67.89, s.get(0).latDeg(), 0.0);
        assertEquals(5.0, s.get(0).minElevationDeg(), 0.0);
    }

    @Test
    void stationErrorsNameTheFileAndLine() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InputFiles.parseStations(List.of("# ok", "Kiruna,67.89,21.11,400"), "stations.csv"));
        assertTrue(e.getMessage().contains("stations.csv:2"));
        e = assertThrows(IllegalArgumentException.class,
                () -> InputFiles.parseStations(List.of("Kiruna,north,21.11,400,5"), "stations.csv"));
        assertTrue(e.getMessage().contains("stations.csv:1"));
    }

    @Test
    void stationValuesAreRangeChecked() {
        assertThrows(IllegalArgumentException.class, () -> InputFiles.parseStations(List.of("X,91,0,0,5"), "t"));
        assertThrows(IllegalArgumentException.class, () -> InputFiles.parseStations(List.of("X,0,0,0,95"), "t"));
        assertThrows(IllegalArgumentException.class, () -> InputFiles.parseStations(List.of("X,0,0,0,-1"), "t"));
        assertThrows(IllegalArgumentException.class, () -> InputFiles.parseStations(List.of("# only a comment"), "t"));
    }
}
