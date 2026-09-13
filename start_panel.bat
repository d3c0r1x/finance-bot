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
python panel.py
pause
