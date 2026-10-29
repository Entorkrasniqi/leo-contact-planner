package io.github.entorkrasniqi.contacts;

import org.hipparchus.util.FastMath;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.frames.TopocentricFrame;

/**
 * A ground station (antenna site).
 *
 * @param name             human-readable name
 * @param latDeg           geodetic latitude, degrees north
 * @param lonDeg           longitude, degrees east
 * @param altM             height above the ellipsoid, metres
 * @param minElevationDeg  the "elevation mask": the satellite only counts as visible when it is at least
 *                         this many degrees above the horizon (terrain, buildings and antenna limits make
 *                         very low passes useless)
 */
public record GroundStation(String name, double latDeg, double lonDeg, double altM, double minElevationDeg) {

    public GroundStation {
        if (latDeg < -90 || latDeg > 90) {
            throw new IllegalArgumentException("latitude out of range for " + name + ": " + latDeg);
        }
        if (lonDeg < -180 || lonDeg > 360) {
            throw new IllegalArgumentException("longitude out of range for " + name + ": " + lonDeg);
        }
        if (minElevationDeg < 0 || minElevationDeg >= 90) {
            throw new IllegalArgumentException("elevation mask out of range for " + name + ": " + minElevationDeg);
        }
    }

    /**
     * The station's local coordinate frame: origin at the antenna, axes east / north / zenith (up).
     * Orekit computes elevation and azimuth of anything relative to this frame.
     */
    public TopocentricFrame frame() {
        final GeodeticPoint point = new GeodeticPoint(FastMath.toRadians(latDeg), FastMath.toRadians(lonDeg), altM);
        return new TopocentricFrame(OrekitSetup.earth(), point, name);
    }

    public GroundStation withMinElevation(double newMaskDeg) {
        return new GroundStation(name, latDeg, lonDeg, altM, newMaskDeg);
    }
}
