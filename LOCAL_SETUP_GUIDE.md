# Local Development Environment Setup

## Quick Start

Setup Selenium Grid with Docker Compose:

```bash
cd src/test/resources
docker compose up -d
```

Service:
- Selenium Grid: http://localhost:4444

Stop:

```bash
docker compose down
```

Other services (run locally):
- Jenkins: http://localhost:9090 (run separately: java -jar jenkins.war --httpPort=9090)
- MCP Server: http://localhost:8080 (run separately: python mcp/mcp_orchestrator.py)


---

## Manual Setup (Optional)

Run services locally without Docker.

### Jenkins

File: jenkins.war
Path: directory where jenkins.war is downloaded
Command: java -jar jenkins.war --httpPort=9090
Port: 9090
URL: http://localhost:9090
Purpose: CI/CD pipeline

Download: https://www.jenkins.io/download/

### Selenium Grid Hub

File: resources/Grid/selenium-server-4.41.0.jar
Path: project root
Command: java -jar ./resources/Grid/selenium-server-4.41.0.jar hub
Port: 4444
URL: http://localhost:4444
Purpose: Browser instance management

Download: https://www.selenium.dev/downloads/

### Selenium Grid Nodes

Chrome Node:
File: resources/Grid/selenium-server-4.41.0.jar
Path: project root
Command: java -jar ./resources/Grid/selenium-server-4.41.0.jar node --detect-drivers true --hub http://localhost:4444 --port 5555
Port: 5555
Purpose: Chrome test execution

Firefox Node:
File: resources/Grid/selenium-server-4.41.0.jar
Path: project root
Command: java -jar ./resources/Grid/selenium-server-4.41.0.jar node --detect-drivers true --hub http://localhost:4444 --port 5556
Port: 5556
Purpose: Firefox test execution

Edge Node:
File: resources/Grid/selenium-server-4.41.0.jar
Path: project root
Command: java -jar ./resources/Grid/selenium-server-4.41.0.jar node --detect-drivers true --hub http://localhost:4444 --port 5557
Port: 5557
Purpose: Edge test execution

WebDriver downloads (required if not using Docker):
- ChromeDriver: https://chromedriver.chromium.org/
- GeckoDriver: https://github.com/mozilla/geckodriver/releases
- MSEdgeDriver: https://developer.microsoft.com/en-us/microsoft-edge/tools/webdriver/

---

## MCP Server

Setup (Option 1 - Manual):
1. Copy .env.example to .env
2. Edit .env with your credentials (SLACK_WEBHOOK_URL, JENKINS_URL, JIRA_HOST, CLAUDE_API_KEY, etc.)
3. Activate virtual environment at the root folder: .\.venv\Scripts\Activate.ps1

File: mcp/mcp_orchestrator.py
Path: project root
Command: python mcp/mcp_orchestrator.py (after activating .venv)
Port: 5000
URL: http://localhost:5000
Health check: http://localhost:5000/health
Purpose: Test result notifications and analysis

Note: Startup scripts automatically activate virtual environment and start MCP Server. If PowerShell script won't run, set execution policy first:
```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

---

## Configuration Files

Create .env file:

```bash
# PowerShell
Copy-Item .env.example .env

# Bash/Linux
cp .env.example .env
```

Edit .env with your credentials.

Create config.properties file:

```bash
# PowerShell
Copy-Item config.properties.example src/test/resources/config.properties

# Bash/Linux
cp config.properties.example src/test/resources/config.properties
```

Edit config.properties with your test environment settings.

---

## Verification

Check Docker Selenium Grid:

```bash
cd src/test/resources
docker compose ps          # Show container status
docker compose logs        # Show container logs
docker compose logs -f     # Follow logs in real-time
```

Check service status:

```bash
curl http://localhost:9090           # Jenkins (local)
curl http://localhost:4444/status    # Selenium Grid (docker)
curl http://localhost:8080           # MCP Server (local)
```

---

## Troubleshooting

Port already in use:

```bash
# Windows
netstat -ano | findstr ":[PORT]"
taskkill /PID [PID] /F

# Linux/Mac
lsof -i :[PORT]
kill -9 [PID]
```

Docker service failed:

```bash
docker compose logs [service-name]
docker compose restart [service-name]
docker compose down --volumes
docker compose up -d
```

Virtual environment error:

```bash
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r mcp/requirements.txt
```

---

## Notes

- Ensure Docker Desktop is running before executing docker compose up -d
- Each service can run independently
- Multiple terminal windows required: one for Jenkins, one for Selenium Grid Hub, one for MCP Server
- For Windows PowerShell scripts, you may need to set execution policy: Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
- To stop all services, run docker compose down from src/test/resources directory, then stop Jenkins and MCP Server manually
- Test connectivity after startup by running the Verification commands
