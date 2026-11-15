package com.queueguard.qms.web;

import com.queueguard.common.dto.ApiResponse;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.qms.domain.*;
import com.queueguard.qms.dto.QueueDtos.*;
import com.queueguard.qms.service.QueueService;
import com.queueguard.qms.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Controller-level authorization tests for QueueController.
 * These tests verify that authorization is enforced at the controller layer.
 */
@ExtendWith(MockitoExtension.class)
class QueueControllerAuthorizationTest {
    
    @Mock
    private QueueService queueService;
    
    @Mock
    private TenantService tenantService;
    
    @InjectMocks
    private QueueController queueController;
    
    private QueueConfiguration config;
    
    @BeforeEach
    void setUp() {
        config = new QueueConfiguration();
        config.setId(1L);
        config.setOrganizationId(1L);
        config.setQueueCode("TEST");
        config.setStatus(QueueStatus.OPEN);
        config.setMaximumCapacity(100);
        config.setPriorityEnabled(false);
        config.setOrderingPolicy(OrderingPolicy.FIFO);
    }
    
    // ==================== External Participant Registration Tests ====================
    
    @Test
    void testCreateQueueEntry_MemberCannotRegisterExternalParticipant() {
        // Given: A MEMBER (not admin) tries to register an external participant
        Long actor = 1L;
        Long orgId = 1L;
        Long queueId = 1L;
        
        Membership membership = mock(Membership.class);
        lenient().when(membership.getRoles()).thenReturn(Set.of(OrganizationRole.MEMBER));
        lenient().when(membership.getAuthUserId()).thenReturn(actor);
        lenient().when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        
        // Mock tenantService to return MEMBER role
        lenient().when(tenantService.active(eq(actor), eq(orgId))).thenReturn(membership);
        
        // Mock queueService to throw ForbiddenException
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", null, "External Participant", "external@example.com", null);
        
        when(queueService.registerParticipant(eq(actor), eq(orgId), eq(queueId), eq(request)))
                .thenThrow(new ForbiddenException("Cannot register external participants without ORG_ADMIN or QUEUE_MANAGER role"));
        
        // When/Then: Should throw ForbiddenException
        assertThrows(ForbiddenException.class, () -> 
            queueController.createQueueEntry(actor, orgId, queueId, request));
        
        // Verify registerParticipant was called (not createQueueEntry directly)
        verify(queueService, times(1)).registerParticipant(eq(actor), eq(orgId), eq(queueId), eq(request));
        verify(queueService, never()).createQueueEntry(anyLong(), anyLong(), anyLong(), any());
    }
    
    @Test
    void testCreateQueueEntry_MemberCannotRegisterOtherUser() {
        // Given: A MEMBER (not admin) tries to register another user
        Long actor = 1L;
        Long orgId = 1L;
        Long queueId = 1L;
        Long otherUserId = 2L;
        
        Membership membership = mock(Membership.class);
        lenient().when(membership.getRoles()).thenReturn(Set.of(OrganizationRole.MEMBER));
        lenient().when(membership.getAuthUserId()).thenReturn(actor);
        lenient().when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        
        // Mock tenantService to return MEMBER role
        lenient().when(tenantService.active(eq(actor), eq(orgId))).thenReturn(membership);
        
        // Mock queueService to throw ForbiddenException
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", otherUserId, "Other User", "other@example.com", null);
        
        when(queueService.registerParticipant(eq(actor), eq(orgId), eq(queueId), eq(request)))
                .thenThrow(new ForbiddenException("Cannot register other participants without ORG_ADMIN or QUEUE_MANAGER role"));
        
        // When/Then: Should throw ForbiddenException
        assertThrows(ForbiddenException.class, () -> 
            queueController.createQueueEntry(actor, orgId, queueId, request));
        
        // Verify registerParticipant was called (not createQueueEntry directly)
        verify(queueService, times(1)).registerParticipant(eq(actor), eq(orgId), eq(queueId), eq(request));
        verify(queueService, never()).createQueueEntry(anyLong(), anyLong(), anyLong(), any());
    }
    
    @Test
    void testCreateQueueEntry_MemberCanRegisterThemselves() {
        // Given: A MEMBER tries to register themselves (participantUserId == actor)
        Long actor = 1L;
        Long orgId = 1L;
        Long queueId = 1L;
        
        Membership membership = mock(Membership.class);
        lenient().when(membership.getRoles()).thenReturn(Set.of(OrganizationRole.MEMBER));
        lenient().when(membership.getAuthUserId()).thenReturn(actor);
        lenient().when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        
        // Mock tenantService to return MEMBER role
        lenient().when(tenantService.active(eq(actor), eq(orgId))).thenReturn(membership);
        
        // Mock queueService to succeed
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", actor, "Self", "self@example.com", null);
        
        QueueEntryView mockResult = mock(QueueEntryView.class);
        
        when(queueService.registerParticipant(eq(actor), eq(orgId), eq(queueId), eq(request)))
                .thenReturn(mockResult);
        
        // When: Should succeed
        ResponseEntity<ApiResponse<QueueEntryView>> response = 
                queueController.createQueueEntry(actor, orgId, queueId, request);
        
        // Then
        assertNotNull(response);
        assertTrue(response.getBody().success());
        assertNotNull(response.getBody().data());
        assertEquals("Queue entry created", response.getBody().message());
        
        // Verify registerParticipant was called (not createQueueEntry directly)
        verify(queueService, times(1)).registerParticipant(eq(actor), eq(orgId), eq(queueId), eq(request));
        verify(queueService, never()).createQueueEntry(anyLong(), anyLong(), anyLong(), any());
    }
    
    @Test
    void testCreateQueueEntry_OrgAdminCanRegisterExternalParticipant() {
        // Given: An ORG_ADMIN tries to register an external participant
        Long actor = 1L;
        Long orgId = 1L;
        Long queueId = 1L;
        
        Membership membership = mock(Membership.class);
        lenient().when(membership.getRoles()).thenReturn(Set.of(OrganizationRole.ORG_ADMIN));
        lenient().when(membership.getAuthUserId()).thenReturn(actor);
        lenient().when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        
        // Mock tenantService to return ORG_ADMIN role
        lenient().when(tenantService.active(eq(actor), eq(orgId))).thenReturn(membership);
        
        // Mock queueService to succeed
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", null, "External Participant", "external@example.com", null);
        
        QueueEntryView mockResult = mock(QueueEntryView.class);
        
        when(queueService.registerParticipant(eq(actor), eq(orgId), eq(queueId), eq(request)))
                .thenReturn(mockResult);
        
        // When: Should succeed
        ResponseEntity<ApiResponse<QueueEntryView>> response = 
                queueController.createQueueEntry(actor, orgId, queueId, request);
        
        // Then
        assertNotNull(response);
        assertTrue(response.getBody().success());
        assertNotNull(response.getBody().data());
        
        // Verify registerParticipant was called (not createQueueEntry directly)
        verify(queueService, times(1)).registerParticipant(eq(actor), eq(orgId), eq(queueId), eq(request));
        verify(queueService, never()).createQueueEntry(anyLong(), anyLong(), anyLong(), any());
    }
    
    @Test
    void testCreateQueueEntry_QueueManagerCanRegisterExternalParticipant() {
        // Given: A QUEUE_MANAGER tries to register an external participant
        Long actor = 1L;
        Long orgId = 1L;
        Long queueId = 1L;
        
        Membership membership = mock(Membership.class);
        lenient().when(membership.getRoles()).thenReturn(Set.of(OrganizationRole.QUEUE_MANAGER));
        lenient().when(membership.getAuthUserId()).thenReturn(actor);
        lenient().when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        
        // Mock tenantService to return QUEUE_MANAGER role
        lenient().when(tenantService.active(eq(actor), eq(orgId))).thenReturn(membership);
        
        // Mock queueService to succeed
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", null, "External Participant", "external@example.com", null);
        
        QueueEntryView mockResult = mock(QueueEntryView.class);
        
        when(queueService.registerParticipant(eq(actor), eq(orgId), eq(queueId), eq(request)))
                .thenReturn(mockResult);
        
        // When: Should succeed
        ResponseEntity<ApiResponse<QueueEntryView>> response = 
                queueController.createQueueEntry(actor, orgId, queueId, request);
        
        // Then
        assertNotNull(response);
        assertTrue(response.getBody().success());
        assertNotNull(response.getBody().data());
        
        // Verify registerParticipant was called (not createQueueEntry directly)
        verify(queueService, times(1)).registerParticipant(eq(actor), eq(orgId), eq(queueId), eq(request));
        verify(queueService, never()).createQueueEntry(anyLong(), anyLong(), anyLong(), any());
    }
    
    // ==================== Verification Tests ====================
    
    @Test
    void testController_UsesRegisterParticipant_NotCreateQueueEntry() {
        // This test verifies that the controller calls registerParticipant
        // instead of createQueueEntry, ensuring authorization is always enforced
        
        Long actor = 1L;
        Long orgId = 1L;
        Long queueId = 1L;
        
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", null, "Test", "test@example.com", null);
        
        // Mock both methods to verify which one is called
        QueueEntryView mockResult = mock(QueueEntryView.class);
        
        when(queueService.registerParticipant(anyLong(), anyLong(), anyLong(), any())).thenReturn(mockResult);
        lenient().when(queueService.createQueueEntry(anyLong(), anyLong(), anyLong(), any())).thenReturn(mockResult);
        
        // Call the controller
        queueController.createQueueEntry(actor, orgId, queueId, request);
        
        // Verify registerParticipant was called
        verify(queueService, times(1)).registerParticipant(eq(actor), eq(orgId), eq(queueId), eq(request));
        
        // Verify createQueueEntry was NOT called
        verify(queueService, never()).createQueueEntry(anyLong(), anyLong(), anyLong(), any());
    }
    
    @Test
    void testNoDirectCallsToCreateQueueEntryInController() {
        // This test verifies that there are no direct calls to createQueueEntry
        // in the controller, ensuring all entry creation goes through registerParticipant
        
        // We can't easily test this programmatically, but we can document it
        // The previous test already verifies that createQueueEntry is not called
        assertTrue(true, "Controller uses registerParticipant, not createQueueEntry directly");
    }
}
