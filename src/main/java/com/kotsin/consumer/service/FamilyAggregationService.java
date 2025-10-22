package com.kotsin.consumer.service;

import com.kotsin.consumer.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service for family-level aggregation logic
 * Handles assembly and computation of family-level metrics
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FamilyAggregationService {

    private final InstrumentKeyResolver keyResolver;

    /**
     * Assemble family from individual instrument candles
     */
    public FamilyEnrichedData assembleFamily(String familyKey, InstrumentCandle candle, FamilyEnrichedData family) {
        if (candle == null) {
            return family; // No candle to add, return existing family
        }
        if (family == null) {
            family = FamilyEnrichedData.builder().build();
        }
        // Ensure identity fields are populated
        if (family.getFamilyKey() == null) {
            family.setFamilyKey(familyKey);
        }
        if (family.getInstrumentType() == null) {
            String famType = (familyKey != null && keyResolver.isIndex(familyKey)) ? "INDEX_FAMILY" : "EQUITY_FAMILY";
            family.setInstrumentType(famType);
        }
        if (family.getFamilyName() == null) {
            String name = null;
            if (family.getEquity() != null && family.getEquity().getCompanyName() != null) {
                name = family.getEquity().getCompanyName();
            } else {
                name = candle.getCompanyName();
            }
            family.setFamilyName(name);
        }

        if (family.getWindowStartMillis() == null) {
            family.setWindowStartMillis(candle.getWindowStartMillis());
        }
        family.setWindowEndMillis(candle.getWindowEndMillis());

        String type = candle.getInstrumentType() != null ? candle.getInstrumentType().toUpperCase() : "";
        if ("EQUITY".equals(type) || ("INDEX".equals(type) && family.getEquity() == null)) {
            family.setEquity(candle);
            if (family.getFamilyName() == null) {
                family.setFamilyName(candle.getCompanyName());
            }
        } else if ("FUTURE".equals(type)) {
            // CRITICAL: Deduplicate futures by scripCode to prevent double-counting
            addOrUpdateFuture(family, candle);
        } else if ("OPTION".equals(type)) {
            // CRITICAL: Deduplicate options by unique key (strike + type)
            addOrUpdateOption(family, candle);
        }

        // Recompute aggregated family-level analytics
        family.setAggregatedMetrics(computeAggregatedMetrics(family));
        family.setMicrostructure(computeFamilyMicrostructure(family));
        family.setOrderbookDepth(computeFamilyOrderbookDepth(family));
        family.setImbalanceBars(computeFamilyImbalanceBars(family));
        // Adjust identity and spot fallback for derivative-only families
        if (family.getEquity() == null) {
            if (family.getFutures() != null && !family.getFutures().isEmpty()) {
                family.setInstrumentType("DERIVATIVE_FAMILY");
                // Fallback spot price: use near-month future close
                if (family.getAggregatedMetrics() != null && family.getAggregatedMetrics().getSpotPrice() == null) {
                    family.getAggregatedMetrics().setSpotPrice(
                        family.getAggregatedMetrics().getNearMonthFuturePrice()
                    );
                }
            }
        }
        family.setTotalInstrumentsCount(family.calculateTotalCount());
        return family;
    }

    /**
     * Compute aggregated metrics for family
     */
    public FamilyAggregatedMetrics computeAggregatedMetrics(FamilyEnrichedData family) {
        long totalVol = 0;
        long eqVol = 0, futVol = 0, optVol = 0;
        Long totalOi = 0L, futOi = 0L, callsOi = 0L, putsOi = 0L;
        Long futOiChg = 0L, callsOiChg = 0L, putsOiChg = 0L;
        Double spot = null, fut = null;
        Integer activeOptions = 0;
        Integer activeFutures = 0;
        String nearExpiry = null;

        if (family.getEquity() != null) {
            InstrumentCandle e = family.getEquity();
            if (e.getVolume() != null) { eqVol = e.getVolume(); totalVol += eqVol; }
            spot = e.getClose();
            if (e.getOpenInterest() != null) { totalOi += e.getOpenInterest(); }
        }
        if (family.getFutures() != null && !family.getFutures().isEmpty()) {
            InstrumentCandle f = family.getFutures().get(0);
            activeFutures = 1;
            if (f.getVolume() != null) { futVol = f.getVolume(); totalVol += futVol; }
            fut = f.getClose();
            if (f.getOpenInterest() != null) { totalOi += f.getOpenInterest(); futOi += f.getOpenInterest(); }
            if (f.getOiChange() != null) { futOiChg += f.getOiChange(); }
            nearExpiry = f.getExpiry();
        }
        long callsVol = 0, putsVol = 0;
        if (family.getOptions() != null) {
            for (InstrumentCandle o : family.getOptions()) {
                if (o.getVolume() != null) {
                    optVol += o.getVolume();
                    totalVol += o.getVolume();
                    if ("CE".equalsIgnoreCase(o.getOptionType())) callsVol += o.getVolume();
                    if ("PE".equalsIgnoreCase(o.getOptionType())) putsVol += o.getVolume();
                }
                if (o.getOpenInterest() != null) {
                    totalOi += o.getOpenInterest();
                    if ("CE".equalsIgnoreCase(o.getOptionType())) callsOi += o.getOpenInterest();
                    if ("PE".equalsIgnoreCase(o.getOptionType())) putsOi += o.getOpenInterest();
                }
                if (o.getOiChange() != null) {
                    if ("CE".equalsIgnoreCase(o.getOptionType())) callsOiChg += o.getOiChange();
                    if ("PE".equalsIgnoreCase(o.getOptionType())) putsOiChg += o.getOiChange();
                }
                if (o.getVolume() != null && o.getVolume() > 0) activeOptions++;
            }
        }
        Double basis = (spot != null && fut != null) ? (fut - spot) : null;
        Double basisPct = (spot != null && fut != null && spot != 0) ? ((fut - spot) / spot * 100.0) : null;
        Double pcr = (callsOi != null && callsOi > 0) ? (putsOi.doubleValue() / callsOi.doubleValue()) : null;
        Double pcrVol = (callsVol > 0) ? (putsVol * 1.0 / callsVol) : null;

        // Orderbook aggregates across instruments (simple sums/averages)
        Double avgSpread = null;
        Long sumBid = 0L, sumAsk = 0L; int depthCount = 0;
        for (InstrumentCandle c : collectAllInstruments(family)) {
            if (c.getOrderbookDepth() != null) {
                if (c.getOrderbookDepth().getSpread() != null) {
                    avgSpread = (avgSpread == null ? 0.0 : avgSpread) + c.getOrderbookDepth().getSpread();
                }
                if (c.getOrderbookDepth().getTotalBidDepth() != null) sumBid += c.getOrderbookDepth().getTotalBidDepth().longValue();
                if (c.getOrderbookDepth().getTotalAskDepth() != null) sumAsk += c.getOrderbookDepth().getTotalAskDepth().longValue();
                depthCount++;
            }
        }
        if (avgSpread != null && depthCount > 0) avgSpread = avgSpread / depthCount;
        Double bidAskImb = (sumBid + sumAsk) > 0 ? ((sumBid - sumAsk) * 1.0 / (sumBid + sumAsk)) : null;

        return FamilyAggregatedMetrics.builder()
            .totalVolume(totalVol)
            .equityVolume(eqVol)
            .futuresVolume(futVol)
            .optionsVolume(optVol)
            .totalOpenInterest(totalOi > 0 ? totalOi : null)
            .futuresOI(futOi > 0 ? futOi : null)
            .callsOI(callsOi > 0 ? callsOi : null)
            .putsOI(putsOi > 0 ? putsOi : null)
            .futuresOIChange(futOiChg != 0 ? futOiChg : null)
            .callsOIChange(callsOiChg != 0 ? callsOiChg : null)
            .putsOIChange(putsOiChg != 0 ? putsOiChg : null)
            .putCallRatio(pcr)
            .putCallVolumeRatio(pcrVol)
            .activeOptionsCount(activeOptions)
            .spotPrice(spot)
            .nearMonthFuturePrice(fut)
            .futuresBasis(basis)
            .futuresBasisPercent(basisPct)
            .activeFuturesCount(activeFutures)
            .nearMonthExpiry(nearExpiry)
            .avgBidAskSpread(avgSpread)
            .totalBidVolume(sumBid > 0 ? sumBid : null)
            .totalAskVolume(sumAsk > 0 ? sumAsk : null)
            .bidAskImbalance(bidAskImb)
            .calculatedAt(System.currentTimeMillis())
            .build();
    }

    /**
     * Compute family-level microstructure data
     */
    public MicrostructureData computeFamilyMicrostructure(FamilyEnrichedData family) {
        double ofiSum = 0.0, vpinSum = 0.0, depthImbSum = 0.0, kyleSum = 0.0; int n = 0;
        for (InstrumentCandle c : collectAllInstruments(family)) {
            if (c.getMicrostructure() != null) {
                MicrostructureData m = c.getMicrostructure();
                if (m.getOfi() != null) ofiSum += m.getOfi();
                if (m.getVpin() != null) vpinSum += m.getVpin();
                if (m.getDepthImbalance() != null) depthImbSum += m.getDepthImbalance();
                if (m.getKyleLambda() != null) kyleSum += m.getKyleLambda();
                n++;
            }
        }
        if (n == 0) return null;
        return MicrostructureData.builder()
            .ofi(ofiSum / n)
            .vpin(vpinSum / n)
            .depthImbalance(depthImbSum / n)
            .kyleLambda(kyleSum / n)
            .isComplete(true)
            .build();
    }

    /**
     * Compute family-level orderbook depth data
     */
    public OrderbookDepthData computeFamilyOrderbookDepth(FamilyEnrichedData family) {
        double spreadSum = 0.0; int spreadCount = 0;
        double totalBid = 0.0, totalAsk = 0.0;
        double weightedImbSum = 0.0; int imbCount = 0;
        double lvl1ImbSum = 0.0, l2to5ImbSum = 0.0, l6to10ImbSum = 0.0; 
        int lvl1Count = 0, lvl2to5Count = 0, lvl6to10Count = 0;
        double bidVwapSum = 0.0, askVwapSum = 0.0; int vwapCount = 0;
        double bidSlopeSum = 0.0, askSlopeSum = 0.0, slopeRatioSum = 0.0; int slopeCount = 0;
        long tsMax = 0L; Integer depthLevelsMin = null;
        
        // Spoofing/Iceberg aggregation
        boolean anyIcebergBid = false, anyIcebergAsk = false;
        double maxIcebergProbBid = 0.0, maxIcebergProbAsk = 0.0;
        int totalSpoofCount = 0;
        boolean anyActiveSpoofBid = false, anyActiveSpoofAsk = false;
        List<OrderbookDepthData.SpoofingEvent> allSpoofEvents = new ArrayList<>();

        for (InstrumentCandle c : collectAllInstruments(family)) {
            OrderbookDepthData d = c.getOrderbookDepth();
            if (d == null) continue;

            if (d.getSpread() != null) { spreadSum += d.getSpread(); spreadCount++; }
            if (d.getTotalBidDepth() != null) totalBid += d.getTotalBidDepth();
            if (d.getTotalAskDepth() != null) totalAsk += d.getTotalAskDepth();

            if (d.getWeightedDepthImbalance() != null) { weightedImbSum += d.getWeightedDepthImbalance(); imbCount++; }
            
            if (d.getLevel1Imbalance() != null) { lvl1ImbSum += d.getLevel1Imbalance(); lvl1Count++; }
            if (d.getLevel2to5Imbalance() != null) { l2to5ImbSum += d.getLevel2to5Imbalance(); lvl2to5Count++; }
            if (d.getLevel6to10Imbalance() != null) { l6to10ImbSum += d.getLevel6to10Imbalance(); lvl6to10Count++; }

            if (d.getBidVWAP() != null && d.getAskVWAP() != null) {
                bidVwapSum += d.getBidVWAP();
                askVwapSum += d.getAskVWAP();
                vwapCount++;
            }

            if (d.getBidSlope() != null && d.getAskSlope() != null) {
                bidSlopeSum += d.getBidSlope();
                askSlopeSum += d.getAskSlope();
                if (d.getSlopeRatio() != null) slopeRatioSum += d.getSlopeRatio();
                slopeCount++;
            }

            if (d.getTimestamp() != null && d.getTimestamp() > tsMax) tsMax = d.getTimestamp();
            if (d.getDepthLevels() != null) depthLevelsMin = depthLevelsMin == null ? d.getDepthLevels() : Math.min(depthLevelsMin, d.getDepthLevels());
            
            if (d.getIcebergDetectedBid() != null && d.getIcebergDetectedBid()) anyIcebergBid = true;
            if (d.getIcebergDetectedAsk() != null && d.getIcebergDetectedAsk()) anyIcebergAsk = true;
            if (d.getIcebergProbabilityBid() != null) maxIcebergProbBid = Math.max(maxIcebergProbBid, d.getIcebergProbabilityBid());
            if (d.getIcebergProbabilityAsk() != null) maxIcebergProbAsk = Math.max(maxIcebergProbAsk, d.getIcebergProbabilityAsk());
            if (d.getSpoofingCountLast1Min() != null) totalSpoofCount += d.getSpoofingCountLast1Min();
            if (d.getActiveSpoofingBid() != null && d.getActiveSpoofingBid()) anyActiveSpoofBid = true;
            if (d.getActiveSpoofingAsk() != null && d.getActiveSpoofingAsk()) anyActiveSpoofAsk = true;
            if (d.getSpoofingEvents() != null && !d.getSpoofingEvents().isEmpty()) {
                allSpoofEvents.addAll(d.getSpoofingEvents());
            }
        }

        if (spreadCount == 0 && totalBid == 0.0 && totalAsk == 0.0 && imbCount == 0 && vwapCount == 0 && slopeCount == 0) return null;
        Double familySpread = spreadCount > 0 ? (spreadSum / spreadCount) : null;
        Double weightedImb = imbCount > 0 ? (weightedImbSum / imbCount) : null;
        Double lvl1Imb = lvl1Count > 0 ? (lvl1ImbSum / lvl1Count) : null;
        Double l2to5Imb = lvl2to5Count > 0 ? (l2to5ImbSum / lvl2to5Count) : null;
        Double l6to10Imb = lvl6to10Count > 0 ? (l6to10ImbSum / lvl6to10Count) : null;
        Double bidVwap = vwapCount > 0 ? (bidVwapSum / vwapCount) : null;
        Double askVwap = vwapCount > 0 ? (askVwapSum / vwapCount) : null;
        Double bidSlope = slopeCount > 0 ? (bidSlopeSum / slopeCount) : null;
        Double askSlope = slopeCount > 0 ? (askSlopeSum / slopeCount) : null;
        Double slopeRatio = slopeCount > 0 ? (slopeRatioSum / slopeCount) : null;

        return OrderbookDepthData.builder()
            .spread(familySpread)
            .totalBidDepth(totalBid > 0 ? totalBid : null)
            .totalAskDepth(totalAsk > 0 ? totalAsk : null)
            .weightedDepthImbalance(weightedImb)
            .level1Imbalance(lvl1Imb)
            .level2to5Imbalance(l2to5Imb)
            .level6to10Imbalance(l6to10Imb)
            .bidVWAP(bidVwap)
            .askVWAP(askVwap)
            .bidSlope(bidSlope)
            .askSlope(askSlope)
            .slopeRatio(slopeRatio)
            .icebergDetectedBid(anyIcebergBid ? true : null)
            .icebergDetectedAsk(anyIcebergAsk ? true : null)
            .icebergProbabilityBid(maxIcebergProbBid > 0 ? maxIcebergProbBid : null)
            .icebergProbabilityAsk(maxIcebergProbAsk > 0 ? maxIcebergProbAsk : null)
            .spoofingCountLast1Min(totalSpoofCount > 0 ? totalSpoofCount : null)
            .activeSpoofingBid(anyActiveSpoofBid ? true : null)
            .activeSpoofingAsk(anyActiveSpoofAsk ? true : null)
            .spoofingEvents(allSpoofEvents.isEmpty() ? null : allSpoofEvents)
            .timestamp(tsMax > 0 ? tsMax : null)
            .depthLevels(depthLevelsMin)
            .isComplete(true)
            .build();
    }

    /**
     * Compute family-level imbalance bar data
     */
    public ImbalanceBarData computeFamilyImbalanceBars(FamilyEnrichedData family) {
        ImbalanceBarData best = null;
        List<InstrumentCandle> ordered = new ArrayList<>();
        if (family.getFutures() != null) ordered.addAll(family.getFutures());
        if (family.getEquity() != null) ordered.add(family.getEquity());
        if (family.getOptions() != null) ordered.addAll(family.getOptions());
        for (InstrumentCandle c : ordered) {
            if (c.getImbalanceBars() != null) {
                if (best == null) {
                    best = c.getImbalanceBars();
                } else if (!best.hasAnyCompleteBar() && c.getImbalanceBars().hasAnyCompleteBar()) {
                    best = c.getImbalanceBars();
                }
            }
        }
        return best;
    }

    /**
     * Add or update future in family (with deduplication by scripCode)
     * CRITICAL FIX: Prevents duplicate futures from different timeframes
     */
    private void addOrUpdateFuture(FamilyEnrichedData family, InstrumentCandle candle) {
        if (family.getFutures() == null) {
            family.setFutures(new ArrayList<>());
        }
        
        String candleScripCode = candle.getScripCode();
        if (candleScripCode == null) {
            return;
        }
        
        // OPTIMIZATION: Use HashMap for O(1) lookup instead of O(n) linear search
        Map<String, Integer> futureIndexMap = getOrCreateFutureIndexMap(family);
        Integer existingIdx = futureIndexMap.get(candleScripCode);
        
        if (existingIdx != null) {
            // Future already exists - update if new one has more volume (more recent/complete data)
            InstrumentCandle existing = family.getFutures().get(existingIdx);
            if (candle.getVolume() != null && 
                (existing.getVolume() == null || candle.getVolume() > existing.getVolume())) {
                family.getFutures().set(existingIdx, candle);
            }
        } else {
            // New future - add it, but keep only near-month (earliest expiry)
            if (family.getFutures().isEmpty()) {
                family.getFutures().add(candle);
                futureIndexMap.put(candleScripCode, 0);
            } else {
                // Replace if this is nearer month, otherwise skip
                InstrumentCandle nearMonth = family.getFutures().get(0);
                if (nearMonth.getExpiry() == null || 
                    (candle.getExpiry() != null && candle.getExpiry().compareTo(nearMonth.getExpiry()) < 0)) {
                    family.getFutures().set(0, candle);
                    futureIndexMap.put(candleScripCode, 0);
                }
            }
        }
    }
    
    /**
     * Get or create future index map for O(1) lookups
     * OPTIMIZATION: Avoids O(n) linear search in family aggregation
     */
    private Map<String, Integer> getOrCreateFutureIndexMap(FamilyEnrichedData family) {
        Map<String, Integer> indexMap = new java.util.HashMap<>();
        if (family.getFutures() != null) {
            for (int i = 0; i < family.getFutures().size(); i++) {
                InstrumentCandle future = family.getFutures().get(i);
                if (future.getScripCode() != null) {
                    indexMap.put(future.getScripCode(), i);
                }
            }
        }
        return indexMap;
    }
    
    /**
     * Add or update option in family (with deduplication by strike+type)
     * CRITICAL FIX: Prevents duplicate options from different timeframes
     */
    private void addOrUpdateOption(FamilyEnrichedData family, InstrumentCandle candle) {
        if (family.getOptions() == null) {
            family.setOptions(new ArrayList<>());
        }
        
        // Generate unique key for this option (strike + type)
        String optionKey = getOptionKey(candle);
        if (optionKey == null) {
            return; // Invalid option, skip
        }
        
        // OPTIMIZATION: Use HashMap for O(1) lookup instead of O(n) linear search
        Map<String, Integer> optionIndexMap = getOrCreateOptionIndexMap(family);
        Integer existingIdx = optionIndexMap.get(optionKey);
        
        if (existingIdx != null) {
            // Option already exists - update if new one has more volume (more recent/complete data)
            InstrumentCandle existing = family.getOptions().get(existingIdx);
            if (candle.getVolume() != null && 
                (existing.getVolume() == null || candle.getVolume() > existing.getVolume())) {
                family.getOptions().set(existingIdx, candle);
            }
        } else {
            // New option - add if we have space, or replace worst one
            if (family.getOptions().size() < 4) {
                family.getOptions().add(candle);
                optionIndexMap.put(optionKey, family.getOptions().size() - 1);
            } else {
                replaceOptionIfBetter(family, candle);
            }
        }
    }
    
    /**
     * Get or create option index map for O(1) lookups
     * OPTIMIZATION: Avoids O(n) linear search in family aggregation
     */
    private Map<String, Integer> getOrCreateOptionIndexMap(FamilyEnrichedData family) {
        Map<String, Integer> indexMap = new java.util.HashMap<>();
        if (family.getOptions() != null) {
            for (int i = 0; i < family.getOptions().size(); i++) {
                InstrumentCandle option = family.getOptions().get(i);
                String optionKey = getOptionKey(option);
                if (optionKey != null) {
                    indexMap.put(optionKey, i);
                }
            }
        }
        return indexMap;
    }
    
    /**
     * Generate unique key for option (strike + type)
     */
    private String getOptionKey(InstrumentCandle option) {
        if (option.getStrikePrice() == null || option.getOptionType() == null) {
            // Fallback to scripCode if strike/type not available
            return option.getScripCode();
        }
        return String.format("%.2f_%s", option.getStrikePrice(), option.getOptionType());
    }

    /**
     * Replace option with better candidate (closer to ATM)
     */
    public void replaceOptionIfBetter(FamilyEnrichedData family, InstrumentCandle candidate) {
        try {
            Double spot = family.getEquity() != null ? family.getEquity().getClose() : null;
            if (spot == null || candidate.getStrikePrice() == null) {
                return;
            }
            int worstIdx = -1;
            double worstDist = -1;
            for (int i = 0; i < family.getOptions().size(); i++) {
                InstrumentCandle opt = family.getOptions().get(i);
                if (opt.getStrikePrice() == null) continue;
                double dist = Math.abs(opt.getStrikePrice() - spot);
                if (dist > worstDist) {
                    worstDist = dist;
                    worstIdx = i;
                }
            }
            double candDist = Math.abs(candidate.getStrikePrice() - spot);
            if (worstIdx >= 0 && candDist < worstDist) {
                family.getOptions().set(worstIdx, candidate);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Collect all instruments from family
     */
    private List<InstrumentCandle> collectAllInstruments(FamilyEnrichedData family) {
        List<InstrumentCandle> list = new ArrayList<>();
        if (family.getEquity() != null) list.add(family.getEquity());
        if (family.getFutures() != null) list.addAll(family.getFutures());
        if (family.getOptions() != null) list.addAll(family.getOptions());
        return list;
    }
}

