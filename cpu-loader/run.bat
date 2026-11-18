@echo off
rem Run cpu-loader, building it first when needed.
setlocal
cd /d "%~dp0"

if not exist "build\cpu-loader.jar" (
    echo build\cpu-loader.jar not found, building ...
    call build.bat
    if errorlevel 1 exit /b 1
)

java -jar "build\cpu-loader.jar" %*
exit /b %ERRORLEVEL%
