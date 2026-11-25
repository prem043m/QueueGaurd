package com.queueguard.common.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class QueueGuardObservationAutoConfiguration {
    @Bean
    ObservationFilter jdbcStatementRedactionFilter() {
        return context -> {
            if (context.getName().startsWith("jdbc.")) {
                context.removeHighCardinalityKeyValue("db.statement");
                context.removeHighCardinalityKeyValue("db.query.text");
            }
            return context;
        };
    }

}
