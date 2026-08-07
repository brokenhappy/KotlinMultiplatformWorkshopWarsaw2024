$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $RootDir

if (-not (Get-Command mise -ErrorAction SilentlyContinue)) {
    Write-Error "mise is required to provision Java 23. Install it from https://mise.jdx.dev/getting-started.html, then run this script again."
}

Write-Host "Installing the project Java toolchain..."
mise install

Write-Host "Verifying Java and Gradle..."
mise exec -- java -version
mise exec -- .\gradlew.bat --version
mise exec -- .\gradlew.bat :client:test

Write-Host "Setup complete. Run Gradle commands through mise, for example:"
Write-Host "  mise exec -- .\gradlew.bat :client:test"
