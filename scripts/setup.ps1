$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$InstallDir = Join-Path $RootDir ".jdk"
$JavaExe = Join-Path $InstallDir "bin\java.exe"
Set-Location $RootDir

function Test-ExpectedJbr {
    param([string]$CandidateJava = $JavaExe)

    if (-not (Test-Path $CandidateJava)) { return $false }
    $Details = (& $CandidateJava -XshowSettings:properties -version 2>&1 | Out-String)
    return $Details -match "java\.vendor\s*=\s*JetBrains" -and
        $Details -match "java\.specification\.version\s*=\s*25"
}

if (-not (Test-ExpectedJbr)) {
    $JbrVersion = "25.0.4"
    $JbrBuild = "329.128"
    $DownloadBase = "https://cache-redirector.jetbrains.com/intellij-jbr"

    switch ($env:PROCESSOR_ARCHITECTURE) {
        "AMD64" {
            $ArchiveName = "jbrsdk-${JbrVersion}-windows-x64-b${JbrBuild}.zip"
            $ExpectedSha512 = "3a857e27fdf3381036366022f6605592ba61bcc508adbbf7adbf64c4988e8c586309c5bcec3cb40c5bbe015bdc644a6645937f6e7935bed44174096345d041a7"
            $ApproximateSize = "296 MB"
        }
        "ARM64" {
            $ArchiveName = "jbrsdk-${JbrVersion}-windows-aarch64-b${JbrBuild}.zip"
            $ExpectedSha512 = "a5949ca393d7a3f0346aa16ee49960ba1e69084709b89981d6f3bd3569b53405e1d5790e2cf3a6d02cc5765b137b0f9df26d806db92e640527d8e984dc096807"
            $ApproximateSize = "272 MB"
        }
        default { Write-Error "Unsupported Windows architecture: $env:PROCESSOR_ARCHITECTURE" }
    }

    $DownloadUri = "$DownloadBase/$ArchiveName"
    $TempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("kmp-workshop-jbr-" + [guid]::NewGuid())
    $Archive = Join-Path $TempDir $ArchiveName
    $StagingDir = "$InstallDir.new-$([guid]::NewGuid())"
    $BackupDir = "$InstallDir.old-$([guid]::NewGuid())"
    New-Item -ItemType Directory -Path $TempDir | Out-Null

    try {
        Write-Host "Downloading JetBrains Runtime SDK $JbrVersion ($ApproximateSize)..."
        Write-Host "Artifact: $ArchiveName"
        & curl.exe --fail --location --retry 3 --output $Archive $DownloadUri
        if ($LASTEXITCODE -ne 0) {
            throw "JBR download failed with exit code $LASTEXITCODE."
        }

        Write-Host "Verifying archive checksum..."
        $ActualSha512 = (Get-FileHash -Path $Archive -Algorithm SHA512).Hash.ToLowerInvariant()
        if ($ActualSha512 -ne $ExpectedSha512) {
            throw "JBR checksum mismatch. Expected $ExpectedSha512, got $ActualSha512."
        }

        Write-Host "Extracting JBR..."
        $ExtractDir = Join-Path $TempDir "extracted"
        Expand-Archive -Path $Archive -DestinationPath $ExtractDir
        $DownloadedJava = Get-ChildItem -Path $ExtractDir -Filter java.exe -Recurse |
            Where-Object { $_.FullName -match "[\\/]bin[\\/]java\.exe$" } |
            Select-Object -First 1
        if (-not $DownloadedJava) { throw "The downloaded archive did not contain a JDK." }

        $DownloadedHome = $DownloadedJava.Directory.Parent.FullName
        Write-Host "Staging JBR installation..."
        New-Item -ItemType Directory -Path $StagingDir | Out-Null
        Copy-Item -Path (Join-Path $DownloadedHome "*") -Destination $StagingDir -Recurse

        $StagedJava = Join-Path $StagingDir "bin\java.exe"
        if (-not (Test-ExpectedJbr -CandidateJava $StagedJava)) {
            throw "The staged JBR failed version/vendor validation."
        }

        Write-Host "Installing JBR into $InstallDir..."
        if (Test-Path $InstallDir) { Move-Item -Path $InstallDir -Destination $BackupDir }
        try {
            Move-Item -Path $StagingDir -Destination $InstallDir
        } catch {
            if (Test-Path $BackupDir) { Move-Item -Path $BackupDir -Destination $InstallDir }
            throw
        }
        if (Test-Path $BackupDir) { Remove-Item -Recurse -Force $BackupDir }
    } finally {
        if (Test-Path $StagingDir) { Remove-Item -Recurse -Force $StagingDir }
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
