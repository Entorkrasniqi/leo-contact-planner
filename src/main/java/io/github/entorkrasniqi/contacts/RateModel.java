package io.github.entorkrasniqi.contacts;

import java.util.List;

/**
 * A toy model of how fast a satellite can send data at a given elevation.
 *
 * <p>Physics in one paragraph: when the satellite is low on the horizon its signal travels through more
 * atmosphere over a longer distance, so it arrives weaker; when it is high overhead the link is
 * stronger. A modem can exploit a stronger link by switching to a denser modulation/coding (more bits per
 * symbol) - exactly the BER-versus-SNR trade-off simulated in the sibling project
 * <i>ofdm-transceiver-cpp</i>. Real systems pick configurations adaptively during a pass.
 *
 * <p>This class reduces that to a staircase: the downlink rate is a function of elevation. <b>The default
 * numbers are illustrative placeholders, not any real satellite's data rate.</b>
 *
 * @param tiers elevation tiers sorted by ascending {@code minElevationDeg}
 */
public record RateModel(List<Tier> tiers) {

    /** One step of the staircase: from {@code minElevationDeg} upward the rate is {@code rateMbps}. */
    public record Tier(double minElevationDeg, double rateMbps) { }

    public RateModel {
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("a rate model needs at least one tier");
        }
        for (int i = 1; i < tiers.size(); i++) {
            if (tiers.get(i).minElevationDeg() <= tiers.get(i - 1).minElevationDeg()) {
                throw new IllegalArgumentException("tiers must be sorted by strictly ascending elevation");
            }
        }
    }

    /** Illustrative placeholder: 50 Mbit/s below 10 degrees, 150 up to 25 degrees, 300 above. */
    public static RateModel illustrativeDefault() {
        return new RateModel(List.of(new Tier(0.0, 50.0), new Tier(10.0, 150.0), new Tier(25.0, 300.0)));
    }

    /** A constant rate, handy for tests ("volume = rate x time"). */
    public static RateModel constant(double rateMbps) {
        return new RateModel(List.of(new Tier(0.0, rateMbps)));
    }

    /** Downlink rate in Mbit/s at the given elevation (degrees). */
    public double rateMbps(double elevationDeg) {
        double rate = tiers.get(0).rateMbps();
        for (Tier t : tiers) {
            if (elevationDeg >= t.minElevationDeg()) {
                rate = t.rateMbps();
            }
        }
        return rate;
    }
}
