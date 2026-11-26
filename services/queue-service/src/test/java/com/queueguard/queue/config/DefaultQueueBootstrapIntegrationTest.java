package com.queueguard.queue.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.queueguard.queue.entity.QueueDef;
import com.queueguard.queue.repo.QueueDefRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DefaultQueueBootstrap.class)
@TestPropertySource(properties = "queueguard.bootstrap.default-queue-enabled=true")
@Testcontainers
class DefaultQueueBootstrapIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DefaultQueueBootstrap bootstrap;

    @Autowired
    private QueueDefRepository queues;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void provisionsDefaultQueueIdempotentlyOnFreshPostgres() throws Exception {
        DefaultApplicationArguments args = new DefaultApplicationArguments(new String[0]);

        bootstrap.run(args);
        bootstrap.run(args);

        assertThat(queues.findAll())
                .singleElement()
                .satisfies(queue -> {
                    assertThat(queue.getName()).isEqualTo("default");
                    assertThat(queue.getDescription()).isEqualTo("Default local infrastructure work queue");
                    assertThat(queue.getMaxConcurrency()).isEqualTo(32);
                    assertThat(queue.getMaxAttempts()).isEqualTo(5);
                });
    }
}
