$ErrorActionPreference = 'Stop'
$backendDirectory = Join-Path $PSScriptRoot '..\backend'
$environmentFile = Join-Path $backendDirectory '.env'
if (Test-Path -LiteralPath $environmentFile) {
    foreach ($line in Get-Content -LiteralPath $environmentFile) {
        if ($line.Trim() -eq '' -or $line.Trim().StartsWith('#')) { continue }
        $pair = $line.Split('=', 2)
        if ($pair.Count -ne 2 -or $pair[0] -notmatch '^(DB_URL|DB_USER|DB_PASSWORD|PORT|SERVER_ADDRESS|CORS_ORIGINS)$') { throw 'Invalid backend .env setting.' }
        [Environment]::SetEnvironmentVariable($pair[0], $pair[1], 'Process')
    }
}
if (-not $env:DB_PASSWORD -or $env:DB_PASSWORD.StartsWith('replace_with_')) { throw 'Set a unique DB_PASSWORD in backend/.env or the process environment.' }
Push-Location $backendDirectory
try { & .\mvnw.cmd spring-boot:run; if ($LASTEXITCODE -ne 0) { throw 'Backend exited with an error.' } }
finally { Pop-Location }
