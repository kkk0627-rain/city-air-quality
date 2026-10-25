@echo off
REM ===========================================================================
REM  AQI Dashboard - one-click launcher (Windows)
REM
REM  IMPORTANT: this file is intentionally ASCII-ONLY. Windows cmd decodes
REM  batch files with the local codepage (GBK on this machine); stray UTF-8
REM  bytes get swallowed together with following ASCII chars like ) and ",
REM  which kills the whole script silently. Do NOT add Chinese comments here.
REM
REM  Usage: double-click this file, or run it from a terminal.
REM  Two extra windows will open: backend (8080) and frontend (5173).
REM  Closing those windows stops the services.
REM
REM  v2 changes:
REM    - auto-locates npm / node (this machine has node only inside the
REM      WorkBuddy binaries dir, NOT on the system PATH, so bare "npm" fails)
REM    - auto-locates mysql.exe and silences the false WARN
REM    - skips a service whose port is already listening, so re-running this
REM      script after a partial start does not blow up with port conflicts
REM ===========================================================================

setlocal

set "ROOT=%~dp0"
set "MVN=D:\apache-maven-3.9.9\bin\mvn.cmd"
if not exist "%MVN%" set "MVN=mvn.cmd"

echo.
echo ============================================================
echo   AQI Dashboard launcher
echo ============================================================
echo   Project : %ROOT%
echo   Maven   : %MVN%
echo   Backend : http://localhost:8080
echo   Frontend: http://localhost:5173
echo ============================================================
echo.

REM ---------------------------------------------------------------------------
REM [0] prerequisites
REM ---------------------------------------------------------------------------
echo [0/3] Checking prerequisites...

if not exist "%ROOT%db\init.sql" goto :no_sql

REM --- locate npm -------------------------------------------------------------
set "NPM_DIR="
where npm >nul 2>nul
if not errorlevel 1 set "NPM_DIR=ONPATH"
if not defined NPM_DIR if exist "C:\Program Files\nodejs\npm.cmd" set "NPM_DIR=C:\Program Files\nodejs"
REM note: "\versions\current" on this machine is a plain FILE holding a version
REM string, not a directory, so we must enumerate the version dirs instead.
if not defined NPM_DIR for /d %%d in ("%USERPROFILE%\.workbuddy\binaries\node\versions\*") do if exist "%%d\npm.cmd" set "NPM_DIR=%%d"

if not defined NPM_DIR goto :no_node
if /i "%NPM_DIR%"=="ONPATH" goto :node_ready
set "PATH=%NPM_DIR%;%PATH%"
echo   [OK] npm      : %NPM_DIR%
goto :node_ready

:node_ready
REM --- locate mysql -----------------------------------------------------------
where mysql.exe >nul 2>nul
if not errorlevel 1 goto :mysql_ready
if exist "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" set "PATH=C:\Program Files\MySQL\MySQL Server 8.0\bin;%PATH%"
:mysql_ready

netstat -ano | findstr ":3306" | findstr "LISTENING" >nul
if errorlevel 1 goto :no_mysql
echo   [OK] MySQL    : port 3306 is listening
goto :prereq_done

:no_mysql
echo   [WARN] MySQL  : nothing listening on 3306 - start the MySQL80 service

:prereq_done
if exist "%ROOT%frontend\node_modules" goto :deps_ok
echo   [WARN] frontend\node_modules missing - running npm install first...
pushd "%ROOT%frontend"
call npm install
popd
:deps_ok
echo.

REM ---------------------------------------------------------------------------
REM [1] backend
REM ---------------------------------------------------------------------------
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul
if not errorlevel 1 goto :backend_running

echo [1/3] Starting backend on port 8080 ...
start "AQI Backend :8080" /D "%ROOT%backend" cmd /k call "%MVN%" spring-boot:run
echo       waiting for backend to boot, 20s ...
timeout /t 20 /nobreak >nul
goto :backend_done

:backend_running
echo [1/3] Skipped - something is already listening on 8080

:backend_done
REM ---------------------------------------------------------------------------
REM [2] frontend
REM ---------------------------------------------------------------------------
netstat -ano | findstr ":5173" | findstr "LISTENING" >nul
if not errorlevel 1 goto :frontend_running

echo [2/3] Starting frontend on port 5173 ...
start "AQI Frontend :5173" /D "%ROOT%frontend" cmd /k call npm run dev
echo       waiting for Vite to be ready, 10s ...
timeout /t 10 /nobreak >nul
goto :frontend_done

:frontend_running
echo [2/3] Skipped - something is already listening on 5173

:frontend_done
REM ---------------------------------------------------------------------------
REM [3] open browser
REM ---------------------------------------------------------------------------
echo.
echo [3/3] Opening http://localhost:5173
start "" http://localhost:5173

echo.
echo ============================================================
echo   Done. If the page is blank, check the two windows:
echo     - backend  must print "Started AqiDashboardApplication"
echo     - frontend must print "Local: http://localhost:5173/"
echo   Dataset date range is 2025-01-01 .. 2025-12-31.
echo ============================================================
echo.
echo Press any key to close this launcher. Services keep running.
pause >nul
exit /b 0

REM ---------------------------------------------------------------------------
REM error paths
REM ---------------------------------------------------------------------------
:no_sql
echo   [ERROR] db\init.sql not found under %ROOT%
goto :fail

:no_node
echo   [ERROR] npm not found. Tried PATH, C:\Program Files\nodejs,
echo           and the WorkBuddy node versions directory.
echo           Install Node.js 18+ from https://nodejs.org/ and retry.
goto :fail

:fail
echo.
echo Launcher aborted. See docs\RUN.md for manual steps.
echo.
pause
exit /b 1
