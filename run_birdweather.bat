@echo off
REM Runs birdweather_local.py with no console window.
REM
REM Assumes pythonw.exe is on your PATH. If `where pythonw` doesn't resolve
REM to the Python install you want (e.g. another tool's venv is ahead of it
REM on PATH), replace "pythonw" below with the full path, e.g.:
REM   "C:\Users\<you>\AppData\Local\Programs\Python\Python313\pythonw.exe"

pythonw "%~dp0birdweather_local.py"
