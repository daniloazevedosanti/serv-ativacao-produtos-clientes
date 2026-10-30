#!/usr/bin/env bash
# Gera um access token HS256 para DESENVOLVIMENTO (docker compose / perfil local).
# Uso: ./scripts/dev-token.sh [client_id] [escopos]
#   ./scripts/dev-token.sh crm-system "activation:read activation:write"
set -euo pipefail

CLIENT_ID="${1:-crm-system}"
SCOPE="${2:-activation:read activation:write}"
SECRET="${JWT_HMAC_SECRET:-dev-only-secret-change-me-0123456789abcdef}"
ISSUER="${JWT_ISSUER:-http://localhost/dev-issuer}"
AUDIENCE="${JWT_AUDIENCE:-activation-api}"
NOW=$(date +%s)
EXP=$((NOW + 3600))

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

HEADER=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
PAYLOAD=$(printf '{"iss":"%s","sub":"%s","client_id":"%s","aud":"%s","scope":"%s","iat":%d,"exp":%d}' \
  "$ISSUER" "$CLIENT_ID" "$CLIENT_ID" "$AUDIENCE" "$SCOPE" "$NOW" "$EXP" | b64url)
SIGNATURE=$(printf '%s.%s' "$HEADER" "$PAYLOAD" | openssl dgst -sha256 -hmac "$SECRET" -binary | b64url)

echo "${HEADER}.${PAYLOAD}.${SIGNATURE}"
