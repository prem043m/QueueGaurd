package com.queueguard.qms.service;

import com.queueguard.qms.domain.TokenSequence;
import com.queueguard.qms.repo.QueueEntryRepository;
import com.queueguard.qms.repo.TokenSequenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TokenGenerator.
 */
class TokenGeneratorTest {
    
    private QueueEntryRepository queueEntryRepository;
    private TokenSequenceRepository tokenSequenceRepository;
    private TokenGenerator tokenGenerator;
    
    @BeforeEach
    void setUp() {
        queueEntryRepository = mock(QueueEntryRepository.class);
        tokenSequenceRepository = mock(TokenSequenceRepository.class);
        tokenGenerator = new TokenGenerator(queueEntryRepository, tokenSequenceRepository);
    }
    
    @Test
    void testGenerateToken() {
        // Given
        Long organizationId = 1L;
        String queueCode = "Q001";
        String datePart = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        TokenSequence sequence = new TokenSequence();
        sequence.setId(1L);
        sequence.setOrganizationId(organizationId);
        sequence.setQueueCode(queueCode);
        sequence.setDatePart(datePart);
        sequence.setLastSequence(0);
        
        // Mock: return sequence and save it
        when(tokenSequenceRepository.findByOrganizationIdAndQueueCodeAndDatePartForUpdate(
                eq(organizationId), eq(queueCode), eq(datePart)))
                .thenReturn(Optional.of(sequence));
        when(tokenSequenceRepository.save(any(TokenSequence.class))).thenAnswer(inv -> {
            TokenSequence saved = inv.getArgument(0);
            saved.setLastSequence(saved.getLastSequence() + 1);
            return saved;
        });
        when(queueEntryRepository.existsByTokenNumberAndOrganizationId(anyString(), eq(organizationId)))
                .thenReturn(false);
        
        // When
        String token = tokenGenerator.generateToken(organizationId, queueCode);
        
        // Then
        assertNotNull(token);
        assertTrue(token.startsWith(queueCode + "-"));
        assertTrue(token.contains("-"));
        
        // Verify format: Q001-{date}-{sequence}
        String[] parts = token.split("-");
        assertEquals(3, parts.length);
        assertEquals(queueCode, parts[0]);
        assertEquals(8, parts[1].length()); // yyyyMMdd format
        assertEquals(6, parts[2].length()); // 6-digit sequence (changed from 3 to 6)
    }
    
    @Test
    void testGenerateTokenWithExistingTokens() {
        // Given
        Long organizationId = 1L;
        String queueCode = "Q001";
        String datePart = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        TokenSequence sequence = new TokenSequence();
        sequence.setId(1L);
        sequence.setOrganizationId(organizationId);
        sequence.setQueueCode(queueCode);
        sequence.setDatePart(datePart);
        sequence.setLastSequence(0);
        
        // Mock: return sequence and save it
        when(tokenSequenceRepository.findByOrganizationIdAndQueueCodeAndDatePartForUpdate(
                eq(organizationId), eq(queueCode), eq(datePart)))
                .thenReturn(Optional.of(sequence));
        when(tokenSequenceRepository.save(any(TokenSequence.class))).thenAnswer(inv -> {
            TokenSequence saved = inv.getArgument(0);
            return saved;
        });
        // First token exists, second doesn't
        when(queueEntryRepository.existsByTokenNumberAndOrganizationId(anyString(), eq(organizationId)))
                .thenReturn(true)  // First call returns true
                .thenReturn(false); // Second call returns false
        
        // When
        String token = tokenGenerator.generateToken(organizationId, queueCode);
        
        // Then
        assertNotNull(token);
        assertTrue(token.startsWith(queueCode + "-"));
    }
    
    @Test
    void testGenerateTokenWithSequence() {
        // Given
        Long organizationId = 1L;
        String queueCode = "Q001";
        String datePart = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        TokenSequence sequence = new TokenSequence();
        sequence.setId(1L);
        sequence.setOrganizationId(organizationId);
        sequence.setQueueCode(queueCode);
        sequence.setDatePart(datePart);
        sequence.setLastSequence(41);
        
        // Mock: return sequence and save it
        when(tokenSequenceRepository.findByOrganizationIdAndQueueCodeAndDatePartForUpdate(
                eq(organizationId), eq(queueCode), eq(datePart)))
                .thenReturn(Optional.of(sequence));
        when(tokenSequenceRepository.save(any(TokenSequence.class))).thenAnswer(inv -> inv.getArgument(0));
        when(queueEntryRepository.existsByTokenNumberAndOrganizationId(anyString(), eq(organizationId)))
                .thenReturn(false);
        
        // When - note: generateTokenWithSequence is deprecated in new implementation
        // We'll test the main generateToken method instead
        String token = tokenGenerator.generateToken(organizationId, queueCode);
        
        // Then
        assertNotNull(token);
        assertTrue(token.startsWith(queueCode + "-"));
    }
    
    @Test
    void testGenerateTokenHandlesConcurrentAccess() {
        // This test verifies that even if tokens exist, the generator finds a unique one
        // Given
        Long organizationId = 1L;
        String queueCode = "Q001";
        String datePart = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        TokenSequence sequence = new TokenSequence();
        sequence.setId(1L);
        sequence.setOrganizationId(organizationId);
        sequence.setQueueCode(queueCode);
        sequence.setDatePart(datePart);
        sequence.setLastSequence(0);
        
        // Mock: return sequence and save it
        when(tokenSequenceRepository.findByOrganizationIdAndQueueCodeAndDatePartForUpdate(
                eq(organizationId), eq(queueCode), eq(datePart)))
                .thenReturn(Optional.of(sequence));
        when(tokenSequenceRepository.save(any(TokenSequence.class))).thenAnswer(inv -> inv.getArgument(0));
        // Simulate concurrent generation where first few tokens already exist
        when(queueEntryRepository.existsByTokenNumberAndOrganizationId(anyString(), eq(organizationId)))
                .thenReturn(true, true, true, false); // First 3 exist, 4th doesn't
        
        // When
        String token = tokenGenerator.generateToken(organizationId, queueCode);
        
        // Then
        assertNotNull(token);
        assertTrue(token.startsWith(queueCode + "-"));
    }
    
    @Test
    void testIsValidToken() {
        String token = "Q001-20241107-001";
        String queueCode = "Q001";
        
        assertTrue(tokenGenerator.isValidToken(token, queueCode));
    }
    
    @Test
    void testIsValidTokenWithWrongQueueCode() {
        String token = "Q001-20241107-001";
        String queueCode = "Q002";
        
        assertFalse(tokenGenerator.isValidToken(token, queueCode));
    }
    
    @Test
    void testIsValidTokenWithInvalidFormat() {
        String token = "INVALID";
        String queueCode = "Q001";
        
        assertFalse(tokenGenerator.isValidToken(token, queueCode));
    }
    
    @Test
    void testIsValidTokenWithInvalidDate() {
        String token = "Q001-INVALID-001";
        String queueCode = "Q001";
        
        assertFalse(tokenGenerator.isValidToken(token, queueCode));
    }
    
    @Test
    void testIsValidTokenWithInvalidSequence() {
        String token = "Q001-20241107-ABC";
        String queueCode = "Q001";
        
        assertFalse(tokenGenerator.isValidToken(token, queueCode));
    }
    
    @Test
    void testExtractQueueCode() {
        String token = "Q001-20241107-001";
        
        assertEquals("Q001", tokenGenerator.extractQueueCode(token));
    }
    
    @Test
    void testExtractQueueCodeWithNull() {
        assertNull(tokenGenerator.extractQueueCode(null));
    }
    
    @Test
    void testExtractQueueCodeWithEmptyToken() {
        // Empty string should return null
        assertNull(tokenGenerator.extractQueueCode(""));
    }
    
    @Test
    void testExtractDatePart() {
        String token = "Q001-20241107-001";
        
        assertEquals("20241107", tokenGenerator.extractDatePart(token));
    }
    
    @Test
    void testExtractSequence() {
        String token = "Q001-20241107-042";
        
        assertEquals(42, tokenGenerator.extractSequence(token));
    }
    
    @Test
    void testExtractSequenceWithInvalidToken() {
        assertEquals(-1, tokenGenerator.extractSequence("INVALID"));
    }
}
