@echo off
setlocal
title CS2 Demo Show

set "ROOT=%~dp0"
set "SERVER=%ROOT%server"
set "WEB=%ROOT%web"
if not defined MYSQL_HOST set "MYSQL_HOST=127.0.0.1"
if not defined MYSQL_DATABASE set "MYSQL_DATABASE=csdemo"
if not defined MYSQL_USER set "MYSQL_USER=csdemo"
if not defined MYSQL_PASSWORD set "MYSQL_PASSWORD="
if not defined MYSQL_ADMIN_USER set "MYSQL_ADMIN_USER=root"
if not defined BACKEND_PORT set "BACKEND_PORT=8090"
if not defined UPDATE_STEAM_AVATARS_ON_START set "UPDATE_STEAM_AVATARS_ON_START=true"

if exist "%ROOT%.env" (
  for /f "usebackq tokens=1,* delims==" %%A in ("%ROOT%.env") do set "%%A=%%B"
)

echo ========================================
echo        CS2 Demo Show starting...
echo ========================================

if not exist "%SERVER%\pom.xml" (
  echo [ERROR] server\pom.xml not found.
  pause
  exit /b 1
)
if not exist "%WEB%\package.json" (
  echo [ERROR] web\package.json not found.
  pause
  exit /b 1
)

echo Checking backend port %BACKEND_PORT% ...
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%BACKEND_PORT% .*LISTENING"') do (
  if not "%%P"=="0" (
    echo Port %BACKEND_PORT% is occupied by PID %%P. Stopping it...
    taskkill /PID %%P /F >nul 2>nul
  )
)

where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java is not installed or not in PATH.
  pause
  exit /b 1
)
where mvn >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Maven is not installed or not in PATH.
  pause
  exit /b 1
)
where npm >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Node.js/npm is not installed or not in PATH.
  pause
  exit /b 1
)

where py >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Python launcher is not installed or not in PATH.
  pause
  exit /b 1
)

if not exist "%ROOT%.venv312\Scripts\python.exe" (
  echo Creating Python 3.12 virtual environment...
  py -3.12 -m venv "%ROOT%.venv312"
  if errorlevel 1 (
    echo [ERROR] Python virtual environment creation failed.
    pause
    exit /b 1
  )
)

echo Syncing Python dependencies...
"%ROOT%.venv312\Scripts\python.exe" -m pip install --disable-pip-version-check -r "%ROOT%requirements.txt"
if errorlevel 1 (
  echo [ERROR] Python dependency installation failed.
  pause
  exit /b 1
)

echo Initializing MySQL database and tables...
"%ROOT%.venv312\Scripts\python.exe" "%ROOT%init_mysql.py"
if errorlevel 1 (
  echo [ERROR] MySQL initialization failed. Check MYSQL settings in .env and make sure MySQL is running.
  pause
  exit /b 1
)

echo Syncing frontend dependencies...
pushd "%WEB%"
call npm install --no-audit --no-fund
if errorlevel 1 (
  popd
  echo [ERROR] npm install failed.
  pause
  exit /b 1
)
popd

echo Building backend with latest code...
pushd "%SERVER%"
call mvn -q -DskipTests package
if errorlevel 1 (
  popd
  echo [ERROR] Backend build failed.
  pause
  exit /b 1
)
popd

echo Building frontend with latest code...
pushd "%WEB%"
call npm run build
if errorlevel 1 (
  popd
  echo [ERROR] Frontend build failed.
  pause
  exit /b 1
)
popd

echo Starting Spring Boot backend on http://localhost:8090 ...
start "CS2 Demo Show - Backend" cmd /k "cd /d ""%SERVER%"" && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=%BACKEND_PORT%"

echo Starting Vue frontend on http://localhost:5173 ...
start "CS2 Demo Show - Frontend" cmd /k "cd /d ""%WEB%"" && npm run dev"

if /I "%UPDATE_STEAM_AVATARS_ON_START%"=="true" (
  echo Updating Steam avatar cache in background...
  start "CS2 Demo Show - Avatars" /min "%ROOT%.venv312\Scripts\python.exe" "%ROOT%steam_avatar_scraper.py"
) else (
  echo Steam avatar startup refresh is disabled.
)

timeout /t 5 /nobreak >nul
start "" "http://localhost:5173"

echo.
echo Services started. Close the two terminal windows to stop them.
echo Frontend: http://localhost:5173
echo Backend:  http://localhost:8090
echo.
pause
