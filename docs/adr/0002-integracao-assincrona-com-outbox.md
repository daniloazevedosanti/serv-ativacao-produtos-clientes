# ADR 0002 — Integração assíncrona com o provedor (outbox na própria tabela)

**Status:** aceita

## Contexto
O provedor externo pode ficar lento (no cenário de análise ele foi de 400 ms para 3 s), falhar ou
estourar timeout. Precisamos garantir que toda solicitação gravada seja enviada, inclusive se a
aplicação cair entre o commit e a chamada.

## Decisão
1. `POST /api/v1/requests` grava a solicitação como `PENDING` e responde `201` imediatamente.
   O tempo de resposta da API não depende do provedor.
2. A tabela `activation_request` é a própria **outbox**: `status = PENDING` + `next_attempt_at`
   significam "precisa ser enviada". Não há dual write (banco + fila).
3. Envio em dois caminhos:
   - **imediato**: evento publicado na transação e consumido `AFTER_COMMIT` em um pool limitado;
   - **rede de segurança**: agendador busca `PENDING` vencidas a cada segundo. Se o pool estiver cheio
     ou a aplicação cair, nada se perde.
4. **Sem chamada HTTP dentro de transação**: (a) transação curta PENDING → PROCESSING;
   (b) chamada HTTP; (c) transação curta com o resultado. Conexões do pool do banco nunca ficam presas
   esperando o provedor.
5. **Disputa entre instâncias** resolvida por lock otimista (`@Version`) na transição PENDING → PROCESSING.
6. **Timeout é ambíguo** (o provedor pode ter ativado): todo envio leva `Idempotency-Key = id da solicitação`,
   igual em todas as tentativas.
7. **Reconciliação**: solicitações em `PROCESSING` há mais que o lease (60 s, bem acima de connect + read
   timeout) voltam para `PENDING` (ou `FAILED`, se esgotaram as tentativas).

## Alternativas consideradas
- **Síncrono** (chamar o provedor no POST): latência da API acoplada à do provedor, threads e conexões
  presas, e a solicitação pode ficar gravada sem envio se a aplicação cair após o commit.
- **Kafka/RabbitMQ**: resolve fila e retry, mas adiciona infraestrutura e o problema do dual write
  (precisaria de outbox de qualquer forma). Para o volume do desafio, a tabela basta.
- **`SELECT ... FOR UPDATE SKIP LOCKED`**: mais eficiente com muitas instâncias; o lock otimista é
  portável e suficiente aqui. Fica como evolução.

## Consequências
- O consumidor acompanha o resultado por `GET /api/v1/requests/{id}` (`status`, `attempts`, `failureReason`).
  Erros de integração não viram 5xx na API: viram estado da solicitação.
- O agendador processa os lotes de forma sequencial; para mais vazão, aumentar instâncias ou o paralelismo.
