package io.github.entorkrasniqi.contacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.time.AbsoluteDate;

/**
 * The reference numbers below do NOT come from this program. They were produced by the independent oracle
 * in {@code tools/crosscheck.py} (the {@code sgp4} Python package + plain NumPy geometry):
 * DEMO-SAR-1 rises above 5 degrees over Kiruna at 08:01:46.643 UTC, sets at 08:09:12.243 UTC and peaks at 14.42 degrees.
 */
class PassPredictorTest {

    private static final double TIME_TOLERANCE_S = 0.05;   // the oracle and Orekit agree to ~4 ms; allow 50 ms

    @BeforeAll
    static void setUp() {
        TestData.initOrekit();
    }

    private static List<Pass> kirunaPasses(AbsoluteDate from, AbsoluteDate to, double mask, RateModel rate) throws IOException {
        return new PassPredictor(rate).predict(TestData.demoSatellite("DEMO-SAR-1"), TestData.kiruna(mask), from, to);
    }

    @Test
    void passTimesMatchTheIndependentSgp4Oracle() throws IOException {
        final List<Pass> passes = kirunaPasses(TestData.utc(8, 0, 0), TestData.utc(8, 15, 0), 5.0, RateModel.constant(100.0));
        assertEquals(1, passes.size());
        final Pass p = passes.get(0);
        assertEquals(0.0, p.aos().durationFrom(TestData.utc(8, 1, 46.643)), TIME_TOLERANCE_S);
        assertEquals(0.0, p.los().durationFrom(TestData.utc(8, 9, 12.243)), TIME_TOLERANCE_S);
        assertEquals(14.42, p.maxElevationDeg(), 0.05);
        assertEquals(223.2, p.tca().durationFrom(p.aos()), 1.0);
        assertFalse(p.clippedStart());
        assertFalse(p.clippedEnd());
    }

    @Test
    void volumeIsRateTimesDurationForAConstantRate() throws IOException {
        final Pass p = kirunaPasses(TestData.utc(8, 0, 0), TestData.utc(8, 15, 0), 5.0, RateModel.constant(100.0)).get(0);
        assertEquals(100.0 * p.durationSeconds(), p.volumeMbit(), 1.0e-6);
    }

    @Test
    void elevationDependentRateGivesALowerVolumeThanTheTopTierEverywhere() throws IOException {
        // this low pass (max 14.4 degrees) never reaches the 300 Mbit/s tier, so its volume lies between the extremes
        final Pass p = kirunaPasses(TestData.utc(8, 0, 0), TestData.utc(8, 15, 0), 5.0, RateModel.illustrativeDefault()).get(0);
        assertTrue(p.volumeMbit() > 50.0 * p.durationSeconds());
        assertTrue(p.volumeMbit() < 150.0 * p.durationSeconds());
    }

    @Test
    void windowStartingInsideAPassIsClippedAtTheStart() throws IOException {
        final Pass p = kirunaPasses(TestData.utc(8, 5, 0), TestData.utc(8, 15, 0), 5.0, RateModel.constant(1.0)).get(0);
        assertTrue(p.clippedStart());
        assertFalse(p.clippedEnd());
        assertEquals(0.0, p.aos().durationFrom(TestData.utc(8, 5, 0)), 1.0e-9);
        assertEquals(0.0, p.los().durationFrom(TestData.utc(8, 9, 12.243)), TIME_TOLERANCE_S);
    }

    @Test
    void windowEndingInsideAPassIsClippedAtTheEnd() throws IOException {
        final Pass p = kirunaPasses(TestData.utc(8, 0, 0), TestData.utc(8, 5, 0), 5.0, RateModel.constant(1.0)).get(0);
        assertFalse(p.clippedStart());
        assertTrue(p.clippedEnd());
        assertEquals(0.0, p.los().durationFrom(TestData.utc(8, 5, 0)), 1.0e-9);
        assertEquals(0.0, p.aos().durationFrom(TestData.utc(8, 1, 46.643)), TIME_TOLERANCE_S);
    }

    @Test
    void emptyWhenNoSatelliteIsInView() throws IOException {
        // the next Kiruna pass of DEMO-SAR-1 starts at about 09:36
        assertTrue(kirunaPasses(TestData.utc(8, 10, 0), TestData.utc(9, 0, 0), 5.0, RateModel.constant(1.0)).isEmpty());
    }

    @Test
    void aHigherElevationMaskRemovesLowPasses() throws IOException {
        assertTrue(kirunaPasses(TestData.utc(8, 0, 0), TestData.utc(8, 15, 0), 20.0, RateModel.constant(1.0)).isEmpty());
    }

    @Test
    void aHigherMaskShortensAPass() throws IOException {
        final Pass low = kirunaPasses(TestData.utc(9, 30, 0), TestData.utc(9, 55, 0), 5.0, RateModel.constant(1.0)).get(0);
        final Pass high = kirunaPasses(TestData.utc(9, 30, 0), TestData.utc(9, 55, 0), 30.0, RateModel.constant(1.0)).get(0);
        assertTrue(high.durationSeconds() < low.durationSeconds());
        assertEquals(low.maxElevationDeg(), high.maxElevationDeg(), 0.01);   // same peak: it is the same pass
    }

    @Test
    void aFullDayGivesOrderedNonOverlappingPassesThatAllReachTheMask() throws IOException {
        final List<Pass> passes = kirunaPasses(TestData.utc(0, 0, 0), TestData.utc(0, 0, 0).shiftedBy(86400.0), 5.0,
                RateModel.illustrativeDefault());
        assertEquals(10, passes.size());   // also confirmed by the independent count in tools/crosscheck.py
        for (int i = 0; i < passes.size(); i++) {
            final Pass p = passes.get(i);
            assertTrue(p.los().isAfter(p.aos()));
            assertTrue(p.maxElevationDeg() >= 5.0 - 1.0e-6);
            assertTrue(p.aosAzimuthDeg() >= 0.0 && p.aosAzimuthDeg() < 360.0);
            assertTrue(p.losAzimuthDeg() >= 0.0 && p.losAzimuthDeg() < 360.0);
            if (i > 0) {
                assertTrue(p.aos().isAfter(passes.get(i - 1).los()));
            }
        }
    }

    @Test
    void rejectsAnEmptyOrReversedWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> kirunaPasses(TestData.utc(8, 0, 0), TestData.utc(8, 0, 0), 5.0, RateModel.constant(1.0)));
        assertThrows(IllegalArgumentException.class,
                () -> kirunaPasses(TestData.utc(9, 0, 0), TestData.utc(8, 0, 0), 5.0, RateModel.constant(1.0)));
    }
}
