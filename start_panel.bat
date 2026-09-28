@echo off
cd /d %~dp0
if not exist venv (
    echo Создаю виртуальное окружение...
    python -m venv venv
    call venv\Scripts\activate.bat
    pip install -r requirements.txt
) else (
    call venv\Scripts\activate.bat
)

rem Секреты приходят из Infisical, а не из файла .env на диске.
where infisical >nul 2>nul
if errorlevel 1 (
    echo Не найден CLI Infisical -- без него секреты не подтянутся.
    echo Установи: winget install infisical
    echo Затем один раз: infisical login  и  infisical init
    echo Подробности -- README, раздел "Секреты Infisical".
    pause
    exit /b 1
)

infisical run --env=dev -- python panel.py
pause
