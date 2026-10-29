package io.github.entorkrasniqi.contacts;

import java.io.File;

import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.data.ClasspathCrawler;
import org.orekit.data.DataContext;
import org.orekit.data.DataProvidersManager;
import org.orekit.data.DirectoryCrawler;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;

/**
 * One-time configuration of the Orekit library.
 *
 * <p>Orekit does not hard-code the facts it needs about time and the Earth - it loads them from
 * <em>data files</em>. The two that matter here:
 * <ul>
 *   <li><b>UTC-TAI.history</b> - the list of leap seconds, needed to convert between UTC (what
 *       humans use) and the continuous time scales used by orbit mathematics.</li>
 *   <li><b>EOP files</b> (Earth Orientation Parameters) - tiny daily corrections to how the Earth is
 *       oriented. Worth up to a few hundred metres on the ground; irrelevant for pass prediction from
 *       TLEs (whose own error is kilometres).</li>
 * </ul>
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@code "builtin"} (default): uses the small leap-second table bundled in this project's
 *       resources. No EOP data. Good for everything this project demonstrates.</li>
 *   <li>a directory path: a full {@code orekit-data} checkout
 *       ({@code git clone https://gitlab.orekit.org/orekit/orekit-data.git}) for maximum accuracy.</li>
 * </ul>
 *
 * <p>Orekit's data configuration is process-wide, so the <em>first</em> call to {@link #init} wins and
 * later calls are ignored.
 */
public final class OrekitSetup {

    public static final String BUILTIN = "builtin";
    private static boolean initialised = false;

    private OrekitSetup() { }

    public static synchronized void init(String dataSpec) {
        if (initialised) {
            return;
        }
        final DataProvidersManager manager = DataContext.getDefault().getDataProvidersManager();
        if (dataSpec == null || dataSpec.isBlank() || BUILTIN.equals(dataSpec)) {
            manager.addProvider(new ClasspathCrawler("orekit-minimal/UTC-TAI.history"));
        } else {
            final File dir = new File(dataSpec);
            if (!dir.isDirectory()) {
                throw new IllegalArgumentException("Orekit data directory not found: " + dir.getAbsolutePath()
                        + " (get it with: git clone https://gitlab.orekit.org/orekit/orekit-data.git"
                        + " - or use --data builtin)");
            }
            manager.addProvider(new DirectoryCrawler(dir));
        }
        initialised = true;
    }

    /** The UTC time scale (leap seconds included). */
    public static TimeScale utc() {
        return TimeScalesFactory.getUTC();
    }

    /** The Earth-fixed frame (ITRF): the frame that rotates with the planet, in which ground stations sit still. */
    public static Frame itrf() {
        return FramesFactory.getITRF(IERSConventions.IERS_2010, true);
    }

    /** The WGS-84 reference ellipsoid, the standard model of the Earth's shape used by GPS and maps. */
    public static OneAxisEllipsoid earth() {
        return new OneAxisEllipsoid(Constants.WGS84_EARTH_EQUATORIAL_RADIUS, Constants.WGS84_EARTH_FLATTENING, itrf());
    }
}
