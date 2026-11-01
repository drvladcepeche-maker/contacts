@echo off
setlocal
cd /d "%~dp0"
echo.
echo Telegram MTProto session generator
echo This creates a separate server session. Do not share the generated session string.
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
call npm run session
if errorlevel 1 goto failed
echo.
echo Copy the generated session directly into the GitHub repository secret TELEGRAM_SESSION.
echo Do NOT paste it into chat, source files, public GitHub content, or logs.
echo Paste it only into the private GitHub Actions secret TELEGRAM_SESSION.
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
