# TaskPilot Agent

Hệ thống **AI Coding Agent** viết bằng **Java 21 + Spring Boot 3.4.5 + Spring AI 1.0.0**, tự động hóa trọn vẹn quy trình xử lý ticket phần mềm qua **7 bước**:

> **1. Fetch Ticket (Jira/Local) → 2. Setup Sandbox (Local/Docker) → 3. AI Coding Loop (Explicit ReAct) → 4. Run Tests → 5. Git Commit & Push → 6. Create Draft PR (GitHub) → 7. Report Ticket (Jira)**

---

## Tính năng chính (Đã hoàn thiện trên `main`)

- **Dual LLM Profiles (`ollama` mặc định & `gemini`):** Chuyển đổi giữa small model local qua Ollama (`qwen2.5-coder:7b`, `qwen3:8b`...) và Google Gemini (`gemini-2.5-flash` / `gemini-2.5-pro`) chỉ bằng Spring Profile.
- **Explicit ReAct Loop (`CodingAgent`):** Tắt vòng lặp tool ngầm của Spring AI (`internalToolExecutionEnabled=false`), chủ động điều phối bằng `ToolCallingManager` để kiểm soát `maxIterations`, ghi log từng vòng và cộng dồn `TokenUsage`.
- **7 `@Tool` cô lập theo từng Job (`WorkspaceTools` + `PathGuard`):** Chống path traversal, chặn sửa `.git/` và `.env*`, cắt gọn output dài, lọc sạch secrets khỏi biến môi trường subprocess.
- **2 chế độ Sandbox (`local` | `docker`):** Cô lập lệnh build/test trong Docker container non-root với giới hạn `--cpus`, `--memory`, `--pids-limit` và tự động `docker rm -f` sau mỗi job.
- **Async Platform (PostgreSQL + RabbitMQ + REST API + Web Dashboard):** Giao việc bất đồng bộ theo **Claim-Check Pattern**, lưu tiến độ 7 bước vào PostgreSQL, theo dõi thời gian thực tại Web Dashboard (`/`).
- **Tích hợp GitHub PR & Jira:** Tự tạo Draft PR (không auto-merge), fetch ticket Jira, comment báo cáo `Reason / Solution` và chuyển trạng thái ticket.

---

## Quick Start (Web Dashboard + PostgreSQL + RabbitMQ)

### 1. Khởi động hạ tầng cục bộ
```bash
docker compose up -d
```
- PostgreSQL: `localhost:5432` (`taskpilot` / `taskpilot`)
- RabbitMQ: `localhost:5672` | Management UI: `http://localhost:15672` (`taskpilot` / `taskpilot`)

### 2. Khởi chạy ứng dụng
- **Với Ollama (profile mặc định, cần Ollama chạy tại `localhost:11434`):**
  ```bash
  mvn spring-boot:run
  ```
- **Với Gemini (Google AI Studio API Key):**
  ```powershell
  # PowerShell (Windows)
  $env:GEMINI_API_KEY="your-google-ai-studio-key"
  mvn spring-boot:run "-Dspring-boot.run.profiles=gemini"
  ```
- Mở trình duyệt vào `http://localhost:8080` để sử dụng **TaskPilot Web Dashboard**.

### 3. Chạy kiểm thử tự động (không cần Docker / không tốn phí API)
```bash
mvn test
```
*(Toàn bộ 17 tests sử dụng H2 in-memory DB, Mock `ChatModel`, Fake `DockerCli` và `MockRestServiceServer`.)*

---

## Ánh xạ theo 5 lớp kiến trúc AI

| Lớp | Thành phần trong TaskPilot |
| :--- | :--- |
| **1. Models** | Spring AI `ChatModel` với 2 profile: `ollama` (small model local) & `gemini` (Google AI Studio) |
| **2. Knowledge & Context** | Ticket context (Jira/Local), extra prompt + quick presets, output truncation tối ưu context window |
| **3. Agency & Workflows** | Hybrid: Pipeline 7 bước cố định (`AsyncJobPipelineService`) + Explicit ReAct Loop (`CodingAgent`) ở bước 3 |
| **4. Adaptation & Serving** | Tách cấu hình cửa sổ đọc/log riêng cho small model (Ollama) vs cloud model (Gemini) |
| **5. Harness & LLMOps** | `PathGuard`, `DockerSandboxCommandExecutor`, `maxIterations`, PostgreSQL 7-step & `TokenUsage` tracking |

---

## Bộ tài liệu kỹ thuật (`docs/`)

- [01 - Kiến trúc tổng thể & Pipeline 7 bước](docs/01-architecture-and-pipeline.md)
- [02 - Đặc tả 7 `@Tool`, Agent Harness & `CommandExecutor`](docs/02-agent-tools-spec.md)
- [03 - Cấu trúc dự án, Cấu hình & Lệnh chạy (CLI / Web / Test)](docs/03-mvp-and-sandbox-plan.md)
- [04 - Hướng dẫn vận hành Docker Sandbox](docs/04-docker-sandbox-guide.md)
- [05 - Kiến trúc Async Platform (RabbitMQ, PostgreSQL, REST API & Integrations)](docs/05-phase4-async-platform.md)
