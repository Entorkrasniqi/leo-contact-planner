package io.github.entorkrasniqi.contacts;

import static io.github.entorkrasniqi.contacts.TestData.pass;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SchedulerTest {

    @BeforeAll
    static void setUp() {
        TestData.initOrekit();
    }

    // ------------------------------------------------------------------ the conflict rule

    @Test
    void overlappingPassesAtOneStationConflict() {
        assertTrue(Scheduler.conflict(pass("S1", "A", 0, 100, 1), pass("S2", "A", 50, 150, 1), 0.0));
    }

    @Test
    void passesAtOneStationNeedTheGuardTimeBetweenThem() {
        final Pass first = pass("S1", "A", 0, 100, 1);
        assertTrue(Scheduler.conflict(first, pass("S2", "A", 120, 200, 1), 30.0));      // 20 s gap < 30 s guard
        assertFalse(Scheduler.conflict(first, pass("S2", "A", 130, 200, 1), 30.0));     // exactly the guard: allowed
        assertFalse(Scheduler.conflict(first, pass("S2", "A", 120, 200, 1), 0.0));      // no guard required
    }

    @Test
    void oneSatelliteCannotTalkToTwoStationsAtOnce() {
        assertTrue(Scheduler.conflict(pass("S1", "A", 0, 100, 1), pass("S1", "B", 50, 150, 1), 30.0));
    }

    @Test
    void satelliteConflictNeedsNoGuardTime() {
        // different stations, same satellite: back-to-back is fine (no antenna has to re-point)
        assertFalse(Scheduler.conflict(pass("S1", "A", 0, 100, 1), pass("S1", "B", 100, 200, 1), 30.0));
    }

    @Test
    void unrelatedPassesNeverConflict() {
        assertFalse(Scheduler.conflict(pass("S1", "A", 0, 100, 1), pass("S2", "B", 0, 100, 1), 30.0));
    }

    // ------------------------------------------------------------------ why greedy is not enough

    /** One long valuable pass blocks two shorter passes that are worth more together. */
    private static List<Pass> trap() {
        return List.of(pass("S1", "A", 0, 10, 10.0),     // A: worth 10, blocks both of the others
                pass("S2", "A", 0, 5, 6.0),              // B: worth 6
                pass("S3", "A", 5, 10, 6.0));            // C: worth 6, compatible with B
    }

    @Test
    void greedyCanBeBeatenByTwoSmallerPasses() {
        final Schedule greedy = new GreedyScheduler().schedule(trap(), 0.0);
        final Schedule exact = new ExactScheduler().schedule(trap(), 0.0);
        assertEquals(10.0, greedy.totalVolumeMbit(), 1e-9);
        assertEquals(12.0, exact.totalVolumeMbit(), 1e-9);
        assertEquals(2, exact.selected().size());
        assertFalse(greedy.exact());
        assertTrue(exact.exact());
    }

    // ------------------------------------------------------------------ properties on random instances

    private static List<Pass> randomPasses(Random random, int count) {
        final String[] stations = {"A", "B"};
        final String[] satellites = {"S1", "S2", "S3"};
        final List<Pass> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final double start = random.nextDouble() * 600.0;
            final double duration = 60.0 + random.nextDouble() * 240.0;
            list.add(pass(satellites[random.nextInt(3)], stations[random.nextInt(2)], start, start + duration,
                    duration * (0.5 + random.nextDouble())));
        }
        return list;
    }

    private static boolean conflictFree(List<Pass> chosen, double guard) {
        for (int i = 0; i < chosen.size(); i++) {
            for (int j = i + 1; j < chosen.size(); j++) {
                if (Scheduler.conflict(chosen.get(i), chosen.get(j), guard)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Try every subset - only feasible for tiny inputs, which is exactly why it is a good referee. */
    private static double bruteForceBest(List<Pass> passes, double guard) {
        final int n = passes.size();
        double best = 0.0;
        for (int mask = 0; mask < (1 << n); mask++) {
            final List<Pass> subset = new ArrayList<>();
            double value = 0.0;
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(passes.get(i));
                    value += passes.get(i).volumeMbit();
                }
            }
            if (value > best && conflictFree(subset, guard)) {
                best = value;
            }
        }
        return best;
    }

    @Test
    void exactSchedulerMatchesBruteForceAndGreedyNeverBeatsIt() {
        final Random random = new Random(20261024L);
        int greedyStrictlyWorse = 0;
        for (int trial = 0; trial < 300; trial++) {
            final List<Pass> passes = randomPasses(random, 6 + random.nextInt(8));   // 6..13 passes
            final Schedule greedy = new GreedyScheduler().schedule(passes, 30.0);
            final Schedule exact = new ExactScheduler().schedule(passes, 30.0);
            assertTrue(conflictFree(greedy.selected(), 30.0));
            assertTrue(conflictFree(exact.selected(), 30.0));
            assertEquals(bruteForceBest(passes, 30.0), exact.totalVolumeMbit(), 1e-6);
            assertTrue(greedy.totalVolumeMbit() <= exact.totalVolumeMbit() + 1e-9);
            if (greedy.totalVolumeMbit() < exact.totalVolumeMbit() - 1e-9) {
                greedyStrictlyWorse++;
            }
        }
        assertTrue(greedyStrictlyWorse > 0, "random instances should include cases where greedy loses");
    }

    @Test
    void schedulesAreReturnedInTimeOrder() {
        final List<Pass> passes = randomPasses(new Random(7), 12);
        for (Scheduler s : List.of(new GreedyScheduler(), new ExactScheduler())) {
            final List<Pass> chosen = s.schedule(passes, 30.0).selected();
            for (int i = 1; i < chosen.size(); i++) {
                assertFalse(chosen.get(i).aos().isBefore(chosen.get(i - 1).aos()));
            }
        }
    }

    // ------------------------------------------------------------------ edge cases and limits

    @Test
    void emptyInputGivesAnEmptySchedule() {
        for (Scheduler s : List.of(new GreedyScheduler(), new ExactScheduler())) {
            final Schedule schedule = s.schedule(List.of(), 30.0);
            assertTrue(schedule.selected().isEmpty());
            assertEquals(0.0, schedule.totalVolumeMbit(), 0.0);
        }
    }

    @Test
    void manyIndependentClustersAreSolvedExactlyAndFast() {
        // 500 passes in clusters of 4 mutually-overlapping passes, clusters far apart in time
        final List<Pass> passes = new ArrayList<>();
        final Random random = new Random(1);
        double expected = 0.0;
        for (int cluster = 0; cluster < 125; cluster++) {
            double clusterBest = 0.0;
            for (int k = 0; k < 4; k++) {
                final double value = 1.0 + random.nextInt(100);
                clusterBest = Math.max(clusterBest, value);
                passes.add(pass("S" + k, "A", cluster * 10_000.0 + k, cluster * 10_000.0 + 300.0, value));
            }
            expected += clusterBest;   // all four overlap at one station, so exactly one can be used
        }
        final Schedule exact = new ExactScheduler().schedule(passes, 30.0);
        assertTrue(exact.exact());
        assertEquals(125, exact.selected().size());
        assertEquals(expected, exact.totalVolumeMbit(), 1e-9);
    }

    @Test
    void anOversizedComponentFallsBackToGreedyAndSaysSo() {
        final List<Pass> clique = new ArrayList<>();
        for (int i = 0; i < ExactScheduler.MAX_COMPONENT + 10; i++) {
            clique.add(pass("S" + i, "A", 0, 300, 1.0 + i));   // everybody overlaps everybody at station A
        }
        final Schedule schedule = new ExactScheduler().schedule(clique, 30.0);
        assertFalse(schedule.exact());
        assertEquals(1, schedule.selected().size());
        assertEquals(ExactScheduler.MAX_COMPONENT + 10.0, schedule.totalVolumeMbit(), 1e-9);   // greedy took the best one
    }
}
