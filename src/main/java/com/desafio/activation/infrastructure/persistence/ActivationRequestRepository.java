package com.desafio.activation.infrastructure.persistence;

import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository
        extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

    Optional<ActivationRequest> findByExternalId(String externalId);

    Optional<ActivationRequest> findByIdAndClientId(UUID id, String clientId);

    @Query("""
            select r.id from ActivationRequest r
             where r.status = com.desafio.activation.domain.ActivationStatus.PENDING
               and r.nextAttemptAt <= :now
             order by r.nextAttemptAt
            """)
    List<UUID> findDueIds(@Param("now") Instant now, Pageable page);

    @Query("""
            select r.id from ActivationRequest r
             where r.status = com.desafio.activation.domain.ActivationStatus.PROCESSING
               and r.updatedAt < :threshold
             order by r.updatedAt
            """)
    List<UUID> findStuckProcessingIds(@Param("threshold") Instant threshold, Pageable page);

    @Query("select r.status as status, count(r) as total from ActivationRequest r group by r.status")
    List<StatusCount> countByStatus();

    interface StatusCount {
        ActivationStatus getStatus();

        long getTotal();
    }
}
