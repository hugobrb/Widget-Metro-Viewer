@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Update-MetroReferences.ps1" %*
set "references_exit=%errorlevel%"
pause
exit /b %references_exit%
