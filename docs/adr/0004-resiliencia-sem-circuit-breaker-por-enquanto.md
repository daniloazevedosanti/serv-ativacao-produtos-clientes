# ADR 0004 — Resiliência: backoff com jitter e bulkhead; circuit breaker adiado

**Status:** aceita

## Decisão
- Timeouts explícitos (connect 1 s, read 3 s, configuráveis).
- Backoff exponencial com jitter (2 s, 4 s, 8 s... até 5 min), no máximo 5 tentativas.
- `Retry-After` do provedor respeitado (limitado a 15 min).
- Bulkhead: pool limitado (4–8 threads) para o envio imediato; excedente fica para o agendador.

## Por que não Resilience4j agora
O objetivo do circuit breaker é parar de bater num serviço que está falhando e liberar recursos
rapidamente. No desenho assíncrono, a API já não espera o provedor e o backoff com jitter já espaça as
tentativas de cada solicitação. Um breaker acrescentaria estado por instância e configuração sem ganho
claro no volume do desafio — e o enunciado pede decisão, não quantidade de bibliotecas.

## Quando adicionar
Com volume alto (muitas solicitações novas por segundo durante uma indisponibilidade do provedor), o
breaker evita gastar tentativas de todas elas: abriria o circuito, as solicitações seriam reagendadas
sem consumir tentativa, e um "half-open" testaria a recuperação. Seria um decorator no
`HttpActivationProvider` devolvendo `TemporaryFailure("CIRCUIT_OPEN", tempoDeEspera)`.
