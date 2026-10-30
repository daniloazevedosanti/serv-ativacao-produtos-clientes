package com.desafio.activation.application;

import com.desafio.activation.domain.ActivationStatus;
import com.desafio.activation.infrastructure.persistence.ActivationRequestRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * Atualizado em intervalo fixo, e não a cada scrape, para não pôr um COUNT no caminho do Prometheus.
 */
@Component
@ConditionalOnProperty(name = "activation.dispatch.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ActivationStatusMetrics {

    private final ActivationRequestRepository repository;
    private final MultiGauge gauge;

    public ActivationStatusMetrics(ActivationRequestRepository repository, MeterRegistry registry) {
        this.repository = repository;
        this.gauge = MultiGauge.builder("activation.requests")
                .description("Quantidade de solicitações por status")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${activation.metrics.status-refresh-ms:30000}")
    public void refresh() {
        Map<ActivationStatus, Long> counts = new EnumMap<>(ActivationStatus.class);
        Arrays.stream(ActivationStatus.values()).forEach(s -> counts.put(s, 0L));
        repository.countByStatus().forEach(c -> counts.put(c.getStatus(), c.getTotal()));
        gauge.register(counts.entrySet().stream()
                .map(e -> MultiGauge.Row.of(Tags.of("status", e.getKey().name()), e.getValue()))
                .toList(), true);
    }
}
