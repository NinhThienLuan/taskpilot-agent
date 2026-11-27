# 03. Kế hoạch triển khai theo lộ trình `1 -> 3 -> 2`

## Phase 1: Tài liệu & Quy ước Git (Hiện tại)
- Thiết lập repository riêng tư (`taskpilot-agent`) quản lý phiên bản theo branch `main` + feature branches (`feat/...`, `docs/...`).
- Hoàn thiện bộ tài liệu:
  - `docs/01-architecture-and-pipeline.md`
  - `docs/02-agent-tools-spec.md`
  - `docs/03-mvp-and-sandbox-plan.md`

---

## Phase 3: Xây dựng MVP Coding Agent (Ưu tiên tiếp theo)
Mục tiêu: Chạy được vòng lặp `AI Coding Loop` trên một repo mẫu local mà chưa cần Jira UI hay RabbitMQ.

### Các module cần xây trong MVP:
1. `config` — Đọc API key, model name, `max_iterations`, đường dẫn repo mục tiêu.
2. `tools` — Cài đặt 6 tools (`list_files`, `read_file`, `search_grep`, `edit_file`, `write_file`, `run_command`) có kiểm tra path traversal (chỉ cho phép thao tác bên trong `WORKDIR`).
3. `agent` — Vòng lặp ReAct gọi LLM (hỗ trợ tool calling) + đếm token usage (`input`, `cached`, `output`).
4. `cli / runner` — Nhận `task_description` + `repo_path`, tạo branch mới, chạy Agent, chạy lại test xác nhận, in `git diff --stat` và tổng token tiêu thụ.

### Tiêu chí nghiệm thu Phase 3:
- Giao 1 task nhỏ (vd: thêm 1 endpoint/hàm + unit test) vào 1 repo mẫu.
- Agent tự đọc code, tự sửa, tự chạy test; nếu cố tình làm fail test ban đầu, Agent đọc được lỗi và tự sửa để test pass.
- Ghi nhận được log từng bước và tổng số token đã dùng.

---

## Phase 2: Đóng gói Docker Sandbox an toàn
Mục tiêu: Tách toàn bộ quá trình chạy `AI Coding Loop` và `RUN_TESTS` vào container cô lập.

### Các thành phần cần làm trong Phase 2:
1. `Dockerfile.sandbox` — Image chứa sẵn toolchain (JDK/Maven hoặc Python/Node), chạy bằng user non-root.
2. `SandboxManager` — Tạo container theo `job_id`, giới hạn CPU/RAM (`--cpus`, `--memory`), giới hạn network khi không cần thiết, mount/copy repo vào `/workspace`.
3. **Auto Cleanup** — Thu thập `git diff` / patch từ container xong là `docker rm -f` tự động dọn dẹp.
4. *(Mở rộng sau Phase 2)*: Nối GitHub PR API, Jira API, Queue (RabbitMQ/Redis) và Web Dashboard theo dõi 7 bước.
