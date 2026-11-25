package com.queueguard.common.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;
import io.lettuce.core.tracing.MicrometerTracing;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ClientResourcesBuilderCustomizer.class, MicrometerTracing.class})
    static class LettuceTracingConfiguration {
        @Bean
        ClientResourcesBuilderCustomizer queueGuardLettuceTracing(
                ObjectProvider<ObservationRegistry> observationRegistryProvider) {
            return builder -> builder.tracing(
                    new MicrometerTracing(
                            observationRegistryProvider.getObject(), "queueguard-redis"));
        }
    }
}
