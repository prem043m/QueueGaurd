package com.queueguard.common.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

class QueueGuardObservationAutoConfigurationTest {
    @Test
    void removesDatasourceQueryTextFromHighCardinalityTags() {
        Observation.Context context = new Observation.Context();
        context.setName("jdbc.query");
        context.addHighCardinalityKeyValue(KeyValue.of("jdbc.query[0]", "test query text"));
        context.addHighCardinalityKeyValue(KeyValue.of("db.statement", "test statement"));
        context.addHighCardinalityKeyValue(KeyValue.of("peer.service", "postgresql"));

        Observation.Context filtered =
                new QueueGuardObservationAutoConfiguration()
                        .jdbcStatementRedactionFilter()
                        .map(context);

        assertNull(filtered.getHighCardinalityKeyValue("jdbc.query[0]"));
        assertNull(filtered.getHighCardinalityKeyValue("db.statement"));
        assertEquals(
                "postgresql",
                filtered.getHighCardinalityKeyValue("peer.service").getValue());
    }
}
