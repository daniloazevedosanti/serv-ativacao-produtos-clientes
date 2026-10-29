# Usado por mvnw.cmd: garante a distribuição do Maven e imprime o diretório dela.
$ErrorActionPreference = 'Stop'
$baseDir = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$props = Join-Path $baseDir '.mvn\wrapper\maven-wrapper.properties'

$url = $env:MVNW_DISTRIBUTION_URL
if (-not $url) {
    $line = Get-Content $props | Where-Object { $_ -match '^distributionUrlWindows=' } | Select-Object -First 1
    $url = $line -replace '^distributionUrlWindows=', ''
}
$fileName = [IO.Path]::GetFileName($url)
$distName = $fileName -replace '-bin\.zip$', '' -replace '-bin\.tar\.gz$', ''
$sha = [Security.Cryptography.SHA256]::Create()
$urlId = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($url))) -replace '-', '').Substring(0, 16)
$userHome = if ($env:MAVEN_USER_HOME) { $env:MAVEN_USER_HOME } else { Join-Path $env:USERPROFILE '.m2' }
$mavenHome = Join-Path $userHome "wrapper\dists\$distName\$urlId"

if (-not (Test-Path (Join-Path $mavenHome 'bin\mvn.cmd'))) {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        [Console]::Error.WriteLine("mvnw: baixando $url")
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $url -OutFile (Join-Path $tmp $fileName) -UseBasicParsing
        Expand-Archive -Path (Join-Path $tmp $fileName) -DestinationPath $tmp -Force
        New-Item -ItemType Directory -Path (Split-Path -Parent $mavenHome) -Force | Out-Null
        if (-not (Test-Path $mavenHome)) {
            Move-Item -Path (Join-Path $tmp $distName) -Destination $mavenHome
        }
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}
Write-Output $mavenHome
