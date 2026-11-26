package com.queueguard.queue.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.NotFoundException;
import com.queueguard.queue.dto.QueueDtos.ClaimRequest;
import com.queueguard.queue.dto.QueueDtos.ClaimResponse;
import com.queueguard.queue.service.QueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QueueController.class)
@Import(QueueApiExceptionHandler.class)
class QueueControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QueueService queueService;

    @Test
    void missingQueueUsesNotFoundStatusAndStandardErrorEnvelope() throws Exception {
        when(queueService.claim(eq("missing"), any(ClaimRequest.class)))
                .thenThrow(new NotFoundException("Queue not found: missing"));

        mockMvc.perform(post("/queues/missing/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"max\":1,\"workerId\":\"test-worker\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Queue not found: missing"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void successfulEmptyClaimUsesSuccessEnvelope() throws Exception {
        when(queueService.claim(eq("default"), any(ClaimRequest.class)))
                .thenReturn(new ClaimResponse("default", "test-worker", 0, java.util.List.of()));

        mockMvc.perform(post("/queues/default/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"max\":1,\"workerId\":\"test-worker\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.claimed").value(0))
                .andExpect(jsonPath("$.data.messages").isEmpty());
    }

    @Test
    void invalidClaimParametersReturnBadRequestEnvelope() throws Exception {
        mockMvc.perform(post("/queues/default/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"max\":0,\"workerId\":\"test-worker\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Request validation failed"));

        verifyNoInteractions(queueService);
    }

    @Test
    void businessConflictRetainsItsStatusAndEnvelope() throws Exception {
        when(queueService.createQueue(any()))
                .thenThrow(new BusinessException(409, "QUEUE_EXISTS", "Queue already exists: default"));

        mockMvc.perform(post("/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"default\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Queue already exists: default"));
    }
}
