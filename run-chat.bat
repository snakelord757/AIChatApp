@echo off
"C:\Windows\System32\chcp.com" 65001 1>NUL 2>NUL
"C:\Windows\System32\cmd.exe" /c cls
set "AICHAT_ANSI=true"

if "%JAVA_HOME%"=="" (
    if exist "C:\Program Files\Java\jdk-20" (
        set "JAVA_HOME=C:\Program Files\Java\jdk-20"
    )
)

set "APP=%~dp0build\app\aichat\bin\aichat.bat"

if not exist "%APP%" (
    echo Application is not built yet.
    echo Run:
    echo   .\gradlew.bat installDist
    exit /b 1
)

call "%APP%"
