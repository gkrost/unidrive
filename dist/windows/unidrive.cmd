@echo off
REM UD-718: version-agnostic launcher for unidrive on Windows.
REM
REM Drop this file into %LOCALAPPDATA%\unidrive\ alongside the deployed
REM unidrive-<version>.jar. The glob below picks the newest matching jar
REM (by mtime) so the next version bump doesn't re-break the launcher,
REM and explicitly skips the unidrive-mcp-*.jar sibling.
REM
REM Pre-fix this file hard-coded unidrive-0.0.0-greenfield.jar, which
REM had been stale since the 0.0.1 cutover. See UD-718 in
REM docs/backlog/CLOSED.md.

setlocal enableextensions
set "JAR="
for /f "delims=" %%i in ('dir /b /od "%~dp0unidrive-*.jar" 2^>nul ^| findstr /v "unidrive-mcp-" ^| findstr /v "snapshot"') do set "JAR=%%i"

if "%JAR%"=="" (
    echo Error: no unidrive-*.jar found in %~dp0 1>&2
    echo Build and deploy first: ^(cd core ^&^& .\gradlew :app:cli:deploy^) 1>&2
    exit /b 1
)

java -Xmx6g -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -jar "%~dp0%JAR%" %*
exit /b %ERRORLEVEL%
