package com.finsafe.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsafe.gateway.dto.PaymentRequest;
import com.finsafe.gateway.dto.PaymentResponse;
import com.finsafe.gateway.model.IdempotencyRecord;
import com.finsafe.gateway.repository.IdempotencyRepository;
import com.finsafe.gateway.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IdempotencyServiceTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void testFirstPaymentIsProcessed() throws Exception {
        PaymentRequest request = new PaymentRequest(100, "GHS");
        Object[] result = idempotencyService.processPayment("key-001", request);

        PaymentResponse response = (PaymentResponse) result[0];
        boolean cacheHit = (boolean) result[2];

        assertEquals("SUCCESS", response.getStatus());
        assertEquals("Charged 100 GHS", response.getMessage());
        assertFalse(cacheHit);
    }

    @Test
    void testDuplicateRequestReturnsCachedResponse() throws Exception {
        PaymentRequest request = new PaymentRequest(200, "GHS");

        // First call
        Object[] first = idempotencyService.processPayment("key-002", request);
        PaymentResponse firstResponse = (PaymentResponse) first[0];

        // Second call with same key + same body
        Object[] second = idempotencyService.processPayment("key-002", request);
        PaymentResponse secondResponse = (PaymentResponse) second[0];
        boolean cacheHit = (boolean) second[2];

        assertTrue(cacheHit);
        assertEquals(firstResponse.getTransactionId(), secondResponse.getTransactionId());
    }

    @Test
    void testSameKeyDifferentBodyThrowsConflict() throws Exception {
        PaymentRequest original = new PaymentRequest(100, "GHS");
        idempotencyService.processPayment("key-003", original);

        PaymentRequest tampered = new PaymentRequest(500, "GHS");

        assertThrows(Exception.class, () ->
            idempotencyService.processPayment("key-003", tampered)
        );
    }

    @Test
    void testRecordIsSavedToDatabase() throws Exception {
        PaymentRequest request = new PaymentRequest(150, "USD");
        idempotencyService.processPayment("key-004", request);

        Optional<IdempotencyRecord> saved = repository.findByIdempotencyKey("key-004");
        assertTrue(saved.isPresent());
        assertEquals(IdempotencyRecord.RecordStatus.COMPLETED, saved.get().getStatus());
    }
}
