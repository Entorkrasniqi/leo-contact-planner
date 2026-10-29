package io.github.entorkrasniqi.contacts;

import org.orekit.propagation.analytical.tle.TLE;

/**
 * A satellite described by its orbit.
 *
 * <p>A <b>TLE</b> (two-line element set) is the standard public format for a satellite's orbit: two
 * 69-character text lines containing the orbital elements at an "epoch" time. Predictions from a TLE are
 * only good for days to about a week around the epoch - orbits drift (drag, manoeuvres), so planners
 * refresh TLEs constantly.
 */
public record Satellite(String name, TLE tle) {
}
