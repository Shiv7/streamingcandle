package com.kotsin.consumer.calculator;

import com.kotsin.consumer.config.CalculatorConfig;
import com.kotsin.consumer.config.InstrumentConfig;
import com.kotsin.consumer.model.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CalculatorComponentTest - Tests for extracted calculator components
 *
 * Tests the refactored calculator classes that were extracted from EnrichedCandlestick:
 * - TradeClassifier
 * - VolumeProfileCalculator
 * - ImbalanceBarCalculator
 *
 * This validates ZERO technical debt by ensuring extracted components work correctly
 */
public class CalculatorComponentTest {

    private InstrumentConfig instrumentConfig;
    private CalculatorConfig calculatorConfig;

    @BeforeEach
    public void setUp() {
        // Mock configurations
        instrumentConfig = new InstrumentConfig();
        instrumentConfig.setTradeClassification(new InstrumentConfig.TradeClassification());
        instrumentConfig.getTradeClassification().setMinAbsolute(0.01);
        instrumentConfig.getTradeClassification().setBasisPoints(0.0001);
        instrumentConfig.getTradeClassification().setSpreadMultiplier(0.15);

        instrumentConfig.setTickSize(new InstrumentConfig.TickSizeDefaults());
        instrumentConfig.getTickSize().setCash(0.05);
        instrumentConfig.getTickSize().setDerivatives(0.05);

        calculatorConfig = new CalculatorConfig();
        calculatorConfig.setImbalanceBar(new CalculatorConfig.ImbalanceBarConfig());
        calculatorConfig.getImbalanceBar().setEwmaAlpha(0.1);
        calculatorConfig.getImbalanceBar().setInitVolumeImbalance(1000.0);
        calculatorConfig.getImbalanceBar().setInitDollarImbalance(100000.0);
        calculatorConfig.getImbalanceBar().setInitTickRuns(10.0);
        calculatorConfig.getImbalanceBar().setInitVolumeRuns(5000.0);
        calculatorConfig.getImbalanceBar().setZScoreThreshold(1.645);
    }

    @Test
    @DisplayName("TradeClassifier: Should classify trade at ask as BUY")
    public void testTradeClassifierBuyAtAsk() {
        // Arrange
        TradeClassifier classifier = new TradeClassifier(instrumentConfig);

        TickData tick = new TickData();
        tick.setBidRate(1000.0);
        tick.setOfferRate(1000.5);
        tick.setLastRate(1000.5); // At ask

        // Act
        boolean isBuy = classifier.classifyTrade(tick, 1000.5, 1000.0, 0.05);

        // Assert
        assertTrue(isBuy, "Trade at ask should be classified as BUY");
    }

    @Test
    @DisplayName("TradeClassifier: Should classify trade at bid as SELL")
    public void testTradeClassifierSellAtBid() {
        // Arrange
        TradeClassifier classifier = new TradeClassifier(instrumentConfig);

        TickData tick = new TickData();
        tick.setBidRate(1000.0);
        tick.setOfferRate(1000.5);
        tick.setLastRate(1000.0); // At bid

        // Act
        boolean isBuy = classifier.classifyTrade(tick, 1000.0, 1000.5, 0.05);

        // Assert
        assertFalse(isBuy, "Trade at bid should be classified as SELL");
    }

    @Test
    @DisplayName("TradeClassifier: Should use tick rule when price uptick")
    public void testTradeClassifierTickRuleUptick() {
        // Arrange
        TradeClassifier classifier = new TradeClassifier(instrumentConfig);

        TickData tick = new TickData();
        tick.setBidRate(999.0);
        tick.setOfferRate(1001.0);
        tick.setLastRate(1000.0); // Mid-spread

        // Act - Price increased from 999 to 1000
        boolean isBuy = classifier.classifyTrade(tick, 1000.0, 999.0, 0.05);

        // Assert
        assertTrue(isBuy, "Uptick should be classified as BUY");
    }

    @Test
    @DisplayName("TradeClassifier: Should use tick rule when price downtick")
    public void testTradeClassifierTickRuleDowntick() {
        // Arrange
        TradeClassifier classifier = new TradeClassifier(instrumentConfig);

        TickData tick = new TickData();
        tick.setBidRate(999.0);
        tick.setOfferRate(1001.0);
        tick.setLastRate(1000.0); // Mid-spread

        // Act - Price decreased from 1001 to 1000
        boolean isBuy = classifier.classifyTrade(tick, 1000.0, 1001.0, 0.05);

        // Assert
        assertFalse(isBuy, "Downtick should be classified as SELL");
    }

    @Test
    @DisplayName("VolumeProfileCalculator: Should calculate POC correctly")
    public void testVolumeProfilePOC() {
        // Arrange
        VolumeProfileCalculator calculator = new VolumeProfileCalculator(instrumentConfig);
        VolumeProfileCalculator.VolumeProfile profile = new VolumeProfileCalculator.VolumeProfile();

        // Add volume at different prices
        profile.addVolume(1000.0, 100L, 0.05);
        profile.addVolume(1000.5, 500L, 0.05); // Highest volume
        profile.addVolume(1001.0, 200L, 0.05);
        profile.addVolume(999.5, 150L, 0.05);

        // Act
        Double poc = calculator.calculatePOC(profile);

        // Assert
        assertNotNull(poc);
        assertEquals(1000.5, poc, 0.01, "POC should be at price with max volume");
    }

    @Test
    @DisplayName("VolumeProfileCalculator: Should calculate Value Area correctly")
    public void testVolumeProfileValueArea() {
        // Arrange
        VolumeProfileCalculator calculator = new VolumeProfileCalculator(instrumentConfig);
        VolumeProfileCalculator.VolumeProfile profile = new VolumeProfileCalculator.VolumeProfile();

        // Add volume at different prices (total: 1000)
        profile.addVolume(1000.0, 200L, 0.05);
        profile.addVolume(1000.5, 400L, 0.05); // POC
        profile.addVolume(1001.0, 200L, 0.05);
        profile.addVolume(999.5, 200L, 0.05);

        // Act
        VolumeProfileCalculator.ValueArea va = calculator.calculateValueArea(profile);

        // Assert
        assertTrue(va.isValid());
        assertTrue(va.getVolume() >= 700L, "Value Area should contain ~70% of volume");
        assertTrue(va.getHigh() >= va.getLow());
        assertTrue(va.getRange() > 0);
    }

    @Test
    @DisplayName("VolumeProfileCalculator: Should merge profiles correctly")
    public void testVolumeProfileMerge() {
        // Arrange
        VolumeProfileCalculator.VolumeProfile profile1 = new VolumeProfileCalculator.VolumeProfile();
        profile1.addVolume(1000.0, 100L, 0.05);
        profile1.addVolume(1000.5, 200L, 0.05);

        VolumeProfileCalculator.VolumeProfile profile2 = new VolumeProfileCalculator.VolumeProfile();
        profile2.addVolume(1000.5, 150L, 0.05); // Same price
        profile2.addVolume(1001.0, 100L, 0.05);

        // Act
        profile1.merge(profile2);

        // Assert
        assertEquals(550L, profile1.getTotalVolume()); // 100 + 200 + 150 + 100
        assertEquals(350L, profile1.getVolumeAtPrice().get(1000.5)); // 200 + 150
        assertEquals(999.95, profile1.getLowestPrice(), 0.1); // Adjusted for rounding
        assertEquals(1001.0, profile1.getHighestPrice(), 0.1);
    }

    @Test
    @DisplayName("ImbalanceBarCalculator: Should detect volume imbalance threshold")
    public void testImbalanceBarVIBThreshold() {
        // Arrange
        ImbalanceBarCalculator calculator = new ImbalanceBarCalculator(calculatorConfig);
        ImbalanceBarCalculator.ImbalanceState state =
            new ImbalanceBarCalculator.ImbalanceState(calculatorConfig.getImbalanceBar());

        // Act - Add trades that will exceed the initVolumeImbalance threshold of 1000
        // Each trade adds 200 volume, so 6 trades = 1200 > 1000 threshold
        for (int i = 0; i < 6; i++) {
            calculator.updateImbalanceBars(state, 1000.0, 200, true, System.currentTimeMillis());
        }

        // Assert - VIB should have triggered since cumulative volume (1200) > threshold (1000)
        assertTrue(state.isVibTriggered() || state.getLastVibTriggerTime() > 0, 
            "VIB should trigger after exceeding threshold of 1000 with 1200 cumulative volume");
    }

    @Test
    @DisplayName("ImbalanceBarCalculator: Should track tick runs correctly")
    public void testImbalanceBarTickRuns() {
        // Arrange
        ImbalanceBarCalculator calculator = new ImbalanceBarCalculator(calculatorConfig);
        ImbalanceBarCalculator.ImbalanceState state =
            new ImbalanceBarCalculator.ImbalanceState(calculatorConfig.getImbalanceBar());

        // Act - Add consecutive buy trades (tick run)
        calculator.updateImbalanceBars(state, 1000.0, 100, true, System.currentTimeMillis());
        calculator.updateImbalanceBars(state, 1000.5, 100, true, System.currentTimeMillis());
        calculator.updateImbalanceBars(state, 1001.0, 100, true, System.currentTimeMillis());

        // Then a sell (breaks the run)
        calculator.updateImbalanceBars(state, 1000.5, 100, false, System.currentTimeMillis());

        // Assert
        assertEquals("SELL", state.getCurrentDirection());
        assertTrue(state.getCurrentRunLength() >= 1);
    }

    @Test
    @DisplayName("ImbalanceBarCalculator: Should merge states correctly")
    public void testImbalanceBarStateMerge() {
        // Arrange
        ImbalanceBarCalculator.ImbalanceState state1 =
            new ImbalanceBarCalculator.ImbalanceState(calculatorConfig.getImbalanceBar());
        state1.setVolumeImbalance(1000L);
        state1.setDollarImbalance(50000.0);
        state1.setVibTriggered(true);

        ImbalanceBarCalculator.ImbalanceState state2 =
            new ImbalanceBarCalculator.ImbalanceState(calculatorConfig.getImbalanceBar());
        state2.setVolumeImbalance(500L);
        state2.setDollarImbalance(25000.0);
        state2.setDibTriggered(true);

        // Act
        state1.merge(state2);

        // Assert
        assertEquals(1500L, state1.getVolumeImbalance());
        assertEquals(75000.0, state1.getDollarImbalance());
        assertTrue(state1.isVibTriggered());
        assertTrue(state1.isDibTriggered());
    }

    @Test
    @DisplayName("TradeClassifier: Should detect significant price changes")
    public void testPriceChangeSignificance() {
        // Arrange
        TradeClassifier classifier = new TradeClassifier(instrumentConfig);

        // Act & Assert - Significant change
        assertTrue(classifier.isPriceChangeSignificant(1000.0, 1000.10, "C", 0.05),
            "0.10 change should be significant for tick size 0.05");

        // Act & Assert - Insignificant change
        assertFalse(classifier.isPriceChangeSignificant(1000.0, 1000.01, "C", 0.05),
            "0.01 change should NOT be significant for tick size 0.05");
    }
}
