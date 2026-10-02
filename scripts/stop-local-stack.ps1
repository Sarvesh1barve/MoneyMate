param([switch]$StopDatabase, [switch]$StopTunnel)
$ErrorActionPreference = 'Stop'
$repositoryDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeDirectory = Join-Path $repositoryDirectory '.local'
function Stop-RecordedProcess([string]$name, [string]$expected) {
    $file = Join-Path $runtimeDirectory ($name + '.pid')
    if (-not (Test-Path -LiteralPath $file)) { return }
    $processId = [int](Get-Content -LiteralPath $file)
    $process = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $processId) -ErrorAction SilentlyContinue
    if ($process -and $process.CommandLine -like $expected) {
        Stop-Process -Id $processId
        Write-Output ($name + ' stopped.')
    } elseif ($process) { throw ('Refusing to stop an unrelated process recorded in ' + $file) }
}
Stop-RecordedProcess 'backend' '*moneymate-1.0.0.jar*'
if ($StopTunnel) { Stop-RecordedProcess 'ngrok' '*ngrok*http*8080*' }
if ($StopDatabase) {
    $runtime = Get-Content -Raw -LiteralPath (Join-Path $runtimeDirectory 'runtime.json') | ConvertFrom-Json
    & (Join-Path $runtime.postgresBin 'pg_ctl.exe') -D $runtime.dataDirectory -m fast -w stop
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL did not stop cleanly.' }
}
