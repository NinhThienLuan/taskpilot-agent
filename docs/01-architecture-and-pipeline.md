# 01. Kiến trúc tổng thể & Pipeline 7 bước

## 1. Nguyên tắc thiết kế: Hybrid Workflow + Agent

Không để AI tự quyết định toàn bộ quy trình từ đầu đến cuối. Hệ thống tách biệt:
- **Orchestrator (Deterministic Workflow):** Quản lý trạng thái job, clone repo, tạo branch, chạy lệnh verify cuối, commit/push, tạo PR và comment lên ticket.
- **Coding Agent (ReAct Loop):** Chỉ chạy ở **Bước 3** với nhiệm vụ duy nhất: đọc hiểu codebase trong sandbox, sửa code/viết test và lặp lại cho đến khi test pass.

```text
[Trigger: Ticket ID + Optional Prompt]
        │
        ▼
(1) Fetch Ticket ──▶ (2) Setup Sandbox ──▶ (3) AI Coding Loop (ReAct)
                                                    │
        ┌───────────────────────────────────────────┘
        ▼
(4) Verify Tests ──▶ (5) Git Commit & Push ──▶ (6) Create Draft PR ──▶ (7) Report Ticket
```

---

## 2. Chi tiết 7 bước thực thi

| Bước | Tên bước | Loại | Đầu vào | Đầu ra | Xử lý lỗi / Guardrail |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | `FETCH_TICKET` | Deterministic | `ticket_id`, `extra_prompt` | Title, description, acceptance criteria, labels | Fail ngay nếu không tìm thấy ticket hoặc thiếu mô tả |
| **2** | `SETUP_SANDBOX` | Deterministic | `repo_url`, `base_branch` | Workspace cô lập, branch `ai/<ticket>-<job_id>` | Timeout clone; đảm bảo không đụng vào branch `main` |
| **3** | `AI_CODING_LOOP` | **AI Agent** | Ticket context + Repo map + Tools | Code changes + test cases mới | Giới hạn `max_iterations` (vd 15 vòng) & `max_tokens` |
| **4** | `RUN_TESTS` | Deterministic | Test command (`mvn test` / `pytest` / `npm test`) | Exit code, `RUN_TEST` log | Cắt gọn log nếu quá dài; lưu full log để debug |
| **5** | `GIT_COMMIT_PUSH` | Deterministic | `git diff` | Commit SHA trên remote branch | Chặn nếu `git diff` rỗng hoặc sửa file nhạy cảm (`.env`, CI secrets) |
| **6** | `CREATE_PR` | Hybrid (LLM tóm tắt) | `git diff --stat`, test summary | Pull Request URL (mặc định **Draft PR**) | Không bao giờ auto-merge; luôn chờ con người review |
| **7** | `REPORT_TICKET` | Deterministic | PR URL, tóm tắt Reason/Solution | Comment lên Jira/GitHub Issue & chuyển status | Retry nếu API Jira/GitHub lỗi tạm thời |

---

## 3. Mô hình dữ liệu Job & Observability

Mỗi lần chạy (`Job`) cần lưu vết để theo dõi trên dashboard và kiểm soát chi phí:

- **`Job`:** `id`, `ticket_id`, `extra_prompt`, `branch_name`, `status` (`PENDING | RUNNING | SUCCEEDED | FAILED`), `pr_url`, `started_at`, `finished_at`.
- **`JobStep`:** `job_id`, `step_number` (1..7), `step_name`, `status`, `log_output`, `duration_ms`.
- **`TokenUsage`:** `job_id`, `model`, `input_tokens`, `cached_input_tokens`, `output_tokens`, `estimated_cost_usd`.

> **Lưu ý về chi phí:** Một task trung bình (sửa 10–17 files) có thể tiêu thụ ~1.5M–2.5M tokens qua nhiều vòng ReAct. Bắt buộc bật **Prompt Caching** cho `system_prompt` và `tool_definitions` để giảm 80–90% chi phí input token.
