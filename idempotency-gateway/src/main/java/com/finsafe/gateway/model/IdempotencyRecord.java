package com.finsafe.gateway.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    /**
     * SHA-256 hash of the original request body.
     * Used to detect if the same key is reused with a different payload (fraud check).
     */
    @Column(name = "request_body_hash", nullable = false)
    private String requestBodyHash;

    /**
     * The saved response body to replay on duplicate requests.
     */
    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    /**
     * HTTP status code of the original response.
     */
    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    /**
     * PROCESSING  — request is currently being handled (in-flight lock)
     * COMPLETED   — request finished successfully
     */
    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private RecordStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public enum RecordStatus {
        PROCESSING,
        COMPLETED
    }
}
