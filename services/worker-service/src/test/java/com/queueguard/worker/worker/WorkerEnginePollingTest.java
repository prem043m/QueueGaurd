package com.queueguard.worker.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.queueguard.worker.client.QueueClient;
import com.queueguard.worker.client.QueueNotFoundException;
import com.queueguard.worker.repo.JobResultRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

class WorkerEnginePollingTest {

    @Test
    void stopsPollingConfiguredQueueAfterPermanentNotFound() {
        QueueClient client = mock(QueueClient.class);
        when(client.claim("missing", 4)).thenThrow(new QueueNotFoundException("missing"));
        WorkerEngine engine = new WorkerEngine(
                client,
                mock(JobResultRepository.class),
                new WorkerMetrics(new SimpleMeterRegistry()),
                mock(Tracer.class),
                "missing",
                4,
                4,
                0);

        try {
            engine.poll();
            engine.poll();
        } finally {
            engine.shutdown();
        }

        verify(client, times(1)).claim("missing", 4);
    }
}
