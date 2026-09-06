@echo off
REM Open Birdy settings GUI next to Birdy.exe, or from a source checkout.
set HERE=%~dp0..
if exist "%HERE%\Birdy.exe" (
  "%HERE%\Birdy.exe" --settings
) else if exist "%HERE%\dist\Birdy.exe" (
  "%HERE%\dist\Birdy.exe" --settings
) else (
  python "%HERE%\birdweather_local.py" --settings
)
