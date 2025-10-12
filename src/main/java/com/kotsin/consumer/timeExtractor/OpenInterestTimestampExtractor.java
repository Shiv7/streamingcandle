package com.kotsin.consumer.timeExtractor;

import com.kotsin.consumer.model.OpenInterestData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TimestampExtractor for OpenInterest data.
 * Extracts receivedTimestamp from OpenInterestData payload.
 */
public class OpenInterestTimestampExtractor implements TimestampExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenInterestTimestampExtractor.class);

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long previousTimestamp) {
        Object value = record.value();
        if (!(value instanceof OpenInterestData)) {
            // Not an OpenInterestData record, fall back to record timestamp
            return record.timestamp();
        }

        OpenInterestData oiData = (OpenInterestData) value;
        long timestamp = oiData.getTimestamp();

        if (timestamp <= 0) {
            LOGGER.warn("OpenInterest has invalid timestamp ({}) for token {}, using Kafka record timestamp", 
                    timestamp, oiData.getToken());
            return record.timestamp();
        }

        // Validate timestamp is reasonable (not too far in future/past)
        long now = System.currentTimeMillis();
        if (timestamp > now + 60000L) { // More than 1 min in future
            LOGGER.warn("OpenInterest timestamp {} is in the future for token {}. Using record timestamp.", 
                    timestamp, oiData.getToken());
            return record.timestamp();
        }
        if (timestamp < now - 7L * 24 * 3600 * 1000) { // More than 7 days old
            LOGGER.warn("OpenInterest timestamp {} is more than 7 days old for token {}. Using record timestamp.", 
                    timestamp, oiData.getToken());
            return record.timestamp();
        }

        return timestamp;
    }
}

