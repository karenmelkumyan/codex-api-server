@echo off
setlocal EnableExtensions EnableDelayedExpansion

where java >nul 2>nul
if errorlevel 1 (
  echo ERROR: java is required. Install Java 17 or newer and try again. 1>&2
  exit /b 1
)

set "SCRIPT_DIR=%~dp0"
set "APP_HOME=%SCRIPT_DIR%.."
set "LAUNCH_DIR=%CD%"
set "JAR=%APP_HOME%\lib\codex-api-server.jar"

if not defined CODEX_API_ENV_FILE set "CODEX_API_ENV_FILE=%APP_HOME%\.env"
if exist "%CODEX_API_ENV_FILE%" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%CODEX_API_ENV_FILE%") do (
    if not "%%A"=="" set "%%A=%%B"
  )
)

if not defined CODEX_API_TOKEN (
  echo INFO: CODEX_API_TOKEN is not set. Bridge pairing can still start; protected local /api routes stay unavailable until a token is configured. 1>&2
)

if not defined CODEX_AGENT_WORKING_DIRECTORY set "CODEX_AGENT_WORKING_DIRECTORY=%LAUNCH_DIR%"
if not defined CODEX_API_PORT set "CODEX_API_PORT=8765"

set "LISTENER_PID="
for /f "tokens=5" %%P in ('netstat -ano -p tcp ^| findstr /R /C:":%CODEX_API_PORT% .*LISTENING"') do (
  if not defined LISTENER_PID set "LISTENER_PID=%%P"
)

if defined LISTENER_PID (
  echo INFO: codex-api-server found an existing listener on port %CODEX_API_PORT%. 1>&2
  echo INFO: Current listener pid=!LISTENER_PID! 1>&2
  for /f "skip=1 tokens=* delims=" %%C in ('wmic process where processid^=!LISTENER_PID! get CommandLine 2^>nul') do (
    if not "%%C"=="" echo INFO: Command: %%C 1>&2
  )

  set "RESTART_CHOICE=cancel"
  if /I "%CODEX_API_RESTART_EXISTING%"=="true" (
    set "RESTART_CHOICE="
  ) else if "%CODEX_API_RESTART_EXISTING%"=="1" (
    set "RESTART_CHOICE="
  ) else (
    set /p "RESTART_CHOICE=Press Enter to restart it, or type anything else to cancel: "
  )

  if not "!RESTART_CHOICE!"=="" (
    echo INFO: Keeping the existing codex-api-server process. 1>&2
    exit /b 0
  )

  echo INFO: Stopping existing listener pid=!LISTENER_PID!... 1>&2
  taskkill /PID !LISTENER_PID! /T >nul 2>nul
  if errorlevel 1 (
    echo ERROR: Unable to stop pid=!LISTENER_PID!. Stop it manually and run this launcher again. 1>&2
    exit /b 1
  )

  timeout /t 2 /nobreak >nul
  set "STILL_LISTENING="
  for /f "tokens=5" %%P in ('netstat -ano -p tcp ^| findstr /R /C:":%CODEX_API_PORT% .*LISTENING"') do (
    set "STILL_LISTENING=1"
  )
  if defined STILL_LISTENING (
    echo ERROR: Port %CODEX_API_PORT% is still in use. Stop the existing process manually and run this launcher again. 1>&2
    exit /b 1
  )
  echo INFO: Existing listener stopped; starting codex-api-server. 1>&2
)

pushd "%APP_HOME%" >nul
java %JAVA_OPTS% -jar "%JAR%" %*
set "EXIT_CODE=%ERRORLEVEL%"
popd >nul
exit /b %EXIT_CODE%
