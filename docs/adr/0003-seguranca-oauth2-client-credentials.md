# ADR 0003 — Segurança: OAuth 2.0 client credentials, JWT e isolamento por consumidor

**Status:** aceita

## Decisão
- A API é um **Resource Server** (Spring Security). Os consumidores são sistemas, então o fluxo é
  **client credentials**: cada sistema tem `client_id`/segredo (ou chave privada, `private_key_jwt`)
  no servidor de autorização (Keycloak, Entra ID, Cognito, Okta...) e obtém um access token JWT.
- O serviço valida **assinatura** (chaves públicas via JWKS, com cache e rotação), **emissor** (`iss`),
  **audiência** (`aud = activation-api`, impede reaproveitar token de outra API) e **expiração** (`exp`/`nbf`).
- **Autorização por escopo**: `activation:write` cria; `activation:read` consulta/lista.
- **Isolamento (OWASP API1 — BOLA)**: cada solicitação guarda o `client_id` de quem a criou; consultas
  sempre filtram por ele. Solicitação de outro consumidor responde `404` (não `403`, para não confirmar
  que o id existe).
- **Credencial do provedor externo**: lida de variável de ambiente injetada pelo cofre de segredos
  (AWS Secrets Manager / Azure Key Vault / Vault). Rotação sem rebuild: o cofre atualiza o segredo e um
  rolling restart aplica (ou leitura dinâmica, se o provedor aceitar duas chaves durante a transição).
  Nunca é logada (`toString` mascarado).
- **Desenvolvimento**: tokens HS256 com segredo local (`scripts/dev-token.*`), recurso **bloqueado no
  perfil `prod`** (a aplicação não sobe com segredo HMAC em produção).

## Proteções adicionais implementadas
- Limite de tamanho do corpo (16 KB), inclusive para corpo em chunks sem `Content-Length`.
- Rate limit por consumidor (token bucket) com `429` + `Retry-After`. Em produção, o lugar natural é o
  API Gateway (o limite aqui é por instância).
- 401/403 no formato Problem Details, mantendo o header `WWW-Authenticate` (RFC 6750).
