# TaskPilot Agent

Hệ thống AI Coding Agent tự động hóa quy trình xử lý ticket phần mềm:
**Ticket (Jira / GitHub Issue) → Sandbox → AI Coding Loop → Auto Test → Self-Correction → Pull Request → Ticket Report**.

## Mục tiêu & Lộ trình (`1 -> 3 -> 2`)

Dự án được triển khai theo thứ tự ưu tiên từ thiết kế cốt lõi đến đóng gói vận hành:

1. **Phase 1 — Architecture & Specs (`docs/`)**: Định nghĩa kiến trúc 7 bước, đặc tả bộ Tools cho `AI Coding Loop`, cơ chế kiểm soát (Harness/Guardrails) và tối ưu chi phí token.
2. **Phase 3 — MVP Coding Agent**: Xây dựng vòng lặp ReAct tối giản chạy trên repo local (đọc repo → sửa code → chạy test → tự sửa lỗi → xuất diff).
3. **Phase 2 — Isolated Docker Sandbox**: Đóng gói bước thực thi code & test vào Docker container cô lập (giới hạn tài nguyên, tự dọn dẹp sau mỗi job).

## Ánh xạ theo 5 lớp kiến trúc AI

| Lớp | Thành phần trong TaskPilot |
| :--- | :--- |
| **1. Models** | LLM hỗ trợ Tool Calling mạnh (Claude / Gemini / GPT) |
| **2. Knowledge & Context** | Ticket context, Repo map, coding conventions (`AGENTS.md`), Prompt Caching |
| **3. Agency & Workflows** | Hybrid: Pipeline 7 bước cố định + ReAct Loop ở bước 3 (`AI Coding Loop`) |
| **4. Adaptation & Serving** | Prompt Caching (giảm 80–90% chi phí input token qua các vòng lặp) |
| **5. Harness & LLMOps** | Docker Sandbox, `max_iterations`, chống sửa test cũ, Token Usage & Step Tracing |

## Tài liệu chi tiết

- [01 - Kiến trúc & Pipeline 7 bước](docs/01-architecture-and-pipeline.md)
- [02 - Đặc tả bộ Tools & Agent Harness](docs/02-agent-tools-spec.md)
- [03 - Kế hoạch triển khai MVP & Docker Sandbox](docs/03-mvp-and-sandbox-plan.md)
