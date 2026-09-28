@echo off
rem ---------------------------------------------------------------------------
rem  Minecraft MCP - starts Minecraft 1.21.11 with the mod and its MCP server up.
rem
rem  Double click this file. The first start downloads the game, the mappings and
rem  the libraries and can take several minutes; later starts take seconds.
rem  Closing the Minecraft window ends the launcher.
rem
rem  Optional argument - a world to jump straight into, for example
rem      run-client.bat SmokeWorld
rem
rem  Optional switch - report the Java it found and start nothing:
rem      run-client.bat --check
rem
rem  The server answers on http://127.0.0.1:25585/mcp once the game is in a world.
rem ---------------------------------------------------------------------------
setlocal EnableDelayedExpansion
title Minecraft MCP - client with the MCP server
cd /d "%~dp0"

set "CHECKONLY="
if /i "%~1"=="--check" set "CHECKONLY=1"

echo.
echo  [Minecraft MCP] Folder: %CD%

if not exist "gradlew.bat" (
    echo  [Minecraft MCP] gradlew.bat is missing - keep this file inside the mod folder.
    echo.
    pause
    exit /b 1
)

rem --- the build needs a JDK 25; the game itself is compiled for Java 21 ------
set "FOUND="
set "CAND=%JAVA_HOME%"
call :check
for /d %%d in ("%ProgramFiles%\Microsoft\jdk-*") do if not defined FOUND (set "CAND=%%~fd" & call :check)
for /d %%d in ("%ProgramFiles%\Java\jdk-*") do if not defined FOUND (set "CAND=%%~fd" & call :check)
for /d %%d in ("%ProgramFiles%\Eclipse Adoptium\jdk-*") do if not defined FOUND (set "CAND=%%~fd" & call :check)
for /d %%d in ("%ProgramFiles%\Amazon Corretto\jdk*") do if not defined FOUND (set "CAND=%%~fd" & call :check)
for /d %%d in ("%ProgramFiles%\Zulu\zulu-*") do if not defined FOUND (set "CAND=%%~fd" & call :check)
for /d %%d in ("%ProgramFiles%\BellSoft\LibericaJDK-*") do if not defined FOUND (set "CAND=%%~fd" & call :check)
for /d %%d in ("%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-*") do if not defined FOUND (set "CAND=%%~fd" & call :check)

if not defined FOUND (
    echo.
    echo  [Minecraft MCP] No Java 25 found.
    echo.
    echo  The build needs a JDK 25 ^(the game itself still runs on Java 21^).
    echo  Install "Microsoft Build of OpenJDK 25" and run this file again:
    echo      https://learn.microsoft.com/java/openjdk/download
    echo.
    echo  Or point JAVA_HOME at a JDK 25 folder, for example:
    echo      set JAVA_HOME=C:\Program Files\Microsoft\jdk-25.0.4.101-hotspot
    echo.
    pause
    exit /b 1
)

echo  [Minecraft MCP] Java:   !JAVA_HOME!  ^( !JAVAVER! ^)

if defined CHECKONLY (
    echo  [Minecraft MCP] Check only - nothing was started.
    echo.
    endlocal
    exit /b 0
)

echo  [Minecraft MCP] Command: gradlew.bat runClient -Pmcp --console=plain
if not "%~1"=="" echo  [Minecraft MCP] World:   %~1
echo  [Minecraft MCP] Server:  http://127.0.0.1:25585/mcp
echo.
echo  [Minecraft MCP] Starting Minecraft with the mod - this window shows the log.
echo.

rem gradlew is called with its full path: some environments do not search the
rem current folder for executables (NoDefaultCurrentDirectoryInExePath).
if "%~1"=="" (
    call "%~dp0gradlew.bat" runClient -Pmcp --console=plain
) else (
    call "%~dp0gradlew.bat" runClient -Pmcp --console=plain --args="--quickPlaySingleplayer %~1"
)

set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" (
    echo.
    echo  [Minecraft MCP] The client stopped with code %CODE% - see the log above.
    pause
)
echo.
echo  [Minecraft MCP] Minecraft closed.
endlocal
exit /b %CODE%

rem ---------------------------------------------------------------------------
rem  :check - tests the JDK folder in CAND and keeps it when it is version 25+.
rem  The version is read from a temp file because "for /f" would mangle a
rem  quoted java.exe path that contains spaces.
rem ---------------------------------------------------------------------------
:check
if "%CAND%"=="" exit /b
if not exist "%CAND%\bin\java.exe" exit /b

set "VF=%TEMP%\minecraft_mcp_java_version.txt"
if not defined TEMP set "VF=%~dp0minecraft_mcp_java_version.txt"
"%CAND%\bin\java.exe" -version > "%VF%" 2>&1
set "RAWVER="
for /f "usebackq tokens=3" %%j in ("%VF%") do if not defined RAWVER set "RAWVER=%%j"
del "%VF%" >nul 2>&1
if not defined RAWVER exit /b

set "RAWVER=!RAWVER:"=!"
for /f "tokens=1 delims=.-+_" %%m in ("!RAWVER!") do set "MAJOR=%%m"
if not defined MAJOR exit /b
if !MAJOR! LSS 25 exit /b

set "FOUND=1"
set "JAVA_HOME=%CAND%"
set "JAVAVER=!RAWVER!"
exit /b
