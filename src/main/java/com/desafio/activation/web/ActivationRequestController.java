package com.desafio.activation.web;

import com.desafio.activation.application.ActivationRequestFilter;
import com.desafio.activation.application.ActivationRequestService;
import com.desafio.activation.application.CreateActivationCommand;
import com.desafio.activation.application.CreationResult;
import com.desafio.activation.domain.ActivationStatus;
import com.desafio.activation.web.dto.ActivationRequestResponse;
import com.desafio.activation.web.dto.CreateActivationRequest;
import com.desafio.activation.web.dto.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController {

    static final String IDEMPOTENT_REPLAY_HEADER = "Idempotent-Replayed";

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    /**
     * 201 + Location: solicitação criada.
     * 200 + Location + {@code Idempotent-Replayed: true}: mesmo externalId e mesmo conteúdo (retry).
     * 409: mesmo externalId com conteúdo diferente.
     */
    @PostMapping
    public ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest body,
                                                            @AuthenticationPrincipal Jwt jwt) {
        var command = new CreateActivationCommand(body.externalId(), body.customerId(), body.productCode(),
                AuthenticatedClient.idOf(jwt));
        CreationResult result = service.create(command);
        var response = ActivationRequestResponse.from(result.request());
        URI location = locationOf(response.id());
        return switch (result) {
            case CreationResult.Created created -> ResponseEntity.created(location).body(response);
            case CreationResult.Replayed replayed -> ResponseEntity.ok()
                    .location(location)
                    .header(IDEMPOTENT_REPLAY_HEADER, "true")
                    .body(response);
        };
    }

    @GetMapping("/{id}")
    public ActivationRequestResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ActivationRequestResponse.from(service.get(id, AuthenticatedClient.idOf(jwt)));
    }

    @GetMapping
    public PageResponse<ActivationRequestResponse> list(
            @RequestParam(required = false) ActivationStatus status,
            @RequestParam(required = false) @Size(max = 64) String customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal Jwt jwt) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = service.list(new ActivationRequestFilter(status, customerId), AuthenticatedClient.idOf(jwt),
                pageable);
        return PageResponse.from(result, ActivationRequestResponse::from);
    }

    private static URI locationOf(UUID id) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/requests/{id}")
                .buildAndExpand(id)
                .toUri();
    }
}
