@echo off
rem ---------------------------------------------------------------------------
rem  Minecraft MCP - builds the mod into a jar you can drop into any 1.21.11
rem  Fabric install, and reports whether that install is ready for it.
rem
rem  Double click this file. The jar lands in:  dist\minecraft-mcp-1.0.0.jar
rem
rem  Optional switch - build and report, but never touch the mods folder:
rem      build-mod.bat --check
rem ---------------------------------------------------------------------------
setlocal EnableDelayedExpansion
title Minecraft MCP - build the mod
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
    echo  [Minecraft MCP] No Java 25 found. Install "Microsoft Build of OpenJDK 25":
    echo      https://learn.microsoft.com/java/openjdk/download
    echo.
    pause
    exit /b 1
)

echo  [Minecraft MCP] Java: !JAVA_HOME!  ^( !JAVAVER! ^)
echo  [Minecraft MCP] Building the mod...
echo.

call "%~dp0gradlew.bat" build --console=plain
if not "!ERRORLEVEL!"=="0" (
    echo.
    echo  [Minecraft MCP] The build failed - see the log above.
    pause
    exit /b 1
)

set "JAR="
for %%f in ("build\libs\minecraft-mcp-*.jar") do (
    set "NAME=%%~nxf"
    if "!NAME:sources=!"=="!NAME!" if not defined JAR set "JAR=%%~ff"
)
if not defined JAR (
    echo  [Minecraft MCP] No jar found in build\libs.
    pause
    exit /b 1
)

for %%f in ("!JAR!") do set "JARNAME=%%~nxf"
if not exist "dist" mkdir "dist"
copy /y "!JAR!" "dist\" >nul
echo.
echo  [Minecraft MCP] Built:  !JAR!
echo  [Minecraft MCP] Copied: %CD%\dist\!JARNAME!

rem --- how ready is the real Minecraft install? ------------------------------
set "MCDIR=%APPDATA%\.minecraft"
set "MODS=%MCDIR%\mods"
set "FABRIC="
for /d %%d in ("%MCDIR%\versions\fabric-loader-*1.21.11*") do set "FABRIC=%%~nxd"
set "API="
for %%f in ("%MODS%\fabric-api-*.jar") do set "API=%%~nxf"

echo.
if defined FABRIC (
    echo  [Minecraft MCP] Fabric Loader profile: found !FABRIC!
) else (
    echo  [Minecraft MCP] Fabric Loader profile: NOT installed for 1.21.11 - run the
    echo                   Fabric installer for that version first.
)
if defined API (
    echo  [Minecraft MCP] Fabric API: found !API!
) else (
    echo  [Minecraft MCP] Fabric API: not in %MODS% - the mod will not load without it.
)

if defined CHECKONLY (
    echo.
    echo  [Minecraft MCP] Check only - nothing was installed.
    echo.
    pause
    exit /b 0
)

echo.
set "ANSWER="
set /p "ANSWER=Install !JARNAME! into %MODS% now? [y/N] "
if /i not "!ANSWER!"=="y" goto :done
if not exist "%MODS%" mkdir "%MODS%"
copy /y "!JAR!" "%MODS%\" >nul
echo  [Minecraft MCP] Installed !JARNAME! into %MODS%
if not defined API echo  [Minecraft MCP] Remember Fabric API - the mod will not load without it.

:done
echo.
echo  [Minecraft MCP] Done.
pause
endlocal
exit /b 0

rem ---------------------------------------------------------------------------
rem  :check - tests the JDK folder in CAND and keeps it when it is version 25+.
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
