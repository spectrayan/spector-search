@echo off
REM ═══════════════════════════════════════════════════════════════
REM  Spector File Ingestion Script
REM  Uses spectorctl to discover and ingest files via SpectorRuntime.
REM  All configuration is read from spector.yml (or CLI overrides).
REM
REM  Usage: scripts\ingest-docs.bat [--pattern "**\*.java"] [--root path]
REM ═══════════════════════════════════════════════════════════════

set SPECTOR_HOME=%~dp0..
set JAR=%SPECTOR_HOME%\spector-dist\target\spector.jar
set CONFIG=%SPECTOR_HOME%\spector.yml

if not exist "%JAR%" (
    echo [ERROR] Fat JAR not found: %JAR%
    echo [INFO]  Run: mvn package -pl spector-dist -am -DskipTests
    exit /b 1
)

java ^
    -Xmx4g ^
    --add-modules jdk.incubator.vector ^
    --enable-native-access=ALL-UNNAMED ^
    --enable-preview ^
    -cp "%JAR%" ^
    com.spectrayan.spector.cli.SpectorCtl ^
    ingest --config "%CONFIG%" ^
    %*
