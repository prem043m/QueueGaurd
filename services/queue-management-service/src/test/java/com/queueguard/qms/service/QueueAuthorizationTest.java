package com.queueguard.qms.service;

import com.queueguard.common.exception.BusinessException;
import com.queueguard.common.exception.ForbiddenException;
import com.queueguard.qms.domain.*;
import com.queueguard.qms.dto.QueueDtos.*;
import com.queueguard.qms.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for authorization, particularly external participant registration.
 * These tests do not require Docker and can run with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class QueueAuthorizationTest {
    
    @Mock
    private QueueConfigurationRepository queueConfigRepo;
    
    @Mock
    private QueueEntryRepository queueEntryRepo;
    
    @Mock
    private QueueAssignmentRepository queueAssignmentRepo;
    
    @Mock
    private QueueEventRepository queueEventRepo;
    
    @Mock
    private OutboxEventRepository outboxEventRepo;
    
    @Mock
    private TokenSequenceRepository tokenSequenceRepo;
    
    @Mock
    private TenantService tenantService;
    
    @Mock
    private TokenGenerator tokenGenerator;
    
    @InjectMocks
    private QueueService queueService;
    
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
        
        // Lenient stubbings that may not be used in all tests
        lenient().when(queueConfigRepo.findByOrganizationAndIdForUpdate(anyLong(), anyLong()))
                .thenReturn(Optional.of(config));
        lenient().when(queueConfigRepo.findByOrganizationAndId(anyLong(), anyLong()))
                .thenReturn(Optional.of(config));
    }
    
    // ==================== External Participant Registration Tests ====================
    
    @Test
    void testExternalParticipantRegistration_MemberCannotRegisterExternal() {
        // Given: A MEMBER (not admin) tries to register an external participant
        Membership membership = mock(Membership.class);
        lenient().when(membership.getRoles()).thenReturn(Set.of(OrganizationRole.MEMBER));
        lenient().when(membership.getAuthUserId()).thenReturn(1L);
        lenient().when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        when(tenantService.active(anyLong(), anyLong())).thenReturn(membership);
        
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", null, "External Participant", "external@example.com", null);
        
        // When/Then: Should throw ForbiddenException
        assertThrows(ForbiddenException.class, () -> 
            queueService.registerParticipant(1L, 1L, 1L, request));
        
        // Verify createQueueEntry was not called
        verify(queueEntryRepo, never()).save(any());
    }
    
    @Test
    void testExternalParticipantRegistration_OrgAdminCanRegisterExternal() {
        // Given: An ORG_ADMIN tries to register an external participant
        Membership membership = mock(Membership.class);
        lenient().when(membership.getRoles()).thenReturn(Set.of(OrganizationRole.ORG_ADMIN));
        lenient().when(membership.getAuthUserId()).thenReturn(1L);
        lenient().when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        when(tenantService.active(anyLong(), anyLong())).thenReturn(membership);
        
        QueueEntry entry = new QueueEntry();
        entry.setId(1L);
        entry.setTokenNumber("TEST-001");
        
        lenient().when(tokenGenerator.generateToken(anyLong(), anyString())).thenReturn("TEST-001");
        lenient().when(queueEntryRepo.existsByTokenNumberAndOrganizationId(anyString(), anyLong())).thenReturn(false);
        lenient().when(queueEntryRepo.save(any(QueueEntry.class))).thenReturn(entry);
        
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", null, "External Participant", "external@example.com", null);
        
        // When: Should succeed
        QueueEntryView result = queueService.registerParticipant(1L, 1L, 1L, request);
        
        // Then
        assertNotNull(result);
        verify(queueEntryRepo, times(1)).save(any());
    }
    
    @Test
    void testExternalParticipantRegistration_QueueManagerCanRegisterExternal() {
        // Given: A QUEUE_MANAGER tries to register an external participant
        Membership membership = mock(Membership.class);
        lenient().when(membership.getRoles()).thenReturn(Set.of(OrganizationRole.QUEUE_MANAGER));
        lenient().when(membership.getAuthUserId()).thenReturn(1L);
        lenient().when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        when(tenantService.active(anyLong(), anyLong())).thenReturn(membership);
        
        QueueEntry entry = new QueueEntry();
        entry.setId(1L);
        entry.setTokenNumber("TEST-001");
        
        lenient().when(tokenGenerator.generateToken(anyLong(), anyString())).thenReturn("TEST-001");
        lenient().when(queueEntryRepo.existsByTokenNumberAndOrganizationId(anyString(), anyLong())).thenReturn(false);
        lenient().when(queueEntryRepo.save(any(QueueEntry.class))).thenReturn(entry);
        
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", null, "External Participant", "external@example.com", null);
        
        // When: Should succeed
        QueueEntryView result = queueService.registerParticipant(1L, 1L, 1L, request);
        
        // Then
        assertNotNull(result);
        verify(queueEntryRepo, times(1)).save(any());
    }
    
    @Test
    void testSelfRegistration_MemberCanRegisterThemselves() {
        // Given: A MEMBER tries to register themselves (participantUserId == actor)
        Membership membership = mock(Membership.class);
        lenient().when(membership.getRoles()).thenReturn(Set.of(OrganizationRole.MEMBER));
        lenient().when(membership.getAuthUserId()).thenReturn(1L);
        lenient().when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        when(tenantService.active(anyLong(), anyLong())).thenReturn(membership);
        
        QueueEntry entry = new QueueEntry();
        entry.setId(1L);
        entry.setTokenNumber("TEST-001");
        
        lenient().when(tokenGenerator.generateToken(anyLong(), anyString())).thenReturn("TEST-001");
        lenient().when(queueEntryRepo.existsByTokenNumberAndOrganizationId(anyString(), anyLong())).thenReturn(false);
        lenient().when(queueEntryRepo.save(any(QueueEntry.class))).thenReturn(entry);
        
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", 1L, "Self", "self@example.com", null);  // participantUserId == actor (1L)
        
        // When: Should succeed
        QueueEntryView result = queueService.registerParticipant(1L, 1L, 1L, request);
        
        // Then
        assertNotNull(result);
        verify(queueEntryRepo, times(1)).save(any());
    }
    
    @Test
    void testRegisterOtherParticipant_MemberCannotRegisterOther() {
        // Given: A MEMBER tries to register another user (participantUserId != actor)
        Membership membership = mock(Membership.class);
        lenient().when(membership.getRoles()).thenReturn(Set.of(OrganizationRole.MEMBER));
        lenient().when(membership.getAuthUserId()).thenReturn(1L);
        lenient().when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        when(tenantService.active(anyLong(), anyLong())).thenReturn(membership);
        
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", 2L, "Other User", "other@example.com", null);  // participantUserId != actor
        
        // When/Then: Should throw ForbiddenException
        assertThrows(ForbiddenException.class, () -> 
            queueService.registerParticipant(1L, 1L, 1L, request));
        
        verify(queueEntryRepo, never()).save(any());
    }
    
    @Test
    void testRegisterOtherParticipant_OrgAdminCanRegisterOther() {
        // Given: An ORG_ADMIN tries to register another user
        Membership membership = mock(Membership.class);
        lenient().when(membership.getRoles()).thenReturn(Set.of(OrganizationRole.ORG_ADMIN));
        lenient().when(membership.getAuthUserId()).thenReturn(1L);
        lenient().when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        when(tenantService.active(anyLong(), anyLong())).thenReturn(membership);
        
        QueueEntry entry = new QueueEntry();
        entry.setId(1L);
        entry.setTokenNumber("TEST-001");
        
        lenient().when(tokenGenerator.generateToken(anyLong(), anyString())).thenReturn("TEST-001");
        lenient().when(queueEntryRepo.existsByTokenNumberAndOrganizationId(anyString(), anyLong())).thenReturn(false);
        lenient().when(queueEntryRepo.save(any(QueueEntry.class))).thenReturn(entry);
        
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", 2L, "Other User", "other@example.com", null);
        
        // When: Should succeed
        QueueEntryView result = queueService.registerParticipant(1L, 1L, 1L, request);
        
        // Then
        assertNotNull(result);
        verify(queueEntryRepo, times(1)).save(any());
    }
    
    @Test
    void testRegisterOtherParticipant_QueueManagerCanRegisterOther() {
        // Given: A QUEUE_MANAGER tries to register another user
        Membership membership = mock(Membership.class);
        lenient().when(membership.getRoles()).thenReturn(Set.of(OrganizationRole.QUEUE_MANAGER));
        lenient().when(membership.getAuthUserId()).thenReturn(1L);
        lenient().when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        when(tenantService.active(anyLong(), anyLong())).thenReturn(membership);
        
        QueueEntry entry = new QueueEntry();
        entry.setId(1L);
        entry.setTokenNumber("TEST-001");
        
        lenient().when(tokenGenerator.generateToken(anyLong(), anyString())).thenReturn("TEST-001");
        lenient().when(queueEntryRepo.existsByTokenNumberAndOrganizationId(anyString(), anyLong())).thenReturn(false);
        lenient().when(queueEntryRepo.save(any(QueueEntry.class))).thenReturn(entry);
        
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", 2L, "Other User", "other@example.com", null);
        
        // When: Should succeed
        QueueEntryView result = queueService.registerParticipant(1L, 1L, 1L, request);
        
        // Then
        assertNotNull(result);
        verify(queueEntryRepo, times(1)).save(any());
    }
    
    // ==================== Tenant Isolation Tests ====================
    
    @Test
    void testCreateQueueEntry_ValidatesTenant() {
        // Given: Actor doesn't have access to organization
        when(tenantService.active(anyLong(), anyLong())).thenThrow(new ForbiddenException("Access denied"));
        
        CreateQueueEntryRequest request = new CreateQueueEntryRequest(
                "EXT-001", 1L, "Test", "test@example.com", null);
        
        // When/Then: Should throw ForbiddenException
        assertThrows(ForbiddenException.class, () -> 
            queueService.createQueueEntry(1L, 1L, 1L, request));
        
        verify(queueConfigRepo, never()).findByOrganizationAndIdForUpdate(anyLong(), anyLong());
    }
    
    @Test
    void testCallNext_ValidatesTenant() {
        // Given: Actor doesn't have access to organization
        when(tenantService.active(anyLong(), anyLong())).thenThrow(new ForbiddenException("Access denied"));
        
        CallNextRequest request = new CallNextRequest("service-point-1", "idempotency-key-1");
        
        // When/Then: Should throw ForbiddenException
        assertThrows(ForbiddenException.class, () -> 
            queueService.callNext(1L, 1L, 1L, request));
        
        verify(queueEntryRepo, never()).findNextFifoEntryForUpdate(anyLong(), anyLong());
    }
    
    // ==================== Call-Next Status Tests ====================
    
    @Test
    void testCallNext_OnlySelectsWaitingEntries() {
        // Given: Queue with WAITING and CALLED entries
        QueueEntry waitingEntry = new QueueEntry();
        waitingEntry.setId(1L);
        waitingEntry.setStatus(QueueEntryStatus.WAITING);
        waitingEntry.setJoinedAt(Instant.now());
        waitingEntry.setOrganizationId(1L);
        waitingEntry.setQueueConfigurationId(1L);
        
        QueueEntry calledEntry = new QueueEntry();
        calledEntry.setId(2L);
        calledEntry.setStatus(QueueEntryStatus.CALLED);
        calledEntry.setJoinedAt(Instant.now().minusSeconds(10));
        
        // Mock: Only return WAITING entries
        lenient().when(queueEntryRepo.findNextFifoEntryForUpdate(anyLong(), anyLong()))
                .thenReturn(Optional.of(waitingEntry));
        lenient().when(queueEntryRepo.save(any(QueueEntry.class))).thenReturn(waitingEntry);
        
        Membership membership = mock(Membership.class);
        lenient().when(membership.getRoles()).thenReturn(Set.of(OrganizationRole.ORG_ADMIN));
        lenient().when(membership.getAuthUserId()).thenReturn(1L);
        lenient().when(membership.getStatus()).thenReturn(MembershipStatus.ACTIVE);
        when(tenantService.active(anyLong(), anyLong())).thenReturn(membership);
        
        // Mock queue configuration
        QueueConfiguration config = new QueueConfiguration();
        config.setId(1L);
        config.setOrganizationId(1L);
        config.setStatus(QueueStatus.OPEN);
        config.setOrderingPolicy(OrderingPolicy.FIFO);
        lenient().when(queueConfigRepo.findByOrganizationAndId(anyLong(), anyLong()))
                .thenReturn(Optional.of(config));
        lenient().when(queueConfigRepo.findById(anyLong()))
                .thenReturn(Optional.of(config));
        lenient().when(queueEntryRepo.countEntriesAheadFifo(anyLong(), anyLong(), any(), anyLong()))
                .thenReturn(0L);
        
        QueueAssignment assignment = new QueueAssignment();
        assignment.setId(1L);
        lenient().when(queueAssignmentRepo.save(any(QueueAssignment.class))).thenReturn(assignment);
        
        CallNextRequest request = new CallNextRequest("service-point-1", "idempotency-key-1");
        
        // When
        CallNextResponse response = queueService.callNext(1L, 1L, 1L, request);
        
        // Then: Should get the WAITING entry
        assertNotNull(response);
        assertNotNull(response.entry());
        assertEquals(1L, response.entry().id());
        
        // Verify the repository was called with correct parameters
        verify(queueEntryRepo, times(1)).findNextFifoEntryForUpdate(eq(1L), eq(1L));
    }
}
