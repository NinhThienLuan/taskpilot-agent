# TaskPilot Agent

Hệ thống AI Coding Agent viết bằng **Java 21 + Spring Boot + Spring AI**, tự động hóa quy trình xử lý ticket phần mềm:
**Ticket (Jira / GitHub Issue) → Sandbox → AI Coding Loop → Auto Test → Self-Correction → Pull Request → Ticket Report**.

## Tech Stack & LLM Providers

- **Runtime & Framework:** Java 21, Maven, Spring Boot 3.4.x, Spring AI 1.0.x
- **Dual LLM Profiles (chuyển đổi qua Spring Profile):**
  - `gemini`: Google Gemini (dùng cho task thực tế cần suy luận & tool calling mạnh).
  - `ollama`: Local small model qua Ollama (vd `qwen2.5-coder:7b`, `qwen3:8b` — dùng để dev/test local miễn phí).

## Mục tiêu & Lộ trình (`1 -> 3 -> 2`)

Dự án được triển khai theo thứ tự ưu tiên từ thiết kế cốt lõi đến đóng gói vận hành:

1. **Phase 1 — Architecture & Specs (`docs/`)**: Định nghĩa kiến trúc 7 bước, Explicit ReAct Loop trong Spring AI, đặc tả bộ `@Tool`, abstraction `CommandExecutor` và cấu trúc Maven.
2. **Phase 3 — MVP Coding Agent**: Xây dựng lõi ReAct Agent chạy trên repo local với `LocalProcessCommandExecutor`, hỗ trợ 2 profile `gemini` / `ollama` và bộ Unit Test dùng Mock `ChatModel`.
3. **Phase 2 — Isolated Docker Sandbox**: Thêm `DockerSandboxCommandExecutor` chạy code & test trong container cô lập (giới hạn CPU/RAM, tự dọn dẹp sau mỗi job) mà không cần sửa code Agent/Tools.

## Ánh xạ theo 5 lớp kiến trúc AI

| Lớp | Thành phần trong TaskPilot |
| :--- | :--- |
| **1. Models** | Spring AI `ChatModel` với 2 profile: **Gemini** (cloud) & **Ollama** (local small model) |
| **2. Knowledge & Context** | Ticket context, Repo map, coding conventions (`AGENTS.md`), Prompt Caching |
| **3. Agency & Workflows** | Hybrid: Pipeline 7 bước cố định + Explicit ReAct Loop (`ToolCallingManager`) ở bước 3 |
| **4. Adaptation & Serving** | Prompt Caching (Gemini context caching) & tối ưu context cho small model (Ollama) |
| **5. Harness & LLMOps** | `CommandExecutor` (Local → Docker), `max_iterations`, chống sửa test cũ, `TokenUsage` & Step Tracing |

## Tài liệu chi tiết

- [01 - Kiến trúc & Pipeline 7 bước](docs/01-architecture-and-pipeline.md)
- [02 - Đặc tả bộ Tools & Agent Harness](docs/02-agent-tools-spec.md)
- [03 - Kế hoạch triển khai MVP & Docker Sandbox](docs/03-mvp-and-sandbox-plan.md)
