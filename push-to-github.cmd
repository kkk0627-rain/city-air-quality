@echo off
REM ===========================================================================
REM  Push this folder to a GitHub repository.
REM
REM  BEFORE YOU RUN THIS:
REM    1. Go to https://github.com/new and create an EMPTY repository.
REM       Do NOT tick "Add a README file", "Add .gitignore" or "Choose a
REM       license" - a non-empty repo makes the first push get rejected.
REM    2. Create a Personal Access Token (classic) with the "repo" scope:
REM       https://github.com/settings/tokens
REM       GitHub does NOT accept your account password over HTTPS anymore.
REM
REM  WHAT THIS SCRIPT DOES:
REM    - git remote add origin  <the URL you paste>
REM    - git branch -M main
REM    - git push -u origin main
REM
REM  When a login window pops up:
REM    username = your GitHub user name
REM    password = the Personal Access Token  (NOT your account password)
REM
REM  TIP: this script also works for Gitee (gitee.com) - just paste a Gitee
REM  repo URL instead. Gitee is usually faster from mainland China.
REM
REM  NOTE: this file is ASCII-ONLY on purpose. Windows cmd decodes batch
REM  files with the local codepage (GBK on this machine); stray UTF-8 bytes
REM  get merged into following characters and break the script silently.
REM ===========================================================================

setlocal
cd /d "%~dp0"

echo.
echo ============================================================
echo   Push to GitHub
echo   Folder : %CD%
echo ============================================================
echo.

git rev-parse --git-dir >nul 2>nul
if errorlevel 1 goto :not_a_repo

echo   This repository currently has:
for /f "tokens=*" %%a in ('git rev-list --count HEAD') do echo     %%a commits
echo.

set "REPO="
set /p "REPO=Paste the repo URL, example https://github.com/you/repo.git : "
if not defined REPO goto :no_url

echo.
echo [1/4] Setting remote origin ...
git remote remove origin >nul 2>nul
git remote add origin %REPO%
if errorlevel 1 goto :failed

echo [2/4] Renaming branch to main ...
git branch -M main
if errorlevel 1 goto :failed

echo [3/4] Current remote:
git remote -v
echo.

echo [4/4] Pushing now. If a login window appears:
echo         username = your GitHub user name
echo         password = your Personal Access Token
echo.
git push -u origin main
if errorlevel 1 goto :push_failed

echo.
echo ============================================================
echo   Done. Open the repo URL in your browser to verify.
echo ============================================================
echo.
pause
exit /b 0

:not_a_repo
echo [ERROR] This folder is not a git repository.
pause
exit /b 1

:no_url
echo [ERROR] No URL entered. Nothing was changed.
pause
exit /b 1

:failed
echo [ERROR] A git command failed. Read the message above.
pause
exit /b 1

:push_failed
echo.
echo [ERROR] Push failed. Most common causes:
echo   1. Wrong credentials. Use a Personal Access Token, not your password.
echo      Create one here: https://github.com/settings/tokens   scope: repo
echo   2. The GitHub repo is NOT empty - you ticked README / .gitignore /
echo      license when creating it. Delete it, make a new EMPTY one, retry.
echo   3. Network problem. Retry, or use Gitee instead - paste a Gitee URL.
echo   4. URL typo, or you are not the owner of that repo.
echo.
echo Nothing was lost. Your local commits are safe; just run this again.
echo.
pause
exit /b 1
