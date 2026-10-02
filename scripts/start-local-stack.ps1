param([switch]$WithTunnel)
$ErrorActionPreference = 'Stop'
$repositoryDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeDirectory = Join-Path $repositoryDirectory '.local'
$runtimeFile = Join-Path $runtimeDirectory 'runtime.json'
if (-not (Test-Path -LiteralPath $runtimeFile)) { throw 'Local runtime is not configured. Follow README.md for native PostgreSQL setup.' }
$runtime = Get-Content -Raw -LiteralPath $runtimeFile | ConvertFrom-Json
$postgresControl = Join-Path $runtime.postgresBin 'pg_ctl.exe'
& $postgresControl -D $runtime.dataDirectory status *> $null
if ($LASTEXITCODE -ne 0) {
    & $postgresControl -D $runtime.dataDirectory -l (Join-Path $runtimeDirectory 'postgres.log') -w start
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL could not start.' }
}
$environmentFile = Join-Path $repositoryDirectory 'backend\.env'
foreach ($line in Get-Content -LiteralPath $environmentFile) {
    if ($line.Trim() -eq '' -or $line.Trim().StartsWith('#')) { continue }
    $pair = $line.Split('=', 2)
    if ($pair.Count -ne 2 -or $pair[0] -notmatch '^(DB_URL|DB_USER|DB_PASSWORD|PORT|SERVER_ADDRESS|CORS_ORIGINS)$') { throw 'Invalid backend .env setting.' }
    [Environment]::SetEnvironmentVariable($pair[0], $pair[1], 'Process')
}
$backendPort = if ($env:PORT) { [int]$env:PORT } else { 8080 }
$listener = Get-NetTCPConnection -LocalPort $backendPort -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    $running = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $listener[0].OwningProcess)
    if ($running.CommandLine -notlike '*moneymate-1.0.0.jar*') { throw 'The backend port is occupied by another application.' }
    Write-Output 'MoneyMate backend is already running.'
} else {
    $jar = Join-Path $repositoryDirectory 'backend\target\moneymate-1.0.0.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend jar first: backend\mvnw.cmd package' }
    $process = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-jar',('"'+$jar+'"')) -WorkingDirectory (Join-Path $repositoryDirectory 'backend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeDirectory 'backend.log') -RedirectStandardError (Join-Path $runtimeDirectory 'backend-error.log') -PassThru
    $process.Id | Set-Content -LiteralPath (Join-Path $runtimeDirectory 'backend.pid')
    Write-Output ('MoneyMate backend started in the background. PID: ' + $process.Id)
}
if ($WithTunnel) {
    $existingTunnel = $null
    try { $existingTunnel = Invoke-RestMethod 'http://127.0.0.1:4040/api/tunnels' -TimeoutSec 2 } catch {}
    if (-not $existingTunnel) {
        $tunnel = Start-Process -FilePath (Get-Command ngrok.exe).Source -ArgumentList @('http',"$backendPort",'--inspect=false','--log=stdout','--log-format=json') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeDirectory 'ngrok.log') -RedirectStandardError (Join-Path $runtimeDirectory 'ngrok-error.log') -PassThru
        $tunnel.Id | Set-Content -LiteralPath (Join-Path $runtimeDirectory 'ngrok.pid')
        Write-Output ('ngrok started in the background. PID: ' + $tunnel.Id)
    } else { Write-Output 'An ngrok agent is already running; check its endpoint before using it.' }
}
Write-Output ('Health endpoint: http://localhost:' + $backendPort + '/api/health')
Write-Output ('Local logs and process IDs: ' + $runtimeDirectory)
