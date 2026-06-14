package com.finsafe.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsafe.gateway.dto.PaymentRequest;
import com.finsafe.gateway.dto.PaymentResponse;
import com.finsafe.gateway.model.IdempotencyRecord;
import com.finsafe.gateway.repository.IdempotencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    @Value("${app.idempotency.ttl-hours:24}")
    private int ttlHours;

    /**
     * Main method: handles idempotency logic + payment processing.
     *
     * Flow:
     * 1. Acquire DB-level lock on the idempotency key row (handles race conditions)
     * 2. If key exists and COMPLETED  → return cached response
     * 3. If key exists and PROCESSING → another thread is working; wait & retry
     * 4. If key exists with DIFFERENT body → reject (fraud/error check)
     * 5. If key is new → process payment, save & return result
     *
     * @return Object[] { responseBody, httpStatus, cacheHit }
     */
    @Transactional
    public Object[] processPayment(String idempotencyKey, PaymentRequest request) throws Exception {
        String requestHash = hashRequestBody(request);

        // --- Step 1: Try to find an existing record (with DB lock) ---
        Optional<IdempotencyRecord> existing = repository.findByKeyWithLock(idempotencyKey);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();

            // --- Step 3: In-flight check (Bonus Task) ---
            if (record.getStatus() == IdempotencyRecord.RecordStatus.PROCESSING) {
                log.info("Key [{}] is currently PROCESSING. Waiting for it to complete...", idempotencyKey);
                // Since we hold the DB lock, this path means another transaction
                // just released the lock (race condition resolved at DB level).
                // We re-fetch to get the completed result.
                return waitAndReturnResult(idempotencyKey, requestHash);
            }

            // --- Step 4: Same key, different body (fraud check) ---
            if (!record.getRequestBodyHash().equals(requestHash)) {
                log.warn("Key [{}] reused with a different request body!", idempotencyKey);
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency key already used for a different request body."
                );
            }

            // --- Step 2: Cache hit — return saved response ---
            log.info("Cache hit for key [{}]. Returning saved response.", idempotencyKey);
            PaymentResponse cachedResponse = objectMapper.readValue(
                record.getResponseBody(), PaymentResponse.class
            );
            return new Object[]{ cachedResponse, record.getResponseStatus(), true };
        }

        // --- Step 5: New request — create PROCESSING record first ---
        IdempotencyRecord newRecord = IdempotencyRecord.builder()
            .idempotencyKey(idempotencyKey)
            .requestBodyHash(requestHash)
            .responseBody("")
            .responseStatus(0)
            .status(IdempotencyRecord.RecordStatus.PROCESSING)
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusHours(ttlHours))
            .build();
        repository.save(newRecord);

        // --- Simulate payment processing (2-second delay) ---
        log.info("Processing new payment for key [{}]...", idempotencyKey);
        Thread.sleep(2000);

        // --- Build response ---
        String transactionId = UUID.randomUUID().toString();
        PaymentResponse response = PaymentResponse.builder()
            .status("SUCCESS")
            .message(String.format("Charged %.0f %s", request.getAmount(), request.getCurrency()))
            .transactionId(transactionId)
            .amount(request.getAmount())
            .currency(request.getCurrency())
            .build();

        // --- Save COMPLETED record ---
        String responseJson = objectMapper.writeValueAsString(response);
        newRecord.setResponseBody(responseJson);
        newRecord.setResponseStatus(HttpStatus.CREATED.value());
        newRecord.setStatus(IdempotencyRecord.RecordStatus.COMPLETED);
        repository.save(newRecord);

        log.info("Payment processed successfully for key [{}]. TxID: {}", idempotencyKey, transactionId);
        return new Object[]{ response, HttpStatus.CREATED.value(), false };
    }

    /**
     * Bonus Task: Race condition — Request B arrives while Request A is PROCESSING.
     * We poll until the record transitions to COMPLETED, then return its result.
     */
    private Object[] waitAndReturnResult(String idempotencyKey, String requestHash) throws Exception {
        int maxAttempts = 15;
        int attempt = 0;

        while (attempt < maxAttempts) {
            attempt++;
            Thread.sleep(500); // wait 500ms between polls

            Optional<IdempotencyRecord> record = repository.findByIdempotencyKey(idempotencyKey);

            if (record.isPresent() && record.get().getStatus() == IdempotencyRecord.RecordStatus.COMPLETED) {
                IdempotencyRecord completed = record.get();

                // Verify the body hash matches too
                if (!completed.getRequestBodyHash().equals(requestHash)) {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency key already used for a different request body."
                    );
                }

                PaymentResponse cachedResponse = objectMapper.readValue(
                    completed.getResponseBody(), PaymentResponse.class
                );
                log.info("In-flight wait resolved for key [{}]. Returning result.", idempotencyKey);
                return new Object[]{ cachedResponse, completed.getResponseStatus(), true };
            }
        }

        // Timed out waiting — something went wrong with the original request
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Payment processing timed out. Please retry."
        );
    }

    /**
     * Developer's Choice Feature: Automatic TTL-based cleanup.
     * Expired idempotency records are purged every hour to keep the DB lean.
     * Runs every hour at the top of the hour.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanupExpiredRecords() {
        List<IdempotencyRecord> expired = repository.findByExpiresAtBefore(LocalDateTime.now());
        if (!expired.isEmpty()) {
            repository.deleteAll(expired);
            log.info("Cleaned up {} expired idempotency records.", expired.size());
        }
    }

    /**
     * SHA-256 hash of the request body to detect payload tampering.
     */
    private String hashRequestBody(PaymentRequest request) throws Exception {
        String json = objectMapper.writeValueAsString(request);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(json.getBytes());
        return HexFormat.of().formatHex(hash);
    }
}
