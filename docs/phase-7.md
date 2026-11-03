# Phase 7 — Windows packaging and automated releases

## 1. Goal
Build a runnable JAR and a Windows x64 installer with a bundled Java 21 runtime. Verify the packaged launcher and prepare automated builds and tag-triggered releases on GitHub.

## 2. Files
- C:\Users\navee\OneDrive\Desktop\opensorceproject\pom.xml
- C:\Users\navee\OneDrive\Desktop\opensorceproject\.gitignore
- C:\Users\navee\OneDrive\Desktop\opensorceproject\scripts\package-windows.ps1
- C:\Users\navee\OneDrive\Desktop\opensorceproject\scripts\smoke-windows.ps1
- C:\Users\navee\OneDrive\Desktop\opensorceproject\.github\workflows\build.yml
- C:\Users\navee\OneDrive\Desktop\opensorceproject\.github\workflows\release.yml

Generated outputs are in dist/1.0.0. SmartFileOrganizer-1.0.0-windows-x64.exe is the installer. SmartFileOrganizer-1.0.0.jar is the runnable JAR. SHA256SUMS.txt contains SHA-256 checksums.

## 3. Complete code

### pom.xml

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.smartfileorganizer</groupId>
  <artifactId>smart-file-organizer</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <name>Smart File Organizer</name>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency><groupId>com.formdev</groupId><artifactId>flatlaf</artifactId><version>3.6</version></dependency>
    <dependency><groupId>com.google.code.gson</groupId><artifactId>gson</artifactId><version>2.13.1</version></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.13.4</version><scope>test</scope></dependency>
  </dependencies>
  <build>
    <finalName>smart-file-organizer</finalName>
    <plugins>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version></plugin>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.3</version></plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.6.1</version>
        <executions><execution><phase>package</phase><goals><goal>shade</goal></goals><configuration>
          <createDependencyReducedPom>false</createDependencyReducedPom>
          <filters>
            <filter>
              <artifact>*:*</artifact>
              <excludes>
                <exclude>META-INF/*.SF</exclude>
                <exclude>META-INF/*.RSA</exclude>
                <exclude>META-INF/*.DSA</exclude>
                <exclude>module-info.class</exclude>
                <exclude>META-INF/versions/**/module-info.class</exclude>
              </excludes>
            </filter>
          </filters>
          <transformers>
            <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
            <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
              <mainClass>com.smartfileorganizer.Main</mainClass>
              <manifestEntries><Multi-Release>true</Multi-Release></manifestEntries>
            </transformer>
          </transformers>
        </configuration></execution></executions>
      </plugin>
    </plugins>
  </build>
</project>
```

### .gitignore

```text
/target/
/.build-tools/
/dist/
*.log
.idea/
*.iml
.vscode/
.DS_Store
Thumbs.db
```

### scripts/package-windows.ps1

```powershell
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
```

### scripts/smoke-windows.ps1

```powershell
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
```

### .github/workflows/build.yml

```yaml
name: Build and test
on:
  push:
    branches: [main, master]
  pull_request:
permissions:
  contents: read
jobs:
  verify:
    runs-on: windows-2022
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '21'
          architecture: x64
          cache: maven
      - name: Verify
        run: mvn --batch-mode --no-transfer-progress clean verify
      - name: Upload runnable JAR
        uses: actions/upload-artifact@v7
        with:
          name: runnable-jar
          path: target/smart-file-organizer.jar
          if-no-files-found: error
      - name: Upload test reports
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: test-reports
          path: target/surefire-reports/
          if-no-files-found: warn
```

### .github/workflows/release.yml

```yaml
name: Windows release
on:
  push:
    tags: ['v*']
  workflow_dispatch:
permissions:
  contents: read
concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: false
jobs:
  package:
    runs-on: windows-2022
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '21'
          architecture: x64
          cache: maven
      - name: Validate release tag
        shell: pwsh
        env:
          REF_TYPE: ${{ github.ref_type }}
          REF_NAME: ${{ github.ref_name }}
        run: |
          [xml]$pom = Get-Content pom.xml -Raw
          $version = ([string]$pom.project.version) -replace '-SNAPSHOT$', ''
          if ($env:REF_TYPE -eq 'tag' -and $env:REF_NAME -cne "v$version") {
            throw 'Tag must match the POM version, for example v1.0.0.'
          }
      - name: Build tested Windows installer
        shell: pwsh
        run: ./scripts/package-windows.ps1
      - name: Upload release files
        uses: actions/upload-artifact@v7
        with:
          name: windows-release
          path: dist/*/*
          if-no-files-found: error
      - name: Upload test reports
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: release-test-reports
          path: target/surefire-reports/
          if-no-files-found: warn
  publish:
    if: startsWith(github.ref, 'refs/tags/')
    needs: package
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/download-artifact@v8
        with:
          name: windows-release
          path: release-files
      - name: Publish GitHub release
        shell: pwsh
        env:
          GH_TOKEN: ${{ github.token }}
          GH_REPO: ${{ github.repository }}
          RELEASE_TAG: ${{ github.ref_name }}
        run: |
          $ErrorActionPreference = 'Stop'
          $files = @(Get-ChildItem release-files -File -Recurse | ForEach-Object FullName)
          if ($files.Count -ne 3) { throw 'Expected installer, runnable JAR, and checksums.' }
          gh release create $env:RELEASE_TAG @files --verify-tag --title "Smart File Organizer $env:RELEASE_TAG" --generate-notes
          if ($LASTEXITCODE -ne 0) { throw 'Release publication failed.' }
```

## 4. How to run and verify
Run the built JAR with the portable JDK 21 already downloaded in this workspace:
```powershell
& ".\.build-tools\jdk21\jdk-21.0.13+9\bin\java.exe" -jar .\dist\1.0.0\SmartFileOrganizer-1.0.0.jar
```
For a normal installation, open dist/1.0.0/SmartFileOrganizer-1.0.0-windows-x64.exe. The installer includes Java and installs for the current user. Install/uninstall behavior has not been tested here. The installer is unsigned.

To build a fresh release on Windows with Java 21, Maven, and WiX 3.x configured:
```powershell
.\scripts\package-windows.ps1
```
For this workspace, use its portable tools:
```powershell
.\scripts\package-windows.ps1 -JdkHome ".\.build-tools\jdk21\jdk-21.0.13+9" -Maven ".\.build-tools\maven\apache-maven-3.9.11\bin\mvn.cmd" -WixDirectory ".\.build-tools\wix"
```
The output directory must be absent. Move the existing dist/1.0.0 folder aside before rebuilding the same version. Expected output includes Tests run: 91, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS, a packaged-launcher check success, and the release output directory.

Verified locally with Temurin JDK 21.0.13+9, Maven 3.9.11, and WiX 3.14.1. All 91 tests passed, the packaged application opened and closed using its bundled runtime, the EXE and JAR were built, checksums matched, and PowerShell/YAML syntax checks passed. No installer was installed, and no GitHub workflow or public release was run.

## 5. How it works
Maven Shade merges application classes and dependencies into a runnable JAR, preserves service registrations and multi-release metadata, and excludes stale signatures and module descriptors from dependency JARs.
The packaging script verifies Java 21 x64, WiX, and the version, then runs Maven clean verify. It stages only the runnable JAR as installer input. jpackage builds a minimal runtime containing desktop, logging, and Gson support, then creates a current-user EXE installer with Start Menu and desktop shortcuts. A stable upgrade UUID identifies future versions of this application. Keep this UUID unchanged across releases.
The launch check runs the packaged executable with isolated temporary app-data. It handles the Windows launcher process and the separate process owning the application window, requests a normal close, and checks application logs. It verifies startup rather than installation or upgrade behavior.
The POM version currently remains 1.0.0-SNAPSHOT. Packaging derives the numeric installer version 1.0.0. Future release tags must match the numeric POM version. Windows installer versions use three numeric components. The script rejects mismatches and existing output directories.
build.yml runs tests and uploads the runnable JAR for main/master pushes and pull requests. release.yml builds and uploads the installer, JAR, and checksums when manually dispatched or when a v-prefixed tag is pushed. Only a tag run publishes a GitHub Release. A separate publication job receives write permission after the build succeeds.

Once this project has been committed to a GitHub repository and Phase 8 documentation is ready, pushing a matching tag starts publication:
```powershell
git tag v1.0.0
git push origin v1.0.0
```
These commands are instructions for a future release. They have not been executed. A manual workflow dispatch builds downloadable workflow artifacts without publishing a release. Re-running publication for an existing release fails rather than overwriting assets.

Official references:
- https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html
- https://github.com/actions/runner-images/blob/main/images/windows/Windows2022-Readme.md
- https://github.com/actions/setup-java
- https://github.com/actions/upload-artifact
- https://github.com/actions/download-artifact

## 6. Common errors
- WiX missing. Install WiX 3.x or supply its binary directory through -WixDirectory. The chosen GitHub windows-2022 runner includes WiX 3.14.1.
- Wrong Java version. Supply a complete Windows x64 Java 21 JDK through -JdkHome or JAVA_HOME.
- Output already exists. Move the existing version directory aside before rebuilding. The script protects existing release files from replacement.
- Tag mismatch. Update the POM version and use the matching v-prefixed numeric tag.
- Windows publisher warning. This installer has no code-signing certificate. Signing requires a separately configured certificate.
- GitHub publication fails. Check the repository Actions policy and release-job token permissions. Workflow execution remains unverified until connected to GitHub.
- Smoke-test failure. Inspect temporary smoke logs and target packaging outputs. The check requires a Windows desktop session with GUI support.
