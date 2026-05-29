@echo off
REM ═══════════════════════════════════════════════════════════════
REM  Spector MCP Server — Start Script
REM  Starts the MCP server. Configuration is read from spector.yml.
REM  CLI args can override any setting.
REM ═══════════════════════════════════════════════════════════════

set SPECTOR_HOME=%~dp0..
set JAR=%SPECTOR_HOME%\spector-dist\target\spector.jar
set CONFIG=%SPECTOR_HOME%\spector-local.yml

if not exist "%JAR%" (
    echo [ERROR] Fat JAR not found: %JAR%
    echo [INFO]  Run: mvn package -pl spector-dist -am -DskipTests
    exit /b 1
)

echo [Spector MCP] Starting... 1>&2
echo [Spector MCP] JAR: %JAR% 1>&2
echo [Spector MCP] Config: %CONFIG% 1>&2

java ^
    --add-modules jdk.incubator.vector ^
    --enable-native-access=ALL-UNNAMED ^
    --enable-preview ^
    -jar "%JAR%" ^
    --config "%CONFIG%" ^
    %*
