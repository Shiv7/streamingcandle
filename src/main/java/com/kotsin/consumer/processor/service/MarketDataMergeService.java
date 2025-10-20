package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.model.OpenInterest;
import com.kotsin.consumer.model.OrderBookSnapshot;
import com.kotsin.consumer.model.TickData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for merging tick data with OI and Orderbook data
 * Single Responsibility: Data merging logic
 *
 * Spring Best Practice: @Service annotation for business logic
 */
@Service
@Slf4j
public class MarketDataMergeService {

    /**
     * Merge Open Interest data into tick
     */
    public TickData mergeOiIntoTick(TickData tick, OpenInterest oi) {
        if (oi != null) {
            tick.setOpenInterest(oi.getOpenInterest());
            tick.setOiChange(oi.getOiChange());
        }
        return tick;
    }

    /**
     * Merge Orderbook data into tick
     */
    public TickData mergeOrderbookIntoTick(TickData tick, OrderBookSnapshot orderbook) {
        if (orderbook != null && orderbook.isValid()) {
            // Parse orderbook details if not already parsed
            orderbook.parseDetails();

            // Update bid/ask with orderbook's best levels (more accurate than tick's cached values)
            double bestBid = orderbook.getBestBid();
            double bestAsk = orderbook.getBestAsk();

            if (bestBid > 0) {
                tick.setBidRate(bestBid);
            }
            if (bestAsk > 0) {
                tick.setOfferRate(bestAsk);
            }

            // Update total quantities from orderbook (aggregated depth)
            if (orderbook.getTotalBidQty() != null) {
                tick.setTotalBidQuantity(orderbook.getTotalBidQty().intValue());
            }
            if (orderbook.getTotalOffQty() != null) {
                tick.setTotalOfferQuantity(orderbook.getTotalOffQty().intValue());
            }

            // Update best bid/ask quantities (top of book)
            if (orderbook.getAllBids() != null && !orderbook.getAllBids().isEmpty()) {
                tick.setBidQuantity(orderbook.getAllBids().get(0).getQuantity());
            }
            if (orderbook.getAllAsks() != null && !orderbook.getAllAsks().isEmpty()) {
                tick.setOfferQuantity(orderbook.getAllAsks().get(0).getQuantity());
            }

            // Store full orderbook for depth analytics (transient field)
            tick.setFullOrderbook(orderbook);

            log.debug("📖 Merged orderbook into tick {}: bid={}, ask={}, spread={}, totalBid={}, totalAsk={}",
                tick.getScripCode(), bestBid, bestAsk, orderbook.getSpread(),
                orderbook.getTotalBidQty(), orderbook.getTotalOffQty());
        }
        return tick;
    }
}
