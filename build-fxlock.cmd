@echo off
node "%~dp0scripts\dev.mjs" --build-only %*
exit /b %ERRORLEVEL%
