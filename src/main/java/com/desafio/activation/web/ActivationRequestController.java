package com.desafio.activation.web;

import com.desafio.activation.application.ActivationRequestFilter;
import com.desafio.activation.application.ActivationRequestService;
import com.desafio.activation.application.CreateActivationCommand;
import com.desafio.activation.application.CreationResult;
import com.desafio.activation.domain.ActivationStatus;
import com.desafio.activation.web.dto.ActivationRequestResponse;
import com.desafio.activation.web.dto.CreateActivationRequest;
import com.desafio.activation.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ProblemDetail;
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
@Tag(name = "Solicitações de ativação", description = "Registro e consulta de solicitações de ativação de produtos")
@ApiResponse(responseCode = "401", description = "Token ausente, inválido ou expirado",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "Token sem o escopo necessário",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Limite de requisições excedido (header Retry-After)",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
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
    @Operation(summary = "Cria uma solicitação de ativação",
            description = """
                    Registra a solicitação como PENDING e responde imediatamente; a ativação no provedor \
                    externo acontece de forma assíncrona (acompanhe pelo GET). Idempotente por externalId. \
                    Escopo exigido: activation:write.""")
    @ApiResponse(responseCode = "201", description = "Solicitação criada",
            headers = @Header(name = "Location", description = "URL da solicitação criada"))
    @ApiResponse(responseCode = "200", description = "Repetição idempotente: mesmo externalId e mesmo conteúdo",
            headers = @Header(name = IDEMPOTENT_REPLAY_HEADER, description = "true"))
    @ApiResponse(responseCode = "400", description = "Dados inválidos ou JSON malformado",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "externalId já utilizado com dados diferentes",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "Corpo da requisição acima de 16 KB",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest body,
                                                            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
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
    @Operation(summary = "Consulta uma solicitação pelo id", description = "Escopo exigido: activation:read.")
    @ApiResponse(responseCode = "200", description = "Solicitação encontrada")
    @ApiResponse(responseCode = "400", description = "id não é um UUID válido",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Solicitação inexistente ou de outro consumidor",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ActivationRequestResponse get(@PathVariable UUID id,
                                         @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        return ActivationRequestResponse.from(service.get(id, AuthenticatedClient.idOf(jwt)));
    }

    @GetMapping
    @Operation(summary = "Lista solicitações com paginação e filtros",
            description = "Ordenadas por createdAt (mais recentes primeiro). Escopo exigido: activation:read.")
    @ApiResponse(responseCode = "200", description = "Página de solicitações")
    @ApiResponse(responseCode = "400", description = "Parâmetro inválido (ex.: size > 100, status inexistente)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public PageResponse<ActivationRequestResponse> list(
            @Parameter(description = "Filtra pelo status") @RequestParam(required = false) ActivationStatus status,
            @Parameter(description = "Filtra pelo cliente") @RequestParam(required = false) @Size(max = 64)
            String customerId,
            @Parameter(description = "Página (começa em 0)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Itens por página (1 a 100)") @RequestParam(defaultValue = "20") @Min(1)
            @Max(100) int size,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
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
