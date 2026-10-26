@echo off
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0configure-fxlock.ps1" %*
exit /b %ERRORLEVEL%
