#!/usr/bin/env groovy
/**
 * MCP Orchestrator Stage - Standalone Groovy Script
 * Usage: @Library('shared-library') _
 *        load "jenkins/run_mcp_server_jenkins.groovy"
 */

def call() {
    stage('Start MCP Orchestrator') {
        steps {
            script {
                echo "🚀 Starting MCP Orchestrator Server..."
                echo "📌 Project Path: ${PROJECT_PATH}"
                
                // Windows: Start Python process in background
                bat """
                    @echo off
                    echo 🚀 Activating Python environment...
                    cd /d "${PROJECT_PATH}"
                    
                    REM Activate venv if exists
                    if exist ".venv\\Scripts\\activate.bat" (
                        call .venv\\Scripts\\activate.bat
                    )
                    
                    REM Start MCP Orchestrator
                    echo 🔧 Starting mcp\\mcp_orchestrator.py...
                    start "MCP Orchestrator" cmd /k "python mcp\\mcp_orchestrator.py"
                    
                    REM Wait for server to start
                    timeout /t 3 /nobreak
                    
                    REM Health check
                    echo 🏥 Checking MCP server health...
                    curl -s http://localhost:5000/health || (
                        echo ❌ MCP server health check failed
                        exit /b 1
                    )
                    
                    echo ✅ MCP Orchestrator started successfully
                """
            }
        }
    }
    
    stage('Run Tests') {
        steps {
            script {
                echo "🧪 Running tests with MCP integration..."
                // Your test execution here
            }
        }
    }
    
    stage('Stop MCP Orchestrator') {
        steps {
            script {
                echo "🛑 Stopping MCP Orchestrator..."
                bat """
                    @echo off
                    echo 🛑 Terminating MCP Orchestrator process...
                    taskkill /FI "WINDOWTITLE eq MCP Orchestrator" /T /F 2>nul || echo ⚠️ Process not found
                    echo ✅ MCP Orchestrator stopped
                """
            }
        }
    }
}

return this
