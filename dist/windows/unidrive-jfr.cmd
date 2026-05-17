@echo off
REM UD-718: version-agnostic JFR launcher for unidrive on Windows.
REM
REM Drop this file into %LOCALAPPDATA%\unidrive\ alongside the deployed
REM unidrive-<version>.jar. The glob below picks the newest matching jar
REM (by mtime) so the next version bump doesn't re-break the launcher,
REM and explicitly skips the unidrive-mcp-*.jar sibling.
REM
REM Pre-fix this file hard-coded unidrive-0.0.0-greenfield.jar, which
REM had been stale since the 0.0.1 cutover. See UD-718 in
REM docs/backlog/CLOSED.md.
REM
REM For richer JFR profiling (settings=profile, sha-tagged filenames,
REM HeapDumpOnOOM) prefer scripts/dev/unidrive-jfr.ps1 -- this .cmd is
REM the minimal "just record a flight" entry point for cmd.exe users.

setlocal enableextensions
set "JAR="
for /f "delims=" %%i in ('dir /b /od "%~dp0unidrive-*.jar" 2^>nul ^| findstr /v "unidrive-mcp-" ^| findstr /v "snapshot"') do set "JAR=%%i"

if "%JAR%"=="" (
    echo Error: no unidrive-*.jar found in %~dp0 1>&2
    echo Build and deploy first: ^(cd core ^&^& .\gradlew :app:cli:deploy^) 1>&2
    exit /b 1
)

REM One-line java invocation: `^` line-continuation inside a .cmd
REM body works on the command line but conhost's batch-file parser is
REM finicky when `setlocal` + `for /f` precede it, splitting on `-Xmx6g`
REM and emitting "Der Befehl 'M' ist entweder falsch geschrieben...".
REM Keep the whole java line flat -- it's ugly but reliable.
java -Xmx6g -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -XX:StartFlightRecording=filename=%~dp0recording_%%t.jfr,dumponexit=true -jar "%~dp0%JAR%" %*
exit /b %ERRORLEVEL%
