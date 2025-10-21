package com.kotsin.consumer.processor.service;

import com.kotsin.consumer.metrics.StreamMetrics;
import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.processor.InstrumentState;
import com.kotsin.consumer.processor.Timeframe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.springframework.stereotype.Service;

/**
 * Service for emitting per-instrument candles to timeframe-specific topics
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CandleEmissionService {

    private final StreamMetrics metrics;

    /**
     * Emit per-instrument candles to timeframe-specific topics
     */
    public void emitPerInstrumentCandles(KStream<String, InstrumentState> stateStream, CandleTopicResolver topicResolver) {
        KStream<String, InstrumentState> completeStates = stateStream
            .filter((key, state) -> state.hasAnyCompleteWindow());

        for (Timeframe timeframe : new Timeframe[]{Timeframe.ONE_MIN, Timeframe.TWO_MIN, Timeframe.THREE_MIN,
                                                     Timeframe.FIVE_MIN, Timeframe.FIFTEEN_MIN, Timeframe.THIRTY_MIN}) {
            final String tfLabel = timeframe.getLabel();
            KStream<String, InstrumentCandle> built = completeStates
                .mapValues((readOnlyKey, state) -> state.extractFinalizedCandle(timeframe))
                .peek((k, c) -> log.debug("built candle tf={} scrip={} vol={} valid={}", tfLabel,
                    c != null ? c.getScripCode() : null,
                    c != null ? c.getVolume() : null,
                    c != null && c.isValid()));

            KStream<String, InstrumentCandle>[] branches = built.branch(
                (k, c) -> c != null && c.isValid(),
                (k, c) -> true
            );
            KStream<String, InstrumentCandle> valid = branches[0];
            KStream<String, InstrumentCandle> invalid = branches[1];

            invalid.peek((k, c) -> {
                log.warn("drop candle tf={} scrip={} reason={} vol={} open={} high={} low={} close={}", tfLabel,
                    c != null ? c.getScripCode() : null,
                    c == null ? "null" : (c.getVolume() == null || c.getVolume() <= 0 ? "volume" : "fields"),
                    c != null ? c.getVolume() : null,
                    c != null ? c.getOpen() : null,
                    c != null ? c.getHigh() : null,
                    c != null ? c.getLow() : null,
                    c != null ? c.getClose() : null);
                metrics.incCandleDrop(tfLabel);
            });

            String topic = topicResolver.getCandleTopicForTimeframe(tfLabel);
            if (topic != null) {
                valid
                    .peek((k, c) -> { 
                        log.info("📤 candle emit tf={} scrip={} vol={} → {}", tfLabel, c.getScripCode(), c.getVolume(), topic); 
                        metrics.incCandleEmit(tfLabel); 
                    })
                    .to(topic, Produced.with(
                        Serdes.String(),
                        InstrumentCandle.serde()
                    ));
            }
        }
    }

    /**
     * Interface for resolving candle topic names
     */
    public interface CandleTopicResolver {
        String getCandleTopicForTimeframe(String timeframe);
    }
}

