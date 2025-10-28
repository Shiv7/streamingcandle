package com.kotsin.consumer.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for Kyle's Lambda (price impact coefficient) calculation
 * in OrderbookAggregate.
 *
 * Kyle's Lambda measures price impact: λ = ΔP / Q
 * Where:
 * - ΔP = change in mid-price
 * - Q = signed order flow (positive for buy, negative for sell)
 *
 * Calculation: λ = Cov(ΔP, Q) / Var(Q)
 */
public class OrderbookAggregateKylesLambdaTest {

    private OrderbookAggregate aggregate;
    private static final double DELTA = 0.001; // Precision for double comparisons

    @BeforeEach
    public void setUp() {
        aggregate = new OrderbookAggregate();
        aggregate.setScripCode("12345");
        aggregate.setCompanyName("TEST");
        aggregate.setExchange("N");
    }

    /**
     * Test Kyle's Lambda initialization
     */
    @Test
    public void testKylesLambdaInitialization() {
        // Initially, Kyle's Lambda should be 0.0 (no data)
        assertEquals(0.0, aggregate.getKyleLambda(), DELTA,
            "Initial Kyle's Lambda should be 0.0");
        assertEquals(0, aggregate.getPriceImpactObservationCount(),
            "Initial observation count should be 0");
    }

    /**
     * Test Kyle's Lambda with positive price impact (buy orders push price up)
     */
    @Test
    public void testKylesLambdaPositivePriceImpact() {
        // Scenario: Buy orders (positive flow) consistently push price up
        // Mid-price: 100.0 → 100.1 → 100.2 → 100.3
        // Signed flow: +1000, +1000, +1000

        OrderBookSnapshot ob1 = createOrderbook(100.0, 100.1);
        aggregate.updateWithSnapshot(ob1);

        OrderBookSnapshot ob2 = createOrderbook(100.1, 100.2, 1000); // buy flow
        aggregate.updateWithSnapshot(ob2);

        OrderBookSnapshot ob3 = createOrderbook(100.2, 100.3, 1000); // buy flow
        aggregate.updateWithSnapshot(ob3);

        OrderBookSnapshot ob4 = createOrderbook(100.3, 100.4, 1000); // buy flow
        aggregate.updateWithSnapshot(ob4);

        // Kyle's Lambda should be positive (buy flow increases price)
        assertTrue(aggregate.getKyleLambda() > 0.0,
            "Kyle's Lambda should be > 0 for positive price impact");
    }

    /**
     * Test Kyle's Lambda with negative price impact (sell orders push price down)
     */
    @Test
    public void testKylesLambdaNegativePriceImpact() {
        // Scenario: Sell orders (negative flow) consistently push price down
        // Mid-price: 100.0 → 99.9 → 99.8 → 99.7
        // Signed flow: -1000, -1000, -1000

        OrderBookSnapshot ob1 = createOrderbook(100.0, 100.1);
        aggregate.updateWithSnapshot(ob1);

        OrderBookSnapshot ob2 = createOrderbook(99.9, 100.0, -1000); // sell flow
        aggregate.updateWithSnapshot(ob2);

        OrderBookSnapshot ob3 = createOrderbook(99.8, 99.9, -1000); // sell flow
        aggregate.updateWithSnapshot(ob3);

        OrderBookSnapshot ob4 = createOrderbook(99.7, 99.8, -1000); // sell flow
        aggregate.updateWithSnapshot(ob4);

        // Kyle's Lambda should still be positive (negative flow decreases price)
        // λ measures absolute impact: |ΔP| / |Q|
        assertTrue(aggregate.getKyleLambda() > 0.0,
            "Kyle's Lambda should be > 0 for negative price impact");
    }

    /**
     * Test Kyle's Lambda with no price impact (balanced market)
     */
    @Test
    public void testKylesLambdaNoImpact() {
        // Scenario: Order flow but no price change (high liquidity)
        // Mid-price: 100.0 (stable)
        // Signed flow: +1000, -1000, +1000, -1000

        OrderBookSnapshot ob1 = createOrderbook(100.0, 100.1);
        aggregate.updateWithSnapshot(ob1);

        OrderBookSnapshot ob2 = createOrderbook(100.0, 100.1, 1000); // buy, no price change
        aggregate.updateWithSnapshot(ob2);

        OrderBookSnapshot ob3 = createOrderbook(100.0, 100.1, -1000); // sell, no price change
        aggregate.updateWithSnapshot(ob3);

        OrderBookSnapshot ob4 = createOrderbook(100.0, 100.1, 1000); // buy, no price change
        aggregate.updateWithSnapshot(ob4);

        // Kyle's Lambda should be near 0 (no price impact)
        assertEquals(0.0, aggregate.getKyleLambda(), 0.01,
            "Kyle's Lambda should be near 0 for no price impact");
    }

    /**
     * Test Kyle's Lambda with large order impact
     */
    @Test
    public void testKylesLambdaLargeOrderImpact() {
        // Scenario: Large buy order causes significant price jump
        // Mid-price: 100.0 → 101.0 (1.0 increase)
        // Signed flow: +10000 (large buy)

        OrderBookSnapshot ob1 = createOrderbook(100.0, 100.1);
        aggregate.updateWithSnapshot(ob1);

        OrderBookSnapshot ob2 = createOrderbook(101.0, 101.1, 10000); // large buy
        aggregate.updateWithSnapshot(ob2);

        // Kyle's Lambda = ΔP / Q = 1.0 / 10000 = 0.0001
        assertTrue(aggregate.getKyleLambda() > 0.0,
            "Kyle's Lambda should be > 0 for large order impact");
        assertTrue(aggregate.getKyleLambda() < 0.01,
            "Kyle's Lambda should be small for large orders");
    }

    /**
     * Test Kyle's Lambda with minimum observations required
     */
    @Test
    public void testKylesLambdaMinimumObservations() {
        // Kyle's Lambda requires at least 20 observations for statistical significance
        OrderBookSnapshot ob = createOrderbook(100.0, 100.1);

        // Add only 10 observations
        for (int i = 0; i < 10; i++) {
            aggregate.updateWithSnapshot(ob);
        }

        // Should still be 0.0 (not enough observations)
        assertEquals(0.0, aggregate.getKyleLambda(), DELTA,
            "Kyle's Lambda should be 0 with < 20 observations");

        // Add 10 more observations (total 20)
        for (int i = 0; i < 10; i++) {
            aggregate.updateWithSnapshot(ob);
        }

        // Now should calculate (though may be 0 if no price movement)
        assertTrue(aggregate.getPriceImpactObservationCount() >= 20,
            "Should have at least 20 observations");
    }

    /**
     * Test Kyle's Lambda rolling window (keeps last 100 observations)
     */
    @Test
    public void testKylesLambdaRollingWindow() {
        // Add 150 observations (should keep last 100)
        for (int i = 0; i < 150; i++) {
            double midPrice = 100.0 + (i * 0.01); // Gradually increasing price
            OrderBookSnapshot ob = createOrderbook(midPrice, midPrice + 0.1, 100);
            aggregate.updateWithSnapshot(ob);
        }

        // Should cap at 100 observations
        assertTrue(aggregate.getPriceImpactObservationCount() <= 100,
            "Should cap at 100 observations");
        assertTrue(aggregate.getKyleLambda() > 0.0,
            "Kyle's Lambda should be > 0 with price trend");
    }

    /**
     * Test Kyle's Lambda calculation accuracy
     */
    @Test
    public void testKylesLambdaCalculationAccuracy() {
        // Known scenario:
        // Price changes: [0.1, 0.1, 0.1] (consistent 0.1 increase)
        // Signed flow: [1000, 1000, 1000] (consistent buy pressure)
        // Expected λ = Cov(ΔP, Q) / Var(Q)
        // Since ΔP and Q are constant, λ = 0.1 / 1000 = 0.0001

        OrderBookSnapshot ob1 = createOrderbook(100.0, 100.1);
        aggregate.updateWithSnapshot(ob1);

        for (int i = 0; i < 30; i++) {
            double midPrice = 100.0 + ((i + 1) * 0.1);
            OrderBookSnapshot ob = createOrderbook(midPrice, midPrice + 0.1, 1000);
            aggregate.updateWithSnapshot(ob);
        }

        // Kyle's Lambda should be approximately 0.0001 (0.1 / 1000)
        assertEquals(0.0001, aggregate.getKyleLambda(), 0.00005,
            "Kyle's Lambda should be ~0.0001 for consistent 0.1 price impact per 1000 volume");
    }

    // Helper methods

    /**
     * Create orderbook snapshot with specified mid-price
     */
    private OrderBookSnapshot createOrderbook(double bestBid, double bestAsk) {
        return createOrderbook(bestBid, bestAsk, 0);
    }

    /**
     * Create orderbook snapshot with specified mid-price and signed flow
     */
    private OrderBookSnapshot createOrderbook(double bestBid, double bestAsk, int signedFlow) {
        OrderBookSnapshot ob = new OrderBookSnapshot();
        ob.setToken(12345);
        ob.setCompanyName("TEST");
        ob.setExch("N");
        ob.setExchType("D");
        ob.setTimestamp(System.currentTimeMillis());

        // Create bid/ask levels
        OrderBookSnapshot.OrderBookLevel bidLevel = new OrderBookSnapshot.OrderBookLevel();
        bidLevel.setPrice(bestBid);
        bidLevel.setQuantity(1000 + Math.abs(signedFlow));
        bidLevel.setNumberOfOrders(1);

        OrderBookSnapshot.OrderBookLevel askLevel = new OrderBookSnapshot.OrderBookLevel();
        askLevel.setPrice(bestAsk);
        askLevel.setQuantity(1000);
        askLevel.setNumberOfOrders(1);

        ob.setAllBids(Arrays.asList(bidLevel));
        ob.setAllAsks(Arrays.asList(askLevel));

        // Set total quantities
        ob.setTotalBidQty((long)(2000 + Math.abs(signedFlow)));
        ob.setTotalOffQty(2000L);

        return ob;
    }
}
