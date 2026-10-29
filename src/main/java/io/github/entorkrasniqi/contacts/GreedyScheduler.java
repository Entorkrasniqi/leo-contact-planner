package io.github.entorkrasniqi.contacts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Greedy heuristic: go through the passes from most to least valuable and keep each one that does not
 * conflict with anything already kept.
 *
 * <p>Fast and easy to explain, and often good - but <b>not optimal</b>. Classic failure: one long, valuable
 * pass overlaps two shorter passes that are together worth more; greedy grabs the long one first and can
 * never recover. See {@code SchedulerTest.greedyCanBeBeatenByTwoSmallerPasses}.
 */
public final class GreedyScheduler implements Scheduler {

    @Override
    public String name() {
        return "greedy";
    }

    @Override
    public Schedule schedule(List<Pass> candidates, double guardSeconds) {
        final List<Pass> ranked = new ArrayList<>(candidates);
        // most valuable first; ties broken by earlier start so the result is deterministic
        ranked.sort(Comparator.comparingDouble(Pass::volumeMbit).reversed().thenComparing(Pass::aos));
        final List<Pass> chosen = new ArrayList<>();
        for (Pass p : ranked) {
            boolean free = true;
            for (Pass q : chosen) {
                if (Scheduler.conflict(p, q, guardSeconds)) {
                    free = false;
                    break;
                }
            }
            if (free) {
                chosen.add(p);
            }
        }
        chosen.sort(Comparator.comparing(Pass::aos));
        return new Schedule(name(), chosen, false);
    }
}
