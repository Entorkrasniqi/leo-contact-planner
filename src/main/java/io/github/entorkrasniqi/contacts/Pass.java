package io.github.entorkrasniqi.contacts;

import org.orekit.time.AbsoluteDate;

/**
 * One <b>pass</b> (also "contact" or "visibility window"): the period during which one satellite is above
 * one station's elevation mask.
 *
 * @param satellite       satellite name
 * @param station         station name
 * @param aos             Acquisition Of Signal - the moment the satellite rises above the mask
 * @param los             Loss Of Signal - the moment it drops below again
 * @param tca             Time of Closest Approach, i.e. of maximum elevation (sampled at 1 s resolution)
 * @param maxElevationDeg highest elevation reached, degrees
 * @param aosAzimuthDeg   compass direction (0 = north, 90 = east) where the satellite appears
 * @param losAzimuthDeg   compass direction where it disappears
 * @param volumeMbit      data that could be downlinked during the pass according to the {@link RateModel}
 * @param clippedStart    true if the satellite was already visible when the planning window began
 *                        (AOS is then the window start, not the true rise time)
 * @param clippedEnd      true if the pass was still in progress when the window ended
 */
public record Pass(String satellite, String station, AbsoluteDate aos, AbsoluteDate los, AbsoluteDate tca,
                   double maxElevationDeg, double aosAzimuthDeg, double losAzimuthDeg, double volumeMbit,
                   boolean clippedStart, boolean clippedEnd) {

    public double durationSeconds() {
        return los.durationFrom(aos);
    }
}
