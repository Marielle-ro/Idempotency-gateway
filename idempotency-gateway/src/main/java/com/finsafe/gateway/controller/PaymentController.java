package com.finsafe.gateway.controller;

import com.finsafe.gateway.dto.PaymentRequest;
import com.finsafe.gateway.dto.PaymentResponse;
import com.finsafe.gateway.service.IdempotencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final IdempotencyService idempotencyService;

    /**
     * POST /api/v1/process-payment
     *
     * Required header: Idempotency-Key: <unique-string>
     * Body: { "amount": 100, "currency": "GHS" }
     */
    @PostMapping("/process-payment")
    public ResponseEntity<PaymentResponse> processPayment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        // Validate Idempotency-Key header presence
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Missing required header: Idempotency-Key"
            );
        }

        // Validate key length (max 255 chars for safety)
        if (idempotencyKey.length() > 255) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Idempotency-Key must not exceed 255 characters"
            );
        }

        try {
            Object[] result = idempotencyService.processPayment(idempotencyKey, request);

            PaymentResponse responseBody = (PaymentResponse) result[0];
            int statusCode = (int) result[1];
            boolean cacheHit = (boolean) result[2];

            return ResponseEntity
                .status(statusCode)
                .header("X-Cache-Hit", String.valueOf(cacheHit))
                .header("Idempotency-Key", idempotencyKey)
                .body(responseBody);

        } catch (ResponseStatusException e) {
            throw e; // Let GlobalExceptionHandler handle it
        } catch (Exception e) {
            log.error("Unexpected error processing payment for key [{}]: {}", idempotencyKey, e.getMessage());
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again."
            );
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("FinSafe Idempotency Gateway is running ✓");
    }
}
