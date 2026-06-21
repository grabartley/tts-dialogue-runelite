@echo off
rem Launcher for the standalone Zonos GPU TTS engine (Windows x64, NVIDIA).
rem
rem The release workflow builds a self-contained PyInstaller bundle next to this script: an embedded
rem Python interpreter plus the PyTorch CUDA wheels (which carry their own CUDA runtime), the Zonos
rem package, and the model weights + reference-voice bank. So an end user needs ONLY a compatible
rem NVIDIA GPU and its normal driver: no system Python, no CUDA toolkit, no dev environment.
rem
rem The PyInstaller executable lives under runtime\zonos-engine.exe next to this script and speaks
rem the same --stdio protocol the plugin drives. If a frozen exe is not present (e.g. running from a
rem source checkout), fall back to `python -m zonos_engine`.
rem
rem Usage: zonos-engine.bat --stdio ^| --selftest [--wav out.wav] ^| --mock ...
setlocal
set DIR=%~dp0

if exist "%DIR%runtime\zonos-engine.exe" (
  "%DIR%runtime\zonos-engine.exe" %*
) else (
  python -m zonos_engine %*
)
