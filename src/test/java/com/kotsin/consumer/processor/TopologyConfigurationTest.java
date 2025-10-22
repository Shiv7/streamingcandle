package com.kotsin.consumer.processor;

import com.kotsin.consumer.model.FamilyEnrichedData;
import com.kotsin.consumer.model.InstrumentCandle;
import com.kotsin.consumer.model.MicrostructureData;
import com.kotsin.consumer.model.FamilyAggregatedMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TopologyConfigurationTest {

    @InjectMocks
    private TopologyConfiguration topologyConfiguration;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testCalculateVolumeWeightedMetrics() {
        // Given
        FamilyEnrichedData family = new FamilyEnrichedData();
        family.setFutures(new ArrayList<>());
        family.setOptions(new ArrayList<>());

        InstrumentCandle future = InstrumentCandle.builder()
            .volume(100L)
            .microstructure(MicrostructureData.builder().ofi(10.0).build())
            .build();
        family.getFutures().add(future);

        InstrumentCandle option = InstrumentCandle.builder()
            .volume(50L)
            .microstructure(MicrostructureData.builder().ofi(5.0).build())
            .build();
        family.getOptions().add(option);

        family.setAggregatedMetrics(FamilyAggregatedMetrics.builder().totalVolume(150L).build());

        // When
        // This is a private method, so we test it indirectly by calling the public method that uses it.
        // For a real-world scenario, you would likely test the public method `createFamilyTopology`
        // with a test topology driver. For this example, we will use reflection to test the private method.
        try {
            java.lang.reflect.Method method = topologyConfiguration.getClass().getDeclaredMethod("calculateVolumeWeightedMetrics", FamilyEnrichedData.class);
            method.setAccessible(true);
            method.invoke(topologyConfiguration, family);
        } catch (Exception e) {
            e.printStackTrace();
        }


        // Then
        MicrostructureData microstructure = family.getMicrostructure();
        double expectedOfi = (10.0 * 100.0 / 150.0) + (5.0 * 50.0 / 150.0);
        assertEquals(expectedOfi, microstructure.getOfi(), 0.001);
    }
}
