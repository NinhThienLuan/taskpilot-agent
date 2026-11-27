# 01. Kiến trúc tổng thể & Pipeline 7 bước

## 1. Tech Stack hiện tại

| Thành phần | Công nghệ / Phiên bản |
| :--- | :--- |
| **Runtime & Build** | Java 21 (OpenJDK 21), Apache Maven 3.9+ |
| **Core Framework** | Spring Boot 3.4.5, Spring AI 1.0.0 (GA) |
| **LLM Providers** | Profile `ollama` (mặc định — `qwen2.5-coder:7b`) & Profile `gemini` (`gemini-2.5-flash` qua OpenAI-compatible API) |
| **Database** | PostgreSQL 16 (production/local via `docker-compose.yml`), H2 (unit/integration tests) |
| **Message Queue** | RabbitMQ 3.13 (`taskpilot.jobs.queue`, Claim-Check Pattern) + `in-memory` fallback |
| **Sandbox** | `local` (`LocalProcessCommandExecutor`) hoặc `docker` (`DockerSandboxCommandExecutor`) |
| **Web UI** | Static SPA Dashboard phục vụ trực tiếp tại `src/main/resources/static/index.html` |

---

## 2. Nguyên tắc kiến trúc: Hybrid Workflow + Explicit ReAct Agent

Hệ thống tách bạch hoàn toàn giữa **Orchestrator tất định (Deterministic)** và **AI Agent tự suy luận**:

```text
[Web Dashboard / POST /api/jobs]
        │
        ├─(1) Lưu Job (PENDING) + 7 JobSteps ──▶ [PostgreSQL]
        │                                             ▲
        └─(2) Publish jobId ──▶ [RabbitMQ]            │ (Cập nhật tiến độ,
                                     │                │  log & TokenUsage)
                                     ▼                │
                       [AsyncJobPipelineService] ─────┘
                                     │
   ┌─────────────────────────────────┴─────────────────────────────────┐
   ▼                                                                   ▼
(1) FETCH_TICKET ──▶ (2) SETUP_SANDBOX ──▶ (3) AI_CODING_LOOP (ReAct)
                                                    │
   ┌────────────────────────────────────────────────┘
   ▼
(4) RUN_TESTS ──▶ (5) GIT_COMMIT_PUSH ──▶ (6) CREATE_PR ──▶ (7) REPORT_TICKET
```

### Explicit ReAct Loop trong `CodingAgent`
Thay vì dùng vòng lặp tool ngầm của Spring AI (`internalToolExecutionEnabled=true`), [`CodingAgent`](../src/main/java/com/taskpilot/agent/core/CodingAgent.java) bật:
- `ToolCallingChatOptions.builder().toolCallbacks(toolCallbacks).internalToolExecutionEnabled(false).build()`
- Chủ động gọi `chatModel.call(prompt)` và `toolCallingManager.executeToolCalls(prompt, response)` trong vòng `for (1..maxIterations)`.
- Nhờ vậy kiểm soát chính xác `maxIterations`, ghi log từng lượt tool call và cộng dồn `promptTokens / completionTokens / totalTokens` qua mọi vòng lặp.

---

## 3. Chi tiết 7 bước thực thi (`AsyncJobPipelineService`)

| Bước | Tên bước | Thành phần thực thi | Hành vi mặc định (Local) | Hành vi khi bật Integrations |
| :--- | :--- | :--- | :--- | :--- |
| **1** | `FETCH_TICKET` | `JiraClient` / Request payload | Dùng `taskDescription` từ form/API (`COMPLETED`) | Gọi Jira REST API v3 lấy summary & description |
| **2** | `SETUP_SANDBOX` | `SandboxLifecycle` | No-op trên thư mục local (`COMPLETED`) | Khởi động Docker container riêng (`docker run -d`) |
| **3** | `AI_CODING_LOOP` | `CodingAgent` + `WorkspaceTools` | Chạy vòng lặp ReAct, lưu `iterations` & `TokenUsage` | Tương tự (mọi lệnh `run_command` chạy trong Docker) |
| **4** | `RUN_TESTS` | `CommandExecutor` | Chạy độc lập `testCommand` (vd `mvn test`), lưu full log | Tương tự; nếu fail → trạng thái `VERIFICATION_FAILED` |
| **5** | `GIT_COMMIT_PUSH` | `CommandExecutor` + `GitHubPrClient` | Thu thập `git status` & `git diff --stat` (`SKIPPED`) | Tạo branch `feature/<ticket>-<jobId>`, commit & push |
| **6** | `CREATE_PR` | `GitHubPrClient` | `SKIPPED` | Mở **Draft Pull Request** trên GitHub, lưu `prUrl` |
| **7** | `REPORT_TICKET` | `JiraClient` | `SKIPPED` | Comment báo cáo `Reason / Solution / PR` & chuyển status |

---

## 4. Mô hình dữ liệu (PostgreSQL)

- **Bảng `job_runs` (`JobRunEntity`):** `id`, `ticket_id`, `task_description`, `extra_prompt`, `repo_path`, `test_command`, `branch_name`, `pr_url`, `status`, `summary`, `git_diff_stat`, `iterations`, `prompt_tokens`, `completion_tokens`, `total_tokens`, `created_at`, `started_at`, `finished_at`.
- **Bảng `job_steps` (`JobStepEntity`):** `id`, `job_id`, `step_number` (`1..7`), `step_name`, `status` (`PENDING | RUNNING | COMPLETED | SKIPPED | FAILED`), `log_output`, `duration_ms`, `updated_at`.
