package com.kotsin.consumer.service;

import com.kotsin.consumer.config.InstrumentConfig;
import com.kotsin.consumer.repository.ScripRepository;
import com.kotsin.consumer.entity.Scrip;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Instrument metadata service with externalized configuration
 * FIXED: Removed hardcoded volume and tick size defaults
 */
@Service
public class InstrumentMetadataService {

    private final ScripRepository scripRepository;
    private final InstrumentConfig instrumentConfig;
    private final Map<String, Scrip> cache = new ConcurrentHashMap<>();

    @Autowired
    public InstrumentMetadataService(ScripRepository scripRepository, InstrumentConfig instrumentConfig) {
        this.scripRepository = scripRepository;
        this.instrumentConfig = instrumentConfig;
    }

    private String key(String exch, String exchType, String scripCode) {
        return (exch == null ? "-" : exch) + ":" + (exchType == null ? "-" : exchType) + ":" + (scripCode == null ? "-" : scripCode);
    }

    public Optional<Scrip> getScrip(String exch, String exchType, String scripCode, String name) {
        String k = key(exch, exchType, scripCode);
        if (cache.containsKey(k)) return Optional.ofNullable(cache.get(k));
        Optional<Scrip> s = Optional.empty();
        if (scripCode != null && !scripCode.isEmpty()) {
            s = scripRepository.findFirstByExchAndExchTypeAndScripCode(exch, exchType, scripCode);
        }
        if (s.isEmpty() && name != null && !name.isEmpty()) {
            s = scripRepository.findFirstByExchAndExchTypeAndName(exch, exchType, name);
        }
        s.ifPresent(val -> cache.put(k, val));
        return s;
    }

    public double getTickSize(String exch, String exchType, String scripCode, String name, double defaultTick) {
        return getScrip(exch, exchType, scripCode, name)
                .map(Scrip::getTickSize)
                .flatMap(ts -> {
                    try { return Optional.of(Double.parseDouble(ts)); } catch (Exception e) { return Optional.empty(); }
                }).orElse(defaultTick);
    }

    public long getLotSize(String exch, String exchType, String scripCode, String name, long defaultLot) {
        return getScrip(exch, exchType, scripCode, name)
                .map(Scrip::getLotSize)
                .flatMap(ls -> {
                    try { return Optional.of(Long.parseLong(ls)); } catch (Exception e) { return Optional.empty(); }
                }).orElse(defaultLot);
    }

    public Optional<Double> getSpoofSizeRatio(String exch, String exchType, String scripCode, String name) {
        return getScrip(exch, exchType, scripCode, name)
                .map(Scrip::getSpoofSizeRatio)
                .flatMap(val -> {
                    try { return Optional.of(Double.parseDouble(val)); } catch (Exception e) { return Optional.empty(); }
                });
    }

    public Optional<Double> getSpoofEpsilonTicks(String exch, String exchType, String scripCode, String name) {
        return getScrip(exch, exchType, scripCode, name)
                .map(Scrip::getSpoofPriceEpsilonTicks)
                .flatMap(val -> {
                    try { return Optional.of(Double.parseDouble(val)); } catch (Exception e) { return Optional.empty(); }
                });
    }

    /**
     * Get average daily volume for an instrument.
     * Used for adaptive VPIN bucket sizing.
     * FIXED: Now uses externalized configuration instead of hardcoded values
     *
     * @return Average daily volume from configuration
     */
    public double getAverageDailyVolume(String exch, String exchType, String scripCode) {
        // Try to get from scrip metadata
        Optional<Scrip> scrip = getScrip(exch, exchType, scripCode, null);
        if (scrip.isPresent()) {
            // If scrip has avgVolume field, use it (extend Scrip entity if needed)
            // For now, use configured defaults based on instrument type
            String exchTypeUpper = exchType != null ? exchType.toUpperCase() : "C";
            if ("D".equals(exchTypeUpper) || "F".equals(exchTypeUpper) || "O".equals(exchTypeUpper)) {
                // Derivatives (futures/options)
                return instrumentConfig.getVolume().getDerivatives();
            }
        }

        // Check if it's an index based on exchange type or name
        if (scripCode != null && (scripCode.equalsIgnoreCase("NIFTY") || scripCode.equalsIgnoreCase("BANKNIFTY"))) {
            return instrumentConfig.getVolume().getIndex();
        }

        // Default for equity
        return instrumentConfig.getVolume().getEquity();
    }
}
