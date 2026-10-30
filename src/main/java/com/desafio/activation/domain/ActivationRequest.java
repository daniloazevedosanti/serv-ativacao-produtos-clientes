package com.desafio.activation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Status só muda pelos métodos de domínio; não há setter de status de propósito.
 */
@Entity
@Table(name = "activation_request")
public class ActivationRequest {

    private static final int MAX_REASON_LENGTH = 500;

    @Id
    private UUID id;

    @Column(name = "external_id", nullable = false, updatable = false, length = 64)
    private String externalId;

    @Column(name = "customer_id", nullable = false, updatable = false, length = 64)
    private String customerId;

    @Column(name = "product_code", nullable = false, updatable = false, length = 64)
    private String productCode;

    /** client_id do token que criou a solicitação; toda consulta filtra por ele (BOLA). */
    @Column(name = "client_id", nullable = false, updatable = false, length = 128)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "failure_reason", length = MAX_REASON_LENGTH)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Long e não long: com id gerado na aplicação, version == null é o que faz o Spring Data
    // chamar persist em vez de merge (sem SELECT extra por criação).
    @Version
    private Long version;

    protected ActivationRequest() {
    }

    private ActivationRequest(UUID id, String externalId, String customerId, String productCode,
                              String clientId, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.externalId = requireText(externalId, "externalId");
        this.customerId = requireText(customerId, "customerId");
        this.productCode = requireText(productCode, "productCode");
        this.clientId = requireText(clientId, "clientId");
        this.status = ActivationStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ActivationRequest create(String externalId, String customerId, String productCode,
                                           String clientId, Instant now) {
        return new ActivationRequest(UUID.randomUUID(), externalId, customerId, productCode, clientId, now);
    }

    public void startProcessing(Instant now) {
        transitionTo(ActivationStatus.PROCESSING, now);
        this.attempts++;
        this.nextAttemptAt = null;
    }

    public void complete(Instant now) {
        transitionTo(ActivationStatus.COMPLETED, now);
        this.failureReason = null;
        this.nextAttemptAt = null;
    }

    public void fail(String reason, Instant now) {
        transitionTo(ActivationStatus.FAILED, now);
        this.failureReason = truncate(reason);
        this.nextAttemptAt = null;
    }

    /** Falha temporária volta para PENDING: assim PROCESSING sempre significa "chamada em andamento". */
    public void retryLater(String reason, Instant nextAttemptAt, Instant now) {
        transitionTo(ActivationStatus.PENDING, now);
        this.failureReason = truncate(reason);
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
    }

    public boolean isDue(Instant now) {
        return status == ActivationStatus.PENDING && nextAttemptAt != null && !nextAttemptAt.isAfter(now);
    }

    public boolean hasSameContent(String customerId, String productCode, String clientId) {
        return this.customerId.equals(customerId)
                && this.productCode.equals(productCode)
                && this.clientId.equals(clientId);
    }

    public boolean isOwnedBy(String clientId) {
        return this.clientId.equals(clientId);
    }

    private void transitionTo(ActivationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(status, target);
        }
        this.status = target;
        this.updatedAt = Objects.requireNonNull(now);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " é obrigatório");
        }
        return value;
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getClientId() {
        return clientId;
    }

    public ActivationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
