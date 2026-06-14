package com.finsafe.gateway.repository;

import com.finsafe.gateway.model.IdempotencyRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    /**
     * Find a record by key — with a PESSIMISTIC WRITE lock.
     * This is the key to solving the race condition (Bonus task):
     * When two identical requests arrive simultaneously, the DB lock
     * ensures only one proceeds; the other waits at the DB level.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM IdempotencyRecord r WHERE r.idempotencyKey = :key")
    Optional<IdempotencyRecord> findByKeyWithLock(@Param("key") String key);

    Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);

    // Used by the cleanup scheduler
    List<IdempotencyRecord> findByExpiresAtBefore(LocalDateTime dateTime);
}
