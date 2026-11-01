@echo off
setlocal EnableExtensions
cd /d "%~dp0.."

if exist "mycontacts-signing.p12" (
  echo Signing keystore already exists. It will not be overwritten.
  echo Keep this file private and continue with the GitHub secret setup.
  goto :encode
)

set "KEYTOOL=keytool"
where keytool >nul 2>&1
if errorlevel 1 (
  if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\keytool.exe" (
    set "KEYTOOL=%ProgramFiles%\Android\Android Studio\jbr\bin\keytool.exe"
  ) else (
    echo keytool was not found. Install JDK 17 or add its bin folder to PATH.
    pause
    exit /b 1
  )
)

echo Creating a private PKCS12 signing key.
echo Keytool will ask you to create a password. Use a strong password and keep it safe.
echo For the key password, press ENTER to reuse the keystore password.
"%KEYTOOL%" -genkeypair -v -keystore "mycontacts-signing.p12" -storetype PKCS12 -alias mycontacts -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=My Contacts"
if errorlevel 1 (
  echo Keystore creation failed.
  pause
  exit /b 1
)

:encode
python -c "import base64,pathlib; pathlib.Path('mycontacts-signing-base64.txt').write_text(base64.b64encode(pathlib.Path('mycontacts-signing.p12').read_bytes()).decode('ascii'), encoding='ascii')"
if errorlevel 1 (
  py -3 -c "import base64,pathlib; pathlib.Path('mycontacts-signing-base64.txt').write_text(base64.b64encode(pathlib.Path('mycontacts-signing.p12').read_bytes()).decode('ascii'), encoding='ascii')"
)
if errorlevel 1 (
  echo Python was not found. Install Python 3 or add it to PATH.
  pause
  exit /b 1
)

echo.
echo Created:
echo   mycontacts-signing.p12
echo   mycontacts-signing-base64.txt
echo.
echo Add these GitHub Actions repository secrets:
echo   ANDROID_KEYSTORE_BASE64 = full contents of mycontacts-signing-base64.txt
echo   ANDROID_KEYSTORE_PASSWORD = the password you entered
echo   ANDROID_KEY_ALIAS = mycontacts
echo   ANDROID_KEY_PASSWORD = the same password
echo.
echo Never commit or send the .p12 or base64 file. Keep a secure backup.
pause
