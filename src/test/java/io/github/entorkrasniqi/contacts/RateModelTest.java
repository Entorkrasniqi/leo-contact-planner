package io.github.entorkrasniqi.contacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class RateModelTest {

    @Test
    void defaultStaircaseHasTheDocumentedSteps() {
        final RateModel m = RateModel.illustrativeDefault();
        assertEquals(50.0, m.rateMbps(0.0), 0.0);
        assertEquals(50.0, m.rateMbps(9.99), 0.0);
        assertEquals(150.0, m.rateMbps(10.0), 0.0);   // the lower edge of a tier belongs to that tier
        assertEquals(150.0, m.rateMbps(24.99), 0.0);
        assertEquals(300.0, m.rateMbps(25.0), 0.0);
        assertEquals(300.0, m.rateMbps(90.0), 0.0);
    }

    @Test
    void belowTheFirstTierTheFirstRateApplies() {
        assertEquals(50.0, RateModel.illustrativeDefault().rateMbps(-3.0), 0.0);
    }

    @Test
    void constantModelIgnoresElevation() {
        final RateModel m = RateModel.constant(42.0);
        assertEquals(42.0, m.rateMbps(0.0), 0.0);
        assertEquals(42.0, m.rateMbps(75.0), 0.0);
    }

    @Test
    void rejectsEmptyOrUnsortedTiers() {
        assertThrows(IllegalArgumentException.class, () -> new RateModel(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RateModel(List.of(new RateModel.Tier(10.0, 1.0), new RateModel.Tier(5.0, 2.0))));
        assertThrows(IllegalArgumentException.class,
                () -> new RateModel(List.of(new RateModel.Tier(5.0, 1.0), new RateModel.Tier(5.0, 2.0))));
    }
}
