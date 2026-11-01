@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Update-MetroCatalog.ps1" %*
set "catalog_exit=%errorlevel%"
pause
exit /b %catalog_exit%
