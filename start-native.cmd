@echo off
REM ===========================================================================
REM  AQI Dashboard - launcher for the NATIVE frontend (HTML + CSS + JS)
REM
REM  IMPORTANT: this file is intentionally ASCII-ONLY. Windows cmd decodes
REM  batch files with the local codepage (GBK on this machine); stray UTF-8
REM  bytes get swallowed together with following ASCII chars like ) and ",
REM  which kills the whole script silently. Do NOT add Chinese comments here.
REM
REM  This launcher starts:
REM    - backend  Spring Boot on 8080 (skipped if already listening)
REM    - frontend a zero-dependency Node static server on 5174
REM  Port 5174 is one of the origins the backend CorsConfig already allows,
REM  so no backend change is needed.
REM
REM  The three source files it serves are plain HTML/CSS/JS - no build step,
REM  no npm install, no bundler.
REM ===========================================================================

setlocal

set "ROOT=%~dp0"
set "MVN=D:\apache-maven-3.9.9\bin\mvn.cmd"
if not exist "%MVN%" set "MVN=mvn.cmd"

echo.
echo ============================================================
echo   AQI Dashboard launcher - NATIVE frontend
echo ============================================================
echo   Project : %ROOT%
echo   Backend : http://localhost:8080
echo   Frontend: http://127.0.0.1:5174
echo ============================================================
echo.

REM ---------------------------------------------------------------------------
REM [1] locate node.exe
REM ---------------------------------------------------------------------------
echo [1/3] Checking prerequisites...

set "NODE_DIR="
where node >nul 2>nul
if not errorlevel 1 set "NODE_DIR=ONPATH"
if not defined NODE_DIR if exist "C:\Program Files\nodejs\node.exe" set "NODE_DIR=C:\Program Files\nodejs"
REM note: "\versions\current" on this machine is a plain FILE holding a version
REM string, not a directory, so we must enumerate the version dirs instead.
if not defined NODE_DIR for /d %%d in ("%USERPROFILE%\.workbuddy\binaries\node\versions\*") do if exist "%%d\node.exe" set "NODE_DIR=%%d"

if not defined NODE_DIR goto :no_node
if /i not "%NODE_DIR%"=="ONPATH" set "PATH=%NODE_DIR%;%PATH%"
echo   [OK] node     : %NODE_DIR%

if not exist "%ROOT%frontend-native\index.html" goto :no_files
echo   [OK] sources  : frontend-native\index.html
echo.

REM ---------------------------------------------------------------------------
REM [2] backend on 8080
REM ---------------------------------------------------------------------------
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul
if not errorlevel 1 goto :backend_ready

echo [2/3] Starting backend on 8080 ...
start "AQI Backend :8080" /D "%ROOT%backend" cmd /k call "%MVN%" spring-boot:run
echo       waiting 25s for Spring Boot to boot ...
timeout /t 25 /nobreak >nul
goto :frontend

:backend_ready
echo [2/3] Skipped - something is already listening on 8080

:frontend
REM ---------------------------------------------------------------------------
REM [3] native frontend on 5174
REM ---------------------------------------------------------------------------
netstat -ano | findstr ":5174" | findstr "LISTENING" >nul
if not errorlevel 1 goto :frontend_ready

echo [3/3] Starting native frontend on 5174 ...
start "AQI Native :5174" /D "%ROOT%frontend-native" cmd /k node server.js 5174
timeout /t 3 /nobreak >nul
goto :open

:frontend_ready
echo [3/3] Skipped - something is already listening on 5174

:open
echo.
echo Opening http://127.0.0.1:5174/
start "" http://127.0.0.1:5174/

echo.
echo ============================================================
echo   Done.
echo   - backend window must print "Started AqiDashboardApplication"
echo   - if the page shows a red error bar, the backend is not up yet
echo   - dataset range is 2025-01-01 .. 2025-12-31
echo ============================================================
echo.
echo Press any key to close this launcher. Services keep running.
pause >nul
exit /b 0

REM ---------------------------------------------------------------------------
REM error paths
REM ---------------------------------------------------------------------------
:no_node
echo   [ERROR] node.exe not found. Tried PATH, C:\Program Files\nodejs,
echo           and the WorkBuddy node versions directory.
echo           Install Node.js 18+ from https://nodejs.org/ and retry.
echo.
echo See docs\RUN.md for manual steps.
pause
exit /b 1

:no_files
echo   [ERROR] frontend-native\index.html not found under %ROOT%
pause
exit /b 1
