@echo off
REM OpenAPI Validator CLI
REM Run script for Windows
REM
REM Usage: openapi-validator.bat <command> [options]
REM

setlocal enabledelayedexpansion

REM Resolve the script directory
set "SCRIPT_DIR=%~dp0"
set "BASE_DIR=%SCRIPT_DIR%.."
set "JAR_FILE=%BASE_DIR%\lib\openapi-validator-cli.jar"

REM Check if JAR exists
if not exist "%JAR_FILE%" (
    echo Error: Cannot find openapi-validator-cli.jar
    echo Expected location: %JAR_FILE%
    exit /b 1
)

REM Check for Java
where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo Error: Java is not installed or not in PATH
    echo Please install Java 8 or higher to run this tool.
    exit /b 1
)

REM Set default JVM options if not already set
if "%JAVA_OPTS%"=="" set "JAVA_OPTS=-Xmx256m"

REM Run the validator
java %JAVA_OPTS% -jar "%JAR_FILE%" %*
