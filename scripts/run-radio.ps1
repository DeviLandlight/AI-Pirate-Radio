param(
    [string]$CloudflaredPath = "C:\Tools\cloudflared\cloudflared.exe",
    [string]$TunnelName = "radio-skittles-api"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repoRoot "gradlew.bat"
$logDir = Join-Path $repoRoot "logs"
$tunnelOutLog = Join-Path $logDir "cloudflared.out.log"
$tunnelErrLog = Join-Path $logDir "cloudflared.err.log"

if (-not (Test-Path -LiteralPath $gradle)) {
    throw "Could not find gradlew.bat at $gradle"
}

if (-not (Test-Path -LiteralPath $CloudflaredPath)) {
    throw "Could not find cloudflared at $CloudflaredPath"
}

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

Write-Host "Starting Radio Skittles tunnel: $TunnelName"
$tunnelProcess = Start-Process `
    -FilePath $CloudflaredPath `
    -ArgumentList @("tunnel", "run", $TunnelName) `
    -WorkingDirectory $repoRoot `
    -RedirectStandardOutput $tunnelOutLog `
    -RedirectStandardError $tunnelErrLog `
    -PassThru `
    -WindowStyle Hidden

Write-Host "Tunnel started. Logs: $tunnelOutLog and $tunnelErrLog"
Write-Host "Starting Discord bot. Press Ctrl+C to stop the bot and tunnel."

try {
    Push-Location $repoRoot
    & $gradle ":bot:run"
}
finally {
    Pop-Location
    if ($tunnelProcess -and -not $tunnelProcess.HasExited) {
        Write-Host "Stopping Radio Skittles tunnel..."
        Stop-Process -Id $tunnelProcess.Id -Force
    }
}
