$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$InstallDir = Join-Path $RootDir ".jdk"
$JavaExe = Join-Path $InstallDir "bin\java.exe"
Set-Location $RootDir

function Test-ExpectedJbr {
    if (-not (Test-Path $JavaExe)) { return $false }
    $Details = (& $JavaExe -XshowSettings:properties -version 2>&1 | Out-String)
    return $Details -match "java\.vendor\s*=\s*JetBrains" -and
        $Details -match "java\.specification\.version\s*=\s*25"
}

if (-not (Test-ExpectedJbr)) {
    switch ($env:PROCESSOR_ARCHITECTURE) {
        "AMD64" { $PackageId = "333e7787aecb0bba6f5c5a9bab21ce90" }
        "ARM64" { $PackageId = "38d4fc46de6f02af9912aa3f66104600" }
        default { Write-Error "Unsupported Windows architecture: $env:PROCESSOR_ARCHITECTURE" }
    }

    $TempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("kmp-workshop-jbr-" + [guid]::NewGuid())
    $Archive = Join-Path $TempDir "jbr.zip"
    New-Item -ItemType Directory -Path $TempDir | Out-Null
    try {
        Write-Host "Downloading JetBrains Runtime 25..."
        Invoke-WebRequest -Uri "https://api.foojay.io/disco/v3.0/ids/$PackageId/redirect" -OutFile $Archive
        Expand-Archive -Path $Archive -DestinationPath $TempDir
        $DownloadedJava = Get-ChildItem -Path $TempDir -Filter java.exe -Recurse |
            Where-Object { $_.FullName -match "[\\/]bin[\\/]java\.exe$" } |
            Select-Object -First 1
        if (-not $DownloadedJava) { Write-Error "The downloaded archive did not contain a JDK." }
        $DownloadedHome = $DownloadedJava.Directory.Parent.FullName
        if (Test-Path $InstallDir) { Remove-Item -Recurse -Force $InstallDir }
        New-Item -ItemType Directory -Path $InstallDir | Out-Null
        Copy-Item -Path (Join-Path $DownloadedHome "*") -Destination $InstallDir -Recurse
    } finally {
        if (Test-Path $TempDir) { Remove-Item -Recurse -Force $TempDir }
    }
}

Write-Host "Verifying Java and Gradle..."
& $JavaExe -version
.\gradlew.bat --version
.\gradlew.bat :client:classes :adminClient:classes

Write-Host "Setup complete. The Gradle wrapper automatically uses $InstallDir."
Write-Host "Run Gradle normally, for example:"
Write-Host "  .\gradlew.bat :client:test"
