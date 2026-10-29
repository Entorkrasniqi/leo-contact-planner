package io.github.entorkrasniqi.contacts;

import java.util.List;

/**
 * Chooses which of the possible passes to actually use.
 *
 * <h2>The scheduling problem</h2>
 * Every predicted pass is an <i>opportunity</i>, but not all can be used:
 * <ul>
 *   <li><b>One antenna, one satellite at a time.</b> Two passes at the same station may not overlap -
 *       and the antenna needs a short <i>guard time</i> to re-point between them.</li>
 *   <li><b>One satellite, one station at a time.</b> A satellite cannot downlink to two stations
 *       simultaneously.</li>
 * </ul>
 * Goal: maximise the total downlinked data volume. In graph terms: passes are vertices, "these two
 * conflict" are edges, and we want the maximum-weight <i>independent set</i> (a set of vertices with no
 * edge between them).
 */
public interface Scheduler {

    String name();

    /**
     * @param candidates   all predicted passes
     * @param guardSeconds minimum gap between two passes at the same station
     */
    Schedule schedule(List<Pass> candidates, double guardSeconds);

    /**
     * Do two passes compete for the same resource?
     * Same station: the guard time is added to both. Same satellite: plain overlap.
     */
    static boolean conflict(Pass a, Pass b, double guardSeconds) {
        final boolean sameStation = a.station().equals(b.station());
        final boolean sameSatellite = a.satellite().equals(b.satellite());
        if (!sameStation && !sameSatellite) {
            return false;
        }
        final double guard = sameStation ? guardSeconds : 0.0;
        // intervals [aosA, losA] and [aosB, losB] (padded by the guard) overlap iff each starts before the other ends
        return a.aos().durationFrom(b.los()) < guard && b.aos().durationFrom(a.los()) < guard;
    }
}
