# MCP Server Startup Script for PowerShell

# Script lives at mcp/scripts/ — repo root is two levels up
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $ScriptDir)
$VenvPath = Join-Path $ProjectRoot ".venv"
$McpScript = Join-Path $ProjectRoot "mcp\mcp_orchestrator.py"
$EnvFile = Join-Path $ProjectRoot ".env"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "MCP Server Startup Script" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# Check if .env file exists
if (-Not (Test-Path $EnvFile)) {
    Write-Host "ERROR: .env file not found!" -ForegroundColor Red
    Write-Host "Please create .env file from .env.example" -ForegroundColor Yellow
    exit 1
}

# Check if Python venv exists
if (-Not (Test-Path $VenvPath)) {
    Write-Host "ERROR: Python virtual environment not found!" -ForegroundColor Red
    Write-Host "Please run: python -m venv .venv" -ForegroundColor Yellow
    exit 1
}

# Activate virtual environment
Write-Host "Activating Python virtual environment..." -ForegroundColor Cyan
& "$VenvPath\Scripts\Activate.ps1"

if (-Not $?) {
    Write-Host "ERROR: Failed to activate virtual environment" -ForegroundColor Red
    exit 1
}

Write-Host "✓ Virtual environment activated" -ForegroundColor Green
Write-Host ""

# Check if MCP script exists
if (-Not (Test-Path $McpScript)) {
    Write-Host "ERROR: MCP script not found at $McpScript" -ForegroundColor Red
    exit 1
}

# Start MCP server
Write-Host "Starting MCP Server..." -ForegroundColor Cyan
Write-Host "Project Root: $ProjectRoot" -ForegroundColor Gray
Write-Host "MCP Script: $McpScript" -ForegroundColor Gray
Write-Host "MCP Port: 8080" -ForegroundColor Gray
Write-Host "MCP Host: 0.0.0.0" -ForegroundColor Gray
Write-Host ""
Write-Host "MCP Server will be available at: http://localhost:8080/" -ForegroundColor Yellow
Write-Host "Press Ctrl+C to stop the server" -ForegroundColor Yellow
Write-Host ""

# Run MCP server
python $McpScript

# If server stops
Write-Host ""
Write-Host "MCP Server stopped" -ForegroundColor Yellow
pause
