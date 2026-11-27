# 03. Cấu trúc dự án, Cấu hình & Hướng dẫn chạy

## 1. Cấu trúc thư mục hiện tại (`main`)

```text
taskpilot-agent/
├── pom.xml                                  # Java 21, Spring Boot 3.4.5, Spring AI 1.0.0
├── docker-compose.yml                       # PostgreSQL 16 + RabbitMQ 3.13 Management
├── docker/
│   └── Dockerfile.sandbox                   # Non-root Java 21 + Maven + Git sandbox image
├── docs/                                    # Bộ tài liệu kiến trúc & vận hành (01..05)
├── samples/
│   └── demo-calc-repo/                      # Mini Java Maven repo mẫu để test nhanh
└── src/
    ├── main/
    │   ├── java/com/taskpilot/agent/
    │   │   ├── TaskPilotApplication.java
    │   │   ├── api/                         # REST API: JobController, CreateJobRequest, JobResponses
    │   │   ├── cli/                         # CLI Runner: TaskPilotCliRunner
    │   │   ├── config/                      # AgentProperties, SandboxProperties, IntegrationProperties
    │   │   ├── core/                        # CodingAgent (Explicit ReAct), LocalPipelineRunner
    │   │   ├── executor/                    # CommandExecutor, SandboxLifecycle, LocalProcess & Docker
    │   │   ├── integration/                 # GitHubPrClient, JiraClient
    │   │   ├── job/                         # AsyncJobPipelineService, JPA Entities & Repositories
    │   │   ├── model/                       # TaskRequest, AgentResult, CommandResult, TokenUsageSummary
    │   │   ├── queue/                       # JobDispatcher, RabbitMqJobDispatcher, InMemoryJobDispatcher
    │   │   └── tools/                       # PathGuard, WorkspaceTools (7 @Tool)
    │   └── resources/
    │       ├── application.yml              # Cấu hình chung (default profile: ollama)
    │       ├── application-ollama.yml       # Local small model (qwen2.5-coder:7b)
    │       ├── application-gemini.yml       # Google Gemini (GEMINI_API_KEY)
    │       └── static/index.html            # TaskPilot Real-time Web Dashboard
    └── test/java/com/taskpilot/agent/       # 17 unit & integration tests (offline, H2 + Mocks)
```

---

## 2. Biến môi trường & Cấu hình chính

| Nhóm | Biến môi trường / Property | Mặc định | Mô tả |
| :--- | :--- | :--- | :--- |
| **LLM** | `spring.profiles.active` | `ollama` | Chọn `ollama` hoặc `gemini` |
| **Ollama** | `OLLAMA_BASE_URL` / `OLLAMA_MODEL` | `http://localhost:11434` / `qwen2.5-coder:7b` | Endpoint & model Ollama |
| **Gemini** | `GEMINI_API_KEY` / `GEMINI_MODEL` | *(bắt buộc khi dùng gemini)* / `gemini-2.5-flash` | Key từ Google AI Studio |
| **Database** | `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/taskpilot` | Kết nối PostgreSQL |
| **Queue** | `taskpilot.queue.mode` | `rabbitmq` | `rabbitmq` hoặc `in-memory` |
| **Sandbox** | `taskpilot.sandbox.mode` | `local` | `local` hoặc `docker` |
| **GitHub** | `GITHUB_INTEGRATION_ENABLED`, `GITHUB_TOKEN`, `GITHUB_OWNER`, `GITHUB_REPO` | `false` | Bật Step 5 & 6 (Push + Draft PR) |
| **Jira** | `JIRA_INTEGRATION_ENABLED`, `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN` | `false` | Bật Step 1 & 7 (Fetch + Comment) |

---

## 3. Các chế độ chạy

### A. Chế độ Web Server + Dashboard (Khuyên dùng)
```powershell
docker compose up -d
mvn spring-boot:run
# Mở http://localhost:8080
```
*Chạy với Gemini:*
```powershell
$env:GEMINI_API_KEY="AIzaSy..."
mvn spring-boot:run "-Dspring-boot.run.profiles=gemini"
```

### B. Chế độ CLI một lần (không cần bấm UI)
```powershell
mvn package -DskipTests
java -jar target/taskpilot-agent-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=ollama `
  --taskpilot.queue.mode=in-memory `
  --taskpilot.run.enabled=true `
  --taskpilot.run.repo=./samples/demo-calc-repo `
  --taskpilot.run.task="Add divide(int a, int b) with zero check and unit tests"
```

### C. Bộ test tự động (`17/17` tests)
```bash
mvn test
```
- `PathGuardTest` (4): kiểm tra chặn path traversal, đường dẫn tuyệt đối, `.git`, `.env`.
- `WorkspaceToolsTest` (4): kiểm tra `list_files`, `search_grep`, `read_file`, `edit_file`, `run_command` truncation.
- `CodingAgentMockTest` (2): kiểm tra Explicit ReAct Loop, đếm token, dừng ở `maxIterations`.
- `DockerSandboxCommandExecutorTest` (1): kiểm tra lệnh `docker run` giới hạn tài nguyên, `docker exec`, và `docker rm -f`.
- `SpringProfileWiringTest` (3): kiểm tra auto-config profile `gemini`, `ollama` và `docker` sandbox.
- `JobApiIntegrationTest` (1): kiểm tra end-to-end `POST /api/jobs` → 7 steps → `GET /api/jobs/{id}` → `GET /api/health`.
- `IntegrationClientsTest` (2): kiểm tra HTTP contract của `GitHubPrClient` và `JiraClient`.
