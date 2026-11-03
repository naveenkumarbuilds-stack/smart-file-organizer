[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn',
    [string]$WixDirectory = $env:WIX,
    [string]$Version = ''
)
$ErrorActionPreference = 'Stop'
$previousPath = $env:PATH
$project = Split-Path -Parent $PSScriptRoot
if ($env:OS -ne 'Windows_NT') { throw 'Build the Windows installer on Windows.' }
if (!$JdkHome) { throw 'Set JAVA_HOME to a Java 21 JDK or supply -JdkHome.' }
$jpackage = Join-Path $JdkHome 'bin/jpackage.exe'
if (!(Test-Path -LiteralPath $jpackage)) { throw 'A full JDK 21 including jpackage is required.' }
$release = Get-Content -LiteralPath (Join-Path $JdkHome 'release') -Raw
if ($release -notmatch 'JAVA_VERSION="21[."]') { throw 'Use JDK 21 for reproducible runtime packaging.' }
if ($release -notmatch 'OS_ARCH="(x86_64|amd64)"') { throw 'Use a Windows x64 JDK 21.' }
if ($WixDirectory) {
    $wixBin = if (Test-Path -LiteralPath (Join-Path $WixDirectory 'candle.exe')) {
        $WixDirectory
    } else { Join-Path $WixDirectory 'bin' }
}
foreach ($tool in 'candle.exe', 'light.exe') {
    $available = if ($WixDirectory) { Test-Path -LiteralPath (Join-Path $wixBin $tool) }
        else { [bool](Get-Command $tool -ErrorAction SilentlyContinue) }
    if (!$available) {
        throw 'WiX 3.x is required. Supply -WixDirectory with its binary directory.'
    }
}
[xml]$pom = Get-Content -LiteralPath (Join-Path $project 'pom.xml') -Raw
$projectVersion = [string]$pom.project.version
$numericVersion = $projectVersion -replace '-SNAPSHOT$', ''
if (!$Version) { $Version = $numericVersion }
if ($Version -cnotmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' -or
        $Version -ne $numericVersion) {
    throw 'The version must match the POM numeric version, such as 1.0.0.'
}
$parts = $Version.Split('.')
if ([long]$parts[0] -gt 255 -or [long]$parts[1] -gt 255 -or [long]$parts[2] -gt 65535) {
    throw 'Windows requires major/minor at most 255 and patch at most 65535.'
}
$output = Join-Path $project "dist/$Version"
if (Test-Path -LiteralPath $output) { throw "Output already exists. Choose a new version or move $output aside." }

# Scope Java selection to this build process and restore it afterwards.
$previousJava = $env:JAVA_HOME
if ($WixDirectory) { $env:PATH = $wixBin + [IO.Path]::PathSeparator + $env:PATH }
$env:JAVA_HOME = $JdkHome
$env:PATH = (Join-Path $JdkHome 'bin') + [IO.Path]::PathSeparator + $env:PATH
Push-Location $project
try {
    & $Maven --batch-mode --no-transfer-progress clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }

    $stage = Join-Path $project ('target/package-' + [guid]::NewGuid().ToString('N'))
    $inputDirectory = Join-Path $stage 'input'
    $imageDirectory = Join-Path $stage 'image'
    New-Item -ItemType Directory -Path $inputDirectory, $imageDirectory, $output | Out-Null
    Copy-Item -LiteralPath 'target/smart-file-organizer.jar' -Destination $inputDirectory
    $imageOptions = @(
        '--type', 'app-image', '--name', 'SmartFileOrganizer',
        '--app-version', $Version, '--vendor', 'Smart File Organizer contributors',
        '--description', 'Preview, organize and undo file moves offline.',
        '--input', $inputDirectory, '--main-jar', 'smart-file-organizer.jar',
        '--main-class', 'com.smartfileorganizer.Main',
        '--add-modules', 'java.desktop,java.logging,jdk.unsupported',
        '--dest', $imageDirectory
    )
    & $jpackage @imageOptions
    if ($LASTEXITCODE -ne 0) { throw 'Application image packaging failed.' }
    $image = Join-Path $imageDirectory 'SmartFileOrganizer'
    & (Join-Path $PSScriptRoot 'smoke-windows.ps1') -Launcher (Join-Path $image 'SmartFileOrganizer.exe')
    $installerOptions = @(
        '--type', 'exe', '--name', 'SmartFileOrganizer', '--app-version', $Version,
        '--app-image', $image, '--dest', $output,
        '--win-per-user-install', '--win-menu', '--win-menu-group', 'Smart File Organizer',
        '--win-shortcut', '--win-dir-chooser',
        '--win-upgrade-uuid', 'e42050d8-f0a2-4a1a-90e4-4bb5ced065a7'
    )
    & $jpackage @installerOptions
    if ($LASTEXITCODE -ne 0) { throw 'Windows installer packaging failed.' }
    Move-Item -LiteralPath (Join-Path $output "SmartFileOrganizer-$Version.exe") -Destination (
        Join-Path $output "SmartFileOrganizer-$Version-windows-x64.exe")
    Copy-Item -LiteralPath 'target/smart-file-organizer.jar' -Destination (
        Join-Path $output "SmartFileOrganizer-$Version.jar")
    $checksums = Get-ChildItem -LiteralPath $output -File |
        Sort-Object Name | ForEach-Object {
            (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_.Name
        }
    $checksums | Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS.txt') -Encoding ascii
    Write-Output "Release files are ready in $output"
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJava
    $env:PATH = $previousPath
}
