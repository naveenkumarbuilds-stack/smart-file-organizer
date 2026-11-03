[CmdletBinding()]
param([Parameter(Mandatory)][string]$Launcher)
$ErrorActionPreference = 'Stop'
$launcherPath = (Resolve-Path -LiteralPath $Launcher).Path
$state = Join-Path ([IO.Path]::GetTempPath()) ('organizer-smoke-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $state | Out-Null
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = $launcherPath
$start.UseShellExecute = $false
$start.EnvironmentVariables['LOCALAPPDATA'] = $state
$startedAt = [DateTime]::UtcNow
$process = [Diagnostics.Process]::Start($start)
$launched = @($process)
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 200
        # The Windows launcher can start a second copy that owns the actual JVM window.
        $launched = @(Get-Process -Name ([IO.Path]::GetFileNameWithoutExtension($launcherPath)) -ErrorAction SilentlyContinue |
            Where-Object { $_.Path -eq $launcherPath -and $_.StartTime.ToUniversalTime() -ge $startedAt })
        $window = $launched | Where-Object MainWindowTitle -eq 'Smart File Organizer' | Select-Object -First 1
        $process.Refresh()
        if ($process.HasExited -and !$launched) { throw "Packaged application exited early ($($process.ExitCode))." }
    } while (!$window -and [DateTime]::UtcNow -lt $deadline)
    if (!$window) { throw 'Packaged application window did not appear.' }
    # Allow the startup worker to finish before requesting a normal window close.
    Start-Sleep -Seconds 2
    if (!$window.CloseMainWindow() -or !$window.WaitForExit(10000)) {
        throw 'The packaged application did not close normally.'
    }
    if (!$process.WaitForExit(10000) -or $process.ExitCode -ne 0) { throw 'Application launcher did not exit successfully.' }
    $logs = Get-ChildItem -LiteralPath $state -Recurse -Filter '*.log'
    if (!$logs) { throw 'Application logging did not initialize.' }
    if ($logs | Select-String -Pattern 'SEVERE|WARNING') { throw 'Application smoke test logged an error.' }
    Write-Output 'Packaged launcher opened and closed successfully using its bundled runtime.'
} finally {
    foreach ($launchedProcess in $launched) {
        if (!$launchedProcess.HasExited) { $launchedProcess.Kill() }
        $launchedProcess.Dispose()
    }
    if (!$process.HasExited) { $process.Kill() }
    $process.Dispose()
}
