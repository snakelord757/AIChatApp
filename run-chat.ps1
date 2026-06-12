$ErrorActionPreference = 'Stop'

[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
& "$env:SystemRoot\System32\chcp.com" 65001 > $null

if (-not $env:JAVA_HOME -and (Test-Path 'C:\Program Files\Java\jdk-20')) {
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-20'
}

$app = Join-Path $PSScriptRoot 'build\app\aichat\bin\aichat.bat'

if (-not (Test-Path $app)) {
    Write-Host 'Application is not built yet.'
    Write-Host 'Run:'
    Write-Host '  .\gradlew.bat installDist'
    exit 1
}

& $app
