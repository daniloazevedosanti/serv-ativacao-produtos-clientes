@REM Maven Wrapper (somente script) para Windows.
@REM Baixa o Maven definido em .mvn\wrapper\maven-wrapper.properties e delega para ele.
@echo off
setlocal
set "MAVEN_HOME="
for /f "usebackq delims=" %%i in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0.mvn\wrapper\mvnw.ps1"`) do set "MAVEN_HOME=%%i"
if not defined MAVEN_HOME (
  echo mvnw: falha ao preparar o Maven 1>&2
  exit /b 1
)
set "MAVEN_PROJECTBASEDIR=%~dp0"
if "%MAVEN_PROJECTBASEDIR:~-1%"=="\" set "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"
"%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
