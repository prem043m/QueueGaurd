package com.queueguard.qms.service;

import com.queueguard.qms.domain.TokenSequence;
import com.queueguard.qms.repo.QueueEntryRepository;
import com.queueguard.qms.repo.TokenSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Generates unique, human-readable queue tokens.
 * 
 * Token format: {queueCode}-{date}-{sequence}
 * Example: Q001-20241107-001
 * 
 * Token generation strategy:
 * - Uses queue code prefix for identification
 * - Includes date for daily reset and readability
 * - Uses sequential number within the day from database
 * - Guarantees uniqueness within organization scope
 * 
 * Concurrency control:
 * - Uses database sequence table with pessimistic locking as primary protection
 * - Falls back to checking existing tokens if needed
 * - Database unique constraint (organization_id, token_number) provides final protection
 */
@Component
public class TokenGenerator {
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String SEPARATOR = "-";
    
    private final QueueEntryRepository queueEntryRepository;
    private final TokenSequenceRepository tokenSequenceRepository;
    
    public TokenGenerator(QueueEntryRepository queueEntryRepository, 
                          TokenSequenceRepository tokenSequenceRepository) {
        this.queueEntryRepository = queueEntryRepository;
        this.tokenSequenceRepository = tokenSequenceRepository;
    }
    
    /**
     * Generates a unique token for the given queue configuration.
     * Uses PostgreSQL atomic operations for concurrency safety.
     * 
     * Algorithm:
     * 1. INSERT sequence row with ON CONFLICT DO NOTHING (handles first-insert race)
     * 2. SELECT ... FOR UPDATE to lock the row
     * 3. Increment the sequence
     * 4. Save the updated sequence
     * 5. Return the token
     * 
     * This ensures:
     * - No duplicate sequence rows
     * - No duplicate token numbers
     * - Correct behavior under concurrent first insertion
     * - Token generation participates in the caller's transaction
     * 
     * @param organizationId the organization ID
     * @param queueCode the queue code
     * @return a unique token number
     */
    @Transactional
    public String generateToken(Long organizationId, String queueCode) {
        String datePart = LocalDate.now().format(DATE_FORMATTER);
        String prefix = queueCode + SEPARATOR + datePart + SEPARATOR;
        
        // Step 1: Ensure sequence row exists (handles first-insert race)
        tokenSequenceRepository.insertSequenceIfNotExists(organizationId, queueCode, datePart);
        
        // Step 2: Get and lock the sequence row
        TokenSequence sequence = tokenSequenceRepository
                .findByOrganizationIdAndQueueCodeAndDatePartForUpdate(organizationId, queueCode, datePart)
                .orElseThrow(() -> new IllegalStateException(
                        "Token sequence row missing after insert attempt"));
        
        // Step 3: Increment and save within the same transaction
        int nextSequence = sequence.next();
        tokenSequenceRepository.save(sequence);
        
        String token = prefix + String.format("%06d", nextSequence);
        
        // Step 4: Final sanity check - ensure token doesn't already exist
        // This should never happen with proper locking, but provides defense in depth
        int retries = 0;
        while (queueEntryRepository.existsByTokenNumberAndOrganizationId(token, organizationId) 
               && retries < 10) {
            nextSequence++;
            sequence.setLastSequence(nextSequence);
            tokenSequenceRepository.save(sequence);
            token = prefix + String.format("%06d", nextSequence);
            retries++;
        }
        
        if (retries >= 10) {
            throw new IllegalStateException("Failed to generate unique token after 10 retries");
        }
        
        return token;
    }
    
    /**
     * Generates a token with a specific sequence number.
     * Used when a token needs to be regenerated with a known sequence.
     * 
     * @param organizationId the organization ID
     * @param queueCode the queue code
     * @param sequence the sequence number
     * @return a token with the specified sequence
     */
    public String generateTokenWithSequence(Long organizationId, String queueCode, int sequence) {
        String datePart = LocalDate.now().format(DATE_FORMATTER);
        String token = queueCode + SEPARATOR + datePart + SEPARATOR + String.format("%03d", sequence);
        
        // Verify uniqueness
        if (queueEntryRepository.existsByTokenNumberAndOrganizationId(token, organizationId)) {
            throw new IllegalStateException("Token " + token + " already exists");
        }
        
        return token;
    }
    
    /**
     * Validates a token format.
     * 
     * @param token the token to validate
     * @param queueCode the expected queue code
     * @return true if the token is valid
     */
    public boolean isValidToken(String token, String queueCode) {
        if (token == null || queueCode == null) {
            return false;
        }
        
        String[] parts = token.split(SEPARATOR);
        if (parts.length != 3) {
            return false;
        }
        
        // Check queue code matches
        if (!parts[0].equals(queueCode)) {
            return false;
        }
        
        // Check date format
        try {
            LocalDate.parse(parts[1], DATE_FORMATTER);
        } catch (Exception e) {
            return false;
        }
        
        // Check sequence is numeric
        try {
            Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Extracts the queue code from a token.
     * 
     * @param token the token
     * @return the queue code, or null if invalid format
     */
    public String extractQueueCode(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        
        String[] parts = token.split(SEPARATOR);
        if (parts.length >= 1 && !parts[0].isEmpty()) {
            return parts[0];
        }
        return null;
    }
    
    /**
     * Extracts the date part from a token.
     * 
     * @param token the token
     * @return the date part, or null if invalid format
     */
    public String extractDatePart(String token) {
        if (token == null) {
            return null;
        }
        
        String[] parts = token.split(SEPARATOR);
        if (parts.length >= 2) {
            return parts[1];
        }
        return null;
    }
    
    /**
     * Extracts the sequence number from a token.
     * 
     * @param token the token
     * @return the sequence number, or -1 if invalid format
     */
    public int extractSequence(String token) {
        if (token == null || token.isEmpty()) {
            return -1;
        }
        
        String[] parts = token.split(SEPARATOR);
        if (parts.length >= 3 && !parts[2].isEmpty()) {
            try {
                return Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }
}
