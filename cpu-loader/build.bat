@echo off
rem Build cpu-loader with plain javac (JDK 8+). No Maven required.
setlocal enabledelayedexpansion
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
    echo [ERROR] javac not found. Install JDK 8+ and add its bin directory to PATH.
    exit /b 1
)

if exist build rmdir /s /q build
mkdir build\classes
if errorlevel 1 (
    echo [ERROR] Cannot create the build directory.
    exit /b 1
)

> build\manifest.txt echo Main-Class: com.cpuload.Main

rem javac treats "\" as an escape character inside @argfiles, so write "/" paths.
type nul > build\sources.txt
for /r "src\main\java" %%f in (*.java) do (
    set "SRC=%%f"
    set "SRC=!SRC:\=/!"
    echo "!SRC!">> build\sources.txt
)

echo Compiling for Java 8 ...
javac --release 8 -encoding UTF-8 -d build\classes @build\sources.txt 2>nul
if errorlevel 1 (
    echo This javac has no --release 8 support, retrying with -source/-target 1.8 ...
    javac -source 1.8 -target 1.8 -encoding UTF-8 -d build\classes @build\sources.txt
    if errorlevel 1 (
        echo [ERROR] Compilation failed.
        exit /b 1
    )
)

jar cfm build\cpu-loader.jar build\manifest.txt -C build\classes .
if errorlevel 1 (
    echo [ERROR] Packaging failed.
    exit /b 1
)

echo.
echo Build OK: build\cpu-loader.jar
exit /b 0
