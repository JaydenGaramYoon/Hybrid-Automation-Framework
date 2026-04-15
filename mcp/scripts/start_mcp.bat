@echo off
REM MCP Server Startup Script for Windows CMD

setlocal enabledelayedexpansion

REM Script is mcp\scripts\ — repo root is two levels up from this file
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "PROJECT_ROOT=%%~fI\"
set VENV_PATH=%PROJECT_ROOT%.venv
set MCP_SCRIPT=%PROJECT_ROOT%mcp\mcp_orchestrator.py
set ENV_FILE=%PROJECT_ROOT%.env

echo ==========================================
echo MCP Server Startup Script
echo ==========================================
echo.

REM Check if .env file exists
if not exist "%ENV_FILE%" (
    echo ERROR: .env file not found!
    echo Please create .env file from .env.example
    pause
    exit /b 1
)

REM Check if Python venv exists
if not exist "%VENV_PATH%" (
    echo ERROR: Python virtual environment not found!
    echo Please run: python -m venv .venv
    pause
    exit /b 1
)

REM Activate virtual environment
echo Activating Python virtual environment...
call "%VENV_PATH%\Scripts\activate.bat"

if errorlevel 1 (
    echo ERROR: Failed to activate virtual environment
    pause
    exit /b 1
)

echo [OK] Virtual environment activated
echo.

REM Check if MCP script exists
if not exist "%MCP_SCRIPT%" (
    echo ERROR: MCP script not found at %MCP_SCRIPT%
    pause
    exit /b 1
)

REM Start MCP server
echo Starting MCP Server...
echo Project Root: %PROJECT_ROOT%
echo MCP Script: %MCP_SCRIPT%
echo MCP Port: 8080
echo MCP Host: 0.0.0.0
echo.
echo MCP Server will be available at: http://localhost:8080/
echo Press Ctrl+C to stop the server
echo.

REM Run MCP server
python "%MCP_SCRIPT%"

REM If server stops
echo.
echo MCP Server stopped
pause
