package com.desafio.activation.infrastructure.persistence;

import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationStatus;
import org.springframework.data.jpa.domain.Specification;

/**
 * Specification em vez de {@code (:p is null or col = :p)}: no PostgreSQL esse padrão falha ao inferir
 * o tipo do parâmetro nulo e ainda atrapalha o uso dos índices.
 */
public final class ActivationRequestSpecifications {

    private ActivationRequestSpecifications() {
    }

    public static Specification<ActivationRequest> ownedBy(String clientId) {
        return (root, query, cb) -> cb.equal(root.get("clientId"), clientId);
    }

    public static Specification<ActivationRequest> hasStatus(ActivationStatus status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<ActivationRequest> hasCustomerId(String customerId) {
        return customerId == null ? null : (root, query, cb) -> cb.equal(root.get("customerId"), customerId);
    }
}
