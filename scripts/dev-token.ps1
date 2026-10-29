# Gera um access token HS256 para DESENVOLVIMENTO (docker compose / perfil local).
# Uso: .\scripts\dev-token.ps1 [-ClientId crm-system] [-Scope "activation:read activation:write"]
param(
    [string]$ClientId = "crm-system",
    [string]$Scope = "activation:read activation:write"
)

$secret   = if ($env:JWT_HMAC_SECRET) { $env:JWT_HMAC_SECRET } else { "dev-only-secret-change-me-0123456789abcdef" }
$issuer   = if ($env:JWT_ISSUER) { $env:JWT_ISSUER } else { "http://localhost/dev-issuer" }
$audience = if ($env:JWT_AUDIENCE) { $env:JWT_AUDIENCE } else { "activation-api" }
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()

function ConvertTo-Base64Url([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$header  = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
$payloadJson = @{
    iss = $issuer; sub = $ClientId; client_id = $ClientId; aud = $audience
    scope = $Scope; iat = $now; exp = $now + 3600
} | ConvertTo-Json -Compress
$payload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($payloadJson))

$hmac = New-Object System.Security.Cryptography.HMACSHA256
$hmac.Key = [Text.Encoding]::UTF8.GetBytes($secret)
$signature = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$header.$payload")))

Write-Output "$header.$payload.$signature"
