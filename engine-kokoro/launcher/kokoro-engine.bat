@echo off
rem Launcher for the standalone Kokoro TTS engine (Windows x64).
rem
rem The release workflow places a self-contained Java runtime under runtime\ next to this script
rem (jlink output), so no system JDK is required. The engine resolves its model from the bundled
rem model\ directory and speaks the --stdio protocol the plugin drives.
rem
rem Usage: kokoro-engine.bat --stdio ^| --selftest
setlocal
set DIR=%~dp0

if exist "%DIR%runtime\bin\java.exe" (
  set JAVA="%DIR%runtime\bin\java.exe"
) else (
  set JAVA=java
)

%JAVA% -cp "%DIR%lib\*" com.grahambartley.engine.KokoroEngineMain %*
