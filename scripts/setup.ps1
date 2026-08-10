$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $RootDir

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "Java was not found. Select or install JetBrains Runtime 25 and set JAVA_HOME."
}

$JavaDetails = (& java -XshowSettings:properties -version 2>&1 | Out-String)
if ($JavaDetails -notmatch "java\.vendor\s*=\s*JetBrains") {
    Write-Error "This project requires JetBrains Runtime 25; the active Java vendor is not JetBrains."
}
if ($JavaDetails -notmatch "java\.specification\.version\s*=\s*25") {
    Write-Error "This project requires JetBrains Runtime 25; the active Java version is not 25."
}

Write-Host "Verifying Java and Gradle..."
java -version
.\gradlew.bat --version
.\gradlew.bat :client:test

Write-Host "Setup complete. Run Gradle normally, for example:"
Write-Host "  .\gradlew.bat :client:test"
