package com.kotsin.consumer.transformers;

import com.kotsin.consumer.model.TickData;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.Stores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CumToDeltaTransformerTest {

    private TopologyTestDriver driver;
    private org.apache.kafka.streams.TestInputTopic<String, TickData> in;
    private org.apache.kafka.streams.TestOutputTopic<String, TickData> out;

    private static final String IN = "in";
    private static final String OUT = "out";
    private static final String STORE = "delta-volume-store-test";

    @BeforeEach
    void setup() {
        StreamsBuilder b = new StreamsBuilder();

        b.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STORE),
                Serdes.String(), Serdes.Integer()));

        JsonSerde<TickData> serde = new JsonSerde<>(TickData.class);

        KStream<String, TickData> s = b.stream(IN, Consumed.with(Serdes.String(), serde));
        KStream<String, TickData> t = s.transform(() -> new CumToDeltaTransformer(STORE), STORE);
        t.to(OUT, Produced.with(Serdes.String(), serde));

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "cum-to-delta-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        driver = new TopologyTestDriver(b.build(), props);

        in = driver.createInputTopic(IN, Serdes.String().serializer(), serde.serializer());
        out = driver.createOutputTopic(OUT, Serdes.String().deserializer(), serde.deserializer());
    }

    @AfterEach
    void tearDown() { if (driver != null) driver.close(); }

    @Test
    void addsDeltaForFirstAndSubsequentTicks() {
        TickData a = new TickData();
        a.setScripCode("EQ1");
        a.setTotalQuantity(100);
        in.pipeInput("EQ1", a);

        KeyValue<String, TickData> r1 = out.readKeyValue();
        assertEquals("EQ1", r1.key);
        assertEquals(100, r1.value.getDeltaVolume());

        TickData b2 = new TickData();
        b2.setScripCode("EQ1");
        b2.setTotalQuantity(130);
        in.pipeInput("EQ1", b2);

        KeyValue<String, TickData> r2 = out.readKeyValue();
        assertEquals(30, r2.value.getDeltaVolume());

        TickData b3 = new TickData();
        b3.setScripCode("EQ1");
        b3.setTotalQuantity(120);
        in.pipeInput("EQ1", b3);

        KeyValue<String, TickData> r3 = out.readKeyValue();
        assertEquals(0, r3.value.getDeltaVolume());
    }
}


