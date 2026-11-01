@echo off
setlocal
cd /d "%~dp0"
echo.
echo Telegram MTProto session generator
echo The session is saved locally and is never printed to the terminal.
echo.
set /p TELEGRAM_API_ID=Telegram API ID:
set /p TELEGRAM_API_HASH=Telegram API HASH:
if "%TELEGRAM_API_ID%"=="" goto missing
if "%TELEGRAM_API_HASH%"=="" goto missing
echo.
echo Installing worker dependencies...
call npm install
if errorlevel 1 goto failed
echo.
echo Starting Telegram authorization...
call npm run generate-session
if errorlevel 1 goto failed
echo.
echo Session saved in your user profile under .config\contacts-telegram\TELEGRAM_SESSION
echo Add its contents only to the private GitHub Actions secret TELEGRAM_SESSION.
echo Do NOT paste it into chat, source files, public GitHub content, or logs.
goto done
:missing
echo API ID and API HASH are required.
goto done
:failed
echo Session generation failed. Check Node.js/npm and the Telegram API credentials.
:done
echo.
pause
endlocal
