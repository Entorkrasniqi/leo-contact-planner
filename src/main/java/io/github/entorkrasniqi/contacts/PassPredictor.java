package io.github.entorkrasniqi.contacts;

import java.util.ArrayList;
import java.util.List;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.util.FastMath;
import org.orekit.frames.Frame;
import org.orekit.frames.TopocentricFrame;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.propagation.events.ElevationDetector;
import org.orekit.propagation.events.EventsLogger;
import org.orekit.propagation.events.handlers.ContinueOnEvent;
import org.orekit.time.AbsoluteDate;

/**
 * Predicts when a satellite is visible from a ground station.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li><b>Propagate</b> the satellite's orbit from its TLE (the SGP4/SDP4 model, the standard for TLEs).
 *       Orekit's {@link TLEPropagator} does this.</li>
 *   <li><b>Watch the elevation</b> of the satellite as seen from the station. Orekit's
 *       {@link ElevationDetector} is an <i>event detector</i>: while the propagator steps forward in time it
 *       tracks the sign of (elevation - mask) and, when it changes, refines the exact crossing time by
 *       root-finding to millisecond precision. Crossing upward = AOS, downward = LOS.</li>
 *   <li><b>Describe each pass</b>: sample the elevation every second between AOS and LOS to find the
 *       maximum elevation and to integrate the {@link RateModel} into a data volume.</li>
 * </ol>
 */
public final class PassPredictor {

    /** Upper bound on the time between two elevation checks while propagating (s). Must be shorter than the shortest pass. */
    private static final double MAX_CHECK_SECONDS = 20.0;
    /** Convergence threshold of the event time search (s). */
    private static final double THRESHOLD_SECONDS = 1.0e-3;
    /** Resolution of the elevation profile used for max elevation and volume (s). */
    private static final double SAMPLE_SECONDS = 1.0;

    private final RateModel rateModel;

    public PassPredictor(RateModel rateModel) {
        this.rateModel = rateModel;
    }

    /** All passes of {@code sat} over {@code gs} between {@code start} and {@code end}, in time order. */
    public List<Pass> predict(Satellite sat, GroundStation gs, AbsoluteDate start, AbsoluteDate end) {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("the planning window must have positive length");
        }
        final TopocentricFrame topo = gs.frame();
        final double maskRad = FastMath.toRadians(gs.minElevationDeg());

        // A propagator carrying the event detector (used only to find AOS / LOS) ...
        final TLEPropagator searcher = TLEPropagator.selectExtrapolator(sat.tle());
        final EventsLogger logger = new EventsLogger();
        searcher.addEventDetector(logger.monitorDetector(
                new ElevationDetector(MAX_CHECK_SECONDS, THRESHOLD_SECONDS, topo)
                        .withConstantElevation(maskRad)
                        .withHandler(new ContinueOnEvent())));
        searcher.propagate(start, end);

        // ... and a clean one for sampling the elevation profile afterwards.
        final TLEPropagator sampler = TLEPropagator.selectExtrapolator(sat.tle());
        final Frame frame = sampler.getFrame();

        final List<Pass> passes = new ArrayList<>();
        AbsoluteDate openAos = null;
        boolean clippedStart = false;

        // If the satellite is already above the mask when the window opens, the pass began earlier.
        if (elevationDeg(sampler, topo, frame, start) >= gs.minElevationDeg()) {
            openAos = start;
            clippedStart = true;
        }
        for (EventsLogger.LoggedEvent event : logger.getLoggedEvents()) {
            if (event.isIncreasing()) {
                openAos = event.getDate();
                clippedStart = false;
            } else if (openAos != null) {
                passes.add(describe(sat, gs, sampler, topo, frame, openAos, event.getDate(), clippedStart, false));
                openAos = null;
            }
        }
        if (openAos != null) {   // still in view when the window closes
            passes.add(describe(sat, gs, sampler, topo, frame, openAos, end, clippedStart, true));
        }
        return passes;
    }

    private Pass describe(Satellite sat, GroundStation gs, TLEPropagator sampler, TopocentricFrame topo, Frame frame,
                          AbsoluteDate aos, AbsoluteDate los, boolean clippedStart, boolean clippedEnd) {
        final double duration = los.durationFrom(aos);
        double maxEl = -90.0;
        AbsoluteDate tca = aos;
        double volumeMbit = 0.0;
        for (double t = 0.0; t < duration; t += SAMPLE_SECONDS) {
            final AbsoluteDate date = aos.shiftedBy(t);
            final double el = elevationDeg(sampler, topo, frame, date);
            if (el > maxEl) {
                maxEl = el;
                tca = date;
            }
            // rectangle rule: the rate at the start of each step applies for the step (last step may be shorter)
            volumeMbit += rateModel.rateMbps(el) * FastMath.min(SAMPLE_SECONDS, duration - t);
        }
        return new Pass(sat.name(), gs.name(), aos, los, tca, maxEl,
                azimuthDeg(sampler, topo, frame, aos), azimuthDeg(sampler, topo, frame, los),
                volumeMbit, clippedStart, clippedEnd);
    }

    private static Vector3D position(TLEPropagator p, Frame frame, AbsoluteDate date) {
        return p.propagate(date).getPVCoordinates(frame).getPosition();
    }

    private static double elevationDeg(TLEPropagator p, TopocentricFrame topo, Frame frame, AbsoluteDate date) {
        return FastMath.toDegrees(topo.getElevation(position(p, frame, date), frame, date));
    }

    private static double azimuthDeg(TLEPropagator p, TopocentricFrame topo, Frame frame, AbsoluteDate date) {
        final double az = FastMath.toDegrees(topo.getAzimuth(position(p, frame, date), frame, date));
        return (az % 360.0 + 360.0) % 360.0;   // normalise to 0..360
    }
}
