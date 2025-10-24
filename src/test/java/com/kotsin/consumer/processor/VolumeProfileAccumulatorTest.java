package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.VolumeProfileData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VolumeProfileAccumulatorTest {

    private VolumeProfileAccumulator acc;

    @BeforeEach
    void setUp() {
        acc = new VolumeProfileAccumulator();
    }

    @Test
    @DisplayName("Empty: returns empty profile")
    void testEmptyProfile() {
        VolumeProfileData vp = acc.calculate();
        assertNull(vp.getPoc());
        assertEquals(0L, vp.getPocVolume());
        assertEquals(0L, vp.getTotalVolume());
        assertNotNull(vp.getVolumeAtPrice());
        assertTrue(vp.getVolumeAtPrice().isEmpty());
    }

    @Test
    @DisplayName("POC and totals computed correctly")
    void testPocAndTotals() {
        acc.addTrade(100.00, 50);
        acc.addTrade(100.05, 30);
        acc.addTrade(99.95, 20);

        VolumeProfileData vp = acc.calculate();
        assertEquals(100.00, vp.getPoc());
        assertEquals(50L, vp.getPocVolume());
        assertEquals(100L, vp.getTotalVolume());
        assertEquals(50.0, vp.getPocVolumePercent(), 1e-6);
    }

    @Test
    @DisplayName("Value Area 70% around POC (POC alone satisfies)")
    void testValueAreaSeventyPercent() {
        // POC=70, others 10 and 20 -> target=70 so POC alone suffices
        acc.addTrade(100.00, 70);
        acc.addTrade(99.95, 10);
        acc.addTrade(100.05, 20);

        VolumeProfileData vp = acc.calculate();
        assertEquals(100.00, vp.getPoc());
        assertEquals(70L, vp.getValueAreaVolume());
        assertEquals(100.00, vp.getValueAreaLow());
        assertEquals(100.00, vp.getValueAreaHigh());
        assertEquals(70.0, vp.getValueAreaVolumePercent(), 1e-6);
    }

    @Test
    @DisplayName("Percentages, average price and std dev")
    void testPercentagesAndStats() {
        // Prices and volumes
        acc.addTrade(100.00, 50); // contributes 5000 to sum
        acc.addTrade(100.05, 30); // adds 3001.5
        acc.addTrade(99.95, 20);  // adds 1999.0
        // Total volume = 100, sumPxVol = 5000 + 3001.5 + 1999.0 = 10000.5

        VolumeProfileData vp = acc.calculate();
        assertEquals(100L, vp.getTotalVolume());

        // Average price ≈ 100.005
        assertEquals(100.005, vp.getAvgPrice(), 1e-6);

        // Check one percentage exactly
        Map<Double, Double> pct = vp.getVolumePercentages();
        assertEquals(50.0, pct.get(100.00), 1e-6);

        // Std dev should be small but positive
        assertNotNull(vp.getVolumeStdDev());
        assertTrue(vp.getVolumeStdDev() > 0.0);
    }

    @Test
    @DisplayName("Tick-size rounding aggregates nearby prices")
    void testTickSizeRounding() {
        acc.addTrade(100.03, 10); // rounds to 100.05
        acc.addTrade(100.04, 20); // rounds to 100.05

        VolumeProfileData vp = acc.calculate();
        assertEquals(30L, vp.getTotalVolume());
        assertEquals(100.05, vp.getPoc(), 1e-9);
        assertEquals(30L, vp.getPocVolume());
        assertNotNull(vp.getVolumeAtPrice());
        assertEquals(1, vp.getVolumeAtPrice().size());
        Double onlyKey = vp.getVolumeAtPrice().keySet().iterator().next();
        assertEquals(100.05, onlyKey, 1e-9);
        assertEquals(30L, vp.getVolumeAtPrice().get(onlyKey));
    }

    @Test
    @DisplayName("Reset clears internal state")
    void testReset() {
        acc.addTrade(100.00, 10);
        acc.reset();
        VolumeProfileData vp = acc.calculate();
        assertNull(vp.getPoc());
        assertEquals(0L, vp.getTotalVolume());
    }
}
