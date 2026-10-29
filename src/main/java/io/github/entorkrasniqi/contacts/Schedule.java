package io.github.entorkrasniqi.contacts;

import java.util.List;

/**
 * The outcome of a scheduling run.
 *
 * @param scheduler    name of the algorithm that produced it
 * @param selected     the passes to actually use, in time order
 * @param exact        true if the algorithm guarantees the highest possible total volume
 */
public record Schedule(String scheduler, List<Pass> selected, boolean exact) {

    public double totalVolumeMbit() {
        return selected.stream().mapToDouble(Pass::volumeMbit).sum();
    }

    public double totalContactSeconds() {
        return selected.stream().mapToDouble(Pass::durationSeconds).sum();
    }
}
