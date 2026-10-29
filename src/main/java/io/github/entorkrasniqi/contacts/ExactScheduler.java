package io.github.entorkrasniqi.contacts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Optimal scheduler: finds the conflict-free set of passes with the highest total volume.
 *
 * <h2>Why this is feasible</h2>
 * The general problem (maximum-weight independent set) is NP-hard, but here the conflict graph falls
 * apart into many small, independent islands: passes only conflict when they overlap in time, so the passes
 * of one busy hour never interact with those of the next. We therefore
 * <ol>
 *   <li>split the passes into <b>connected components</b> of the conflict graph, and</li>
 *   <li>solve each component exactly with <b>branch and bound</b>: pick a vertex, try "take it"
 *       (which removes its neighbours) and "skip it", and prune any branch whose optimistic bound
 *       cannot beat the best solution found so far.</li>
 * </ol>
 * A component with more than {@value #MAX_COMPONENT} passes falls back to the greedy rule, and the returned
 * {@link Schedule} is then marked {@code exact = false}. (In the bundled 4-satellite / 4-station demo the
 * largest component has only 4 passes; the limit only matters for much denser inputs.)
 */
public final class ExactScheduler implements Scheduler {

    /** Largest component solved exactly (bitmasks are stored in a {@code long}). */
    static final int MAX_COMPONENT = 60;

    @Override
    public String name() {
        return "exact";
    }

    @Override
    public Schedule schedule(List<Pass> candidates, double guardSeconds) {
        final int n = candidates.size();
        final boolean[][] edge = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (Scheduler.conflict(candidates.get(i), candidates.get(j), guardSeconds)) {
                    edge[i][j] = true;
                    edge[j][i] = true;
                }
            }
        }

        final List<Pass> chosen = new ArrayList<>();
        boolean allExact = true;
        final boolean[] seen = new boolean[n];
        for (int start = 0; start < n; start++) {
            if (seen[start]) {
                continue;
            }
            // collect the connected component containing `start` (breadth-first search)
            final List<Integer> component = new ArrayList<>();
            final List<Integer> queue = new ArrayList<>(List.of(start));
            seen[start] = true;
            for (int head = 0; head < queue.size(); head++) {
                final int v = queue.get(head);
                component.add(v);
                for (int u = 0; u < n; u++) {
                    if (edge[v][u] && !seen[u]) {
                        seen[u] = true;
                        queue.add(u);
                    }
                }
            }
            if (component.size() > MAX_COMPONENT) {
                allExact = false;
                final List<Pass> sub = new ArrayList<>();
                component.forEach(i -> sub.add(candidates.get(i)));
                chosen.addAll(new GreedyScheduler().schedule(sub, guardSeconds).selected());
            } else {
                chosen.addAll(solveComponent(candidates, edge, component));
            }
        }
        chosen.sort(Comparator.comparing(Pass::aos));
        return new Schedule(name(), chosen, allExact);
    }

    // ------------------------------------------------------------------ branch and bound

    /** Solves one component. Holds the search state so that the scheduler itself stays stateless. */
    private static final class ComponentSearch {
        private final double[] weight;
        private final long[] adjacent;
        private double bestValue = -1.0;
        private long bestSet = 0L;

        ComponentSearch(double[] weight, long[] adjacent) {
            this.weight = weight;
            this.adjacent = adjacent;
        }

        /**
         * @param candidates vertices (bit mask) that may still be added
         * @param chosen     vertices already taken
         * @param value      total weight of {@code chosen}
         */
        void search(long candidates, long chosen, double value) {
            if (candidates == 0L) {
                if (value > bestValue) {
                    bestValue = value;
                    bestSet = chosen;
                }
                return;
            }
            // optimistic bound: pretend every remaining candidate could be taken
            double bound = value;
            for (long c = candidates; c != 0L; c &= c - 1) {
                bound += weight[Long.numberOfTrailingZeros(c)];
            }
            if (bound <= bestValue) {
                return;
            }
            // branch on the candidate with the most remaining conflicts (it prunes the most either way)
            int pick = -1;
            int pickDegree = -1;
            for (long c = candidates; c != 0L; c &= c - 1) {
                final int v = Long.numberOfTrailingZeros(c);
                final int degree = Long.bitCount(adjacent[v] & candidates);
                if (degree > pickDegree) {
                    pickDegree = degree;
                    pick = v;
                }
            }
            final long bit = 1L << pick;
            if (pickDegree == 0) {
                // nobody conflicts with this vertex: taking it can only help, no need to branch
                search(candidates & ~bit, chosen | bit, value + weight[pick]);
                return;
            }
            search(candidates & ~bit & ~adjacent[pick], chosen | bit, value + weight[pick]);   // take it
            search(candidates & ~bit, chosen, value);                                          // skip it
        }
    }

    private static List<Pass> solveComponent(List<Pass> all, boolean[][] edge, List<Integer> component) {
        final int m = component.size();
        final double[] weight = new double[m];
        final long[] adjacent = new long[m];
        for (int a = 0; a < m; a++) {
            weight[a] = all.get(component.get(a)).volumeMbit();
            for (int b = 0; b < m; b++) {
                if (edge[component.get(a)][component.get(b)]) {
                    adjacent[a] |= 1L << b;
                }
            }
        }
        final ComponentSearch search = new ComponentSearch(weight, adjacent);
        search.search((1L << m) - 1L, 0L, 0.0);

        final List<Pass> result = new ArrayList<>();
        for (int a = 0; a < m; a++) {
            if ((search.bestSet & (1L << a)) != 0) {
                result.add(all.get(component.get(a)));
            }
        }
        return result;
    }
}
