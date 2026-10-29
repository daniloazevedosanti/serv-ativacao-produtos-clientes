# ADR 0001 — Duplicidade garantida por constraint única no banco

**Status:** aceita

## Contexto
O `externalId` identifica a solicitação no sistema de origem, que faz retry. Duas requisições
iguais podem chegar quase ao mesmo tempo, em instâncias diferentes da aplicação.

## Decisão
- `UNIQUE (external_id)` no PostgreSQL é a **única** garantia de unicidade.
- A consulta prévia por `externalId` é apenas um atalho para o caso comum (retry após a criação).
- Se o `INSERT` violar a constraint (corrida), a aplicação captura `DataIntegrityViolationException`,
  relê a solicitação vencedora **em nova transação** (a anterior foi abortada pelo PostgreSQL) e decide:
  - mesmo conteúdo (`customerId`, `productCode` e mesmo consumidor) → `200 OK` com o registro existente
    e header `Idempotent-Replayed: true`;
  - conteúdo diferente ou outro consumidor → `409 Conflict`, sem revelar dados do registro existente.

## Por que não "consultar e depois inserir"
Entre o `SELECT` e o `INSERT` existe uma janela: duas requisições podem ver "não existe" e ambas
inserirem. Lock pessimista (`SELECT ... FOR UPDATE`) não ajuda, porque não há linha para travar.
`synchronized`/lock em memória não funciona com várias instâncias.

## Alternativas consideradas
- `INSERT ... ON CONFLICT DO NOTHING RETURNING`: elegante, mas específico do PostgreSQL e exige SQL nativo;
  a constraint + captura da exceção resolve com JPA puro e é igualmente correta.
- Unicidade por `(client_id, external_id)`: permitiria dois sistemas usarem o mesmo id. O enunciado
  pede unicidade do `externalId`; mantive global e documentei a troca caso o negócio peça.

## Consequências
Testado com 20 requisições simultâneas contra PostgreSQL real (`ConcurrentDuplicateIT`).
