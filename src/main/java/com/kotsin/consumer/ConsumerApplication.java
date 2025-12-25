package com.kotsin.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;


/**
 * Spring Boot Application to initialize 3 INDEPENDENT Kafka Streams processors:
 * 1. CandlestickProcessor - Ticks → Enriched Candles (OHLC + ImbalanceBars + VolumeProfile)
 * 2. OrderbookProcessor - Orderbook → Microstructure Signals (OFI + VPIN + Kyle's Lambda + Iceberg + Spoofing)
 * 3. OIProcessor - OI → OI Metrics (OI OHLC + Put/Call tracking)
 * 
 * Each processor:
 * - Has its own application-id
 * - Manages its own Kafka Streams lifecycle
 * - Produces to independent topics (18 total: 6 timeframes × 3 streams)
 * - No cross-stream dependencies
 * 
 * Pattern: Clean, simple, follows streamingcandle-working architecture
 */
@SpringBootApplication
@EnableScheduling
public class ConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
    }
}
