@echo off
rem Run storage-loader, building it first when needed.
setlocal
cd /d "%~dp0"

if not exist "build\storage-loader.jar" (
    echo build\storage-loader.jar not found, building ...
    call build.bat
    if errorlevel 1 exit /b 1
)

java -jar "build\storage-loader.jar" %*
exit /b %ERRORLEVEL%
