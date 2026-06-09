$ErrorActionPreference = 'Stop'

[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
chcp 65001 > $null

if (-not $env:JAVA_HOME -and (Test-Path 'C:\Program Files\Java\jdk-20')) {
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-20'
}

$app = Join-Path $PSScriptRoot 'build\app\AIChatApp\bin\AIChatApp.bat'

if (-not (Test-Path $app)) {
    Write-Host 'Приложение ещё не собрано.'
    Write-Host 'Выполните команду:'
    Write-Host '  .\gradlew.bat installDist'
    exit 1
}

& $app
