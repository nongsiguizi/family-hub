@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Building Family Hub APK v2.10.0 ...
python build_apk.py
echo.
pause
