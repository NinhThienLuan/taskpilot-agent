# 03. Kế hoạch triển khai theo lộ trình `1 -> 3 -> 2` (Java Spring AI)

## Phase 1: Tài liệu & Quy ước Git (Hoàn tất)
- Thiết lập repository git (`taskpilot-agent`, nhánh `main`).
- Hoàn thiện đặc tả kiến trúc Spring AI (`01`), bộ `@Tool` + `CommandExecutor` (`02`) và kế hoạch triển khai (`03`).

---

## Phase 3: Xây dựng MVP Coding Agent (Bước tiếp theo)

Mục tiêu: Xây dựng ứng dụng **Spring Boot + Spring AI (Java 21, Maven)** chạy được vòng lặp `AI Coding Loop` trên một repo local, hỗ trợ chuyển đổi giữa **Gemini** và **Ollama**, và kiểm chứng đầy đủ bằng Unit Test không tốn phí API.

### 1. Cấu trúc dự án Maven
```text
taskpilot-agent/
├── pom.xml
├── docs/
├── samples/
│   └── demo-calc-repo/              # Mini Java Maven repo mẫu để chạy thử Agent
└── src/
    ├── main/
    │   ├── java/com/taskpilot/agent/
    │   │   ├── TaskPilotApplication.java
    │   │   ├── config/
    │   │   │   └── AgentProperties.java        # maxIterations, timeout, truncate limits
    │   │   ├── model/
    │   │   │   ├── TaskRequest.java            # ticketId, description, extraPrompt, repoPath, testCommand
    │   │   │   ├── AgentResult.java            # status, summary, iterations, tokenUsage, stepLogs
    │   │   │   ├── TokenUsageSummary.java      # input, output, total tokens
    │   │   │   └── CommandResult.java          # exitCode, stdout, stderr, timedOut
    │   │   ├── executor/
    │   │   │   ├── CommandExecutor.java        # Interface (Local -> Docker)
    │   │   │   └── LocalProcessCommandExecutor.java
    │   │   ├── tools/
    │   │   │   ├── PathGuard.java              # Chống path traversal & chặn file nhạy cảm
    │   │   │   └── WorkspaceTools.java         # 7 @Tool khởi tạo theo từng Job
    │   │   ├── core/
    │   │   │   ├── CodingAgent.java            # Explicit ReAct Loop (ToolCallingManager)
    │   │   │   └── LocalPipelineRunner.java    # Điều phối MVP: Setup -> Agent -> Verify Test -> Git Diff
    │   │   └── cli/
    │   │       └── TaskPilotCliRunner.java     # Chạy thử nhanh qua CLI args
    │   └── resources/
    │       ├── application.yml                 # Cấu hình chung (Harness limits)
    │       ├── application-gemini.yml          # Profile Gemini (GEMINI_API_KEY)
    │       └── application-ollama.yml          # Profile Ollama (local small model)
    └── test/
        └── java/com/taskpilot/agent/
            ├── tools/
            │   ├── PathGuardTest.java
            │   └── WorkspaceToolsTest.java
            └── core/
                └── CodingAgentMockTest.java    # Test ReAct loop, self-correction & token counting bằng Mock ChatModel
```

### 2. Cấu hình 2 Profile LLM
- **Chạy với Ollama (local small model):**
  ```bash
  mvn spring-boot:run -Dspring-boot.run.profiles=ollama \
    -Dspring-boot.run.arguments="--taskpilot.run.repo=./samples/demo-calc-repo --taskpilot.run.task='Add divide method with zero check and unit tests'"
  ```
- **Chạy với Gemini (cloud):**
  ```bash
  export GEMINI_API_KEY="your-api-key"
  mvn spring-boot:run -Dspring-boot.run.profiles=gemini \
    -Dspring-boot.run.arguments="--taskpilot.run.repo=./samples/demo-calc-repo --taskpilot.run.task='Add divide method with zero check and unit tests'"
  ```

### 3. Tiêu chí nghiệm thu Phase 3
- `mvn test` pass 100% (không cần mạng / không tốn phí API nhờ Mock `ChatModel`).
- Kiểm chứng được:
  - `PathGuard` chặn được `../`, đường dẫn tuyệt đối ngoài workspace và file `.env`/`.git`.
  - `CodingAgent` thực hiện đúng Explicit ReAct Loop, tự đọc lỗi test để sửa lại code, dừng đúng ở `maxIterations`, và tổng hợp chính xác `TokenUsageSummary`.
  - Chạy thực tế được trên `samples/demo-calc-repo` bằng cả `ollama` hoặc `gemini`.

---

## Phase 2: Đóng gói Docker Sandbox an toàn

Mục tiêu: Chuyển bước thực thi code & test sang Docker container cô lập mà **không phải sửa `WorkspaceTools` hay `CodingAgent`**.

### Các thành phần cần làm trong Phase 2:
1. `docker/Dockerfile.sandbox` — Image dựa trên `eclipse-temurin:21-jdk` + Maven + Git, chạy bằng user non-root (`sandbox`).
2. `DockerSandboxCommandExecutor implements CommandExecutor`:
   - Tạo container theo `jobId` với `--cpus=2 --memory=2g --pids-limit=256`.
   - Mount hoặc copy repo vào `/workspace`.
   - Thực thi lệnh qua `docker exec` với timeout cứng.
   - Tự động dọn dẹp container (`docker rm -f`) trong khối `finally` sau khi thu thập xong `git diff`.
3. *(Mở rộng sau Phase 2)*: Tích hợp GitHub PR API, Jira API, RabbitMQ queue, PostgreSQL lưu `Job`/`JobStep`/`TokenUsage` và Web Dashboard theo dõi 7 bước.
