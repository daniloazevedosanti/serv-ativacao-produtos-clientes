package com.desafio.activation.infrastructure.persistence;

import com.desafio.activation.domain.ActivationRequest;
import com.desafio.activation.domain.ActivationStatus;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filtros dinâmicos via Criteria API. Evita o clássico {@code (:param is null or col = :param)},
 * que no PostgreSQL pode falhar por não conseguir inferir o tipo do parâmetro nulo e
 * atrapalha o uso de índices.
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
