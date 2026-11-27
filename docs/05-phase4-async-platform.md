# 05. Thiết kế Phase 4: Async Platform (RabbitMQ + PostgreSQL + REST API + Dashboard)

Mục tiêu của Phase 4 là nâng cấp TaskPilot từ **CLI đồng bộ** thành **hệ thống điều phối Job bất đồng bộ** giống kiến trúc TaskPilot thực tế, đồng thời vẫn giữ khả năng chạy test/dev gọn nhẹ.

---

## 1. Kiến trúc tổng thể Phase 4

```text
[Web Dashboard / REST Client]
        │  POST /api/jobs (ticketId, repoPath/url, task, extraPrompt)
        ▼
[Spring Boot Web API] ──(1) Lưu Job (PENDING)──▶ [PostgreSQL]
        │                                             ▲
        └──(2) Publish JobId──▶ [RabbitMQ Queue]      │ (4) Cập nhật 7 steps,
                                     │                │     log & TokenUsage
                                     ▼                │
                            [JobQueueWorker] ─────────┘
                                     │ (3) Chạy CodingAgent + SandboxLifecycle
                                     ▼
                           [Local / Docker Sandbox]
```

---

## 2. Chia làm 2 chặng triển khai

### Phase 4A — Async Backend Core (Ưu tiên làm trước)
1. **Hạ tầng cục bộ (`docker-compose.yml`):**
   - `postgres:16-alpine` (DB `taskpilot`, port `5432`)
   - `rabbitmq:3.13-management-alpine` (AMQP `5672`, Management UI `15672`)
2. **Spring Boot Dependencies mới (`pom.xml`):**
   - `spring-boot-starter-web` (REST API)
   - `spring-boot-starter-data-jpa` + `postgresql` (và `h2` ở scope `test` để `mvn test` chạy không cần Docker/Postgres thật)
   - `spring-boot-starter-amqp` (RabbitMQ producer & consumer)
   - `spring-boot-starter-validation`
3. **Database Schema (JPA Entities):**
   - `job_runs`: `id`, `ticket_id`, `task_description`, `extra_prompt`, `repo_path`, `test_command`, `status` (`PENDING | RUNNING | SUCCEEDED | VERIFICATION_FAILED | MAX_ITERATIONS_REACHED | FAILED`), `summary`, `git_diff_stat`, `iterations`, `prompt_tokens`, `completion_tokens`, `total_tokens`, `created_at`, `started_at`, `finished_at`.
   - `job_steps`: `id`, `job_id`, `step_number`, `step_name`, `status` (`PENDING | RUNNING | COMPLETED | FAILED`), `log_output`, `duration_ms`, `updated_at`.
4. **REST API Endpoints:**
   - `POST /api/jobs` — Tạo Job mới (status `PENDING`), khởi tạo các `job_steps`, đẩy `jobId` vào queue `taskpilot.jobs.queue`, trả về `202 Accepted` + `jobId`.
   - `GET /api/jobs` — Danh sách lịch sử các job gần đây.
   - `GET /api/jobs/{id}` — Chi tiết tiến độ từng bước (`job_steps`), log `RUN_TEST`, `gitDiffStat` và `TokenUsage`.
   - `GET /api/health` — Kiểm tra trạng thái sẵn sàng (profile LLM đang dùng, sandbox mode).
5. **Chế độ chạy linh hoạt:**
   - Giữ profile mặc định `spring.profiles.default=ollama` (hoặc chọn rõ `gemini`/`ollama`) và bật web server mặc định ở Phase 4 (`spring.main.web-application-type=servlet`), vẫn hỗ trợ CLI runner nếu truyền `--taskpilot.run.enabled=true`.

---

### Phase 4B — Web Dashboard & Tích hợp GitHub/Jira (Làm sau 4A)
1. **Web Dashboard (tích hợp trực tiếp hoặc Next.js):**
   - Form kích hoạt tác vụ mới (kèm quick presets `+ Viết thêm Unit Test`, `+ Sửa Bug & Kiểm thử`, `+ Refactor`).
   - Theo dõi tiến độ thời gian thực (polling/SSE) cho 7 bước, xem log `RUN_TEST` và biểu đồ `Token Usage`.
2. **Tích hợp ngoài:**
   - **Step 1 (`FETCH_TICKET`):** Jira REST API / GitHub Issues API.
   - **Step 5–6 (`GIT_COMMIT_PUSH`, `CREATE_PR`):** Tự tạo branch `feature/<ticket>-<jobId>`, push và mở Draft PR trên GitHub.
   - **Step 7 (`REPORT_TICKET`):** Comment báo cáo `Reason / Solution` lên Jira và chuyển status ticket.
