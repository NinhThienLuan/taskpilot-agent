# 01. Kiến trúc tổng thể & Pipeline 7 bước (Java Spring AI)

## 1. Tech Stack chuẩn hóa

- **Ngôn ngữ & Build:** Java 21 (OpenJDK 21), Apache Maven 3.9+
- **Framework:** Spring Boot 3.4.x + Spring AI 1.0.x (GA)
- **LLM Providers (Spring Profiles):**
  - **`gemini` profile:** Google Gemini qua API Key (`GEMINI_API_KEY`, hỗ trợ OpenAI-compatible endpoint của Google AI Studio hoặc Google GenAI starter) — phù hợp cho task nhiều file, cần chất lượng code cao.
  - **`ollama` profile:** Chạy local qua `spring-ai-starter-model-ollama` với small model hỗ trợ tool calling (vd: `qwen2.5-coder:7b`, `qwen3:8b`, `llama3.2`) — phù hợp cho phát triển, thử nghiệm và tối ưu prompt không tốn phí API.

---

## 2. Nguyên tắc thiết kế: Hybrid Workflow + Explicit ReAct Agent

Không để AI tự quyết định toàn bộ quy trình từ đầu đến cuối. Hệ thống tách biệt rõ ràng:
- **Orchestrator (Deterministic Workflow):** Quản lý trạng thái job, clone repo, tạo branch, chạy lệnh verify cuối, commit/push, tạo PR và comment lên ticket.
- **Coding Agent (Explicit ReAct Loop):** Chỉ chạy ở **Bước 3** với nhiệm vụ duy nhất: đọc hiểu codebase trong workspace, sửa code/viết test và lặp lại cho đến khi test pass.

```text
[Trigger: Ticket ID + Optional Prompt]
        │
        ▼
(1) Fetch Ticket ──▶ (2) Setup Sandbox ──▶ (3) AI Coding Loop (Explicit ReAct)
                                                    │
        ┌───────────────────────────────────────────┘
        ▼
(4) Verify Tests ──▶ (5) Git Commit & Push ──▶ (6) Create Draft PR ──▶ (7) Report Ticket
```

### Tại sao dùng Explicit Agent Loop thay vì vòng lặp ngầm của Spring AI?
Mặc định `ChatClient` của Spring AI tự động thực thi `@Tool` bên trong (`internalToolExecutionEnabled = true`). Cách mặc định này không phù hợp cho Coding Agent trong production vì không kiểm soát được số vòng lặp, khó ghi log từng lượt và khó tổng hợp token.

Do đó, bước 3 bắt buộc dùng **Explicit Agent Loop**:
1. Tắt vòng lặp ngầm: `ToolCallingChatOptions.builder().internalToolExecutionEnabled(false)`.
2. Dùng `ToolCallingManager` của Spring AI để tự điều phối từng lượt:
   - Gọi `chatModel.call(prompt)` → ghi nhận `Usage` (`promptTokens`, `generationTokens`, `totalTokens`) từ `ChatResponse.getMetadata().getUsage()`.
   - Nếu `response.hasToolCalls()` → gọi `toolCallingManager.executeToolCalls(prompt, response)`, ghi log step, kiểm tra `iteration < maxIterations` rồi tiếp tục vòng lặp.
   - Nếu không còn tool calls → kết thúc vòng lặp và trả về bản tóm tắt thay đổi.

---

## 3. Chi tiết 7 bước thực thi

| Bước | Tên bước | Loại | Đầu vào | Đầu ra | Xử lý lỗi / Guardrail |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | `FETCH_TICKET` | Deterministic | `ticket_id`, `extra_prompt` | Title, description, acceptance criteria, labels | Fail ngay nếu không tìm thấy ticket hoặc thiếu mô tả |
| **2** | `SETUP_SANDBOX` | Deterministic | `repo_url`, `base_branch` | Workspace cô lập, branch `ai/<ticket>-<job_id>` | Timeout clone; đảm bảo không đụng vào branch `main` |
| **3** | `AI_CODING_LOOP` | **AI Agent** | Ticket context + Repo map + `WorkspaceTools` | Code changes + test cases mới | Giới hạn `max_iterations` (mặc định 15) & `max_tokens` |
| **4** | `RUN_TESTS` | Deterministic | Test command (`mvn test` / `pytest` / `npm test`) | Exit code, `RUN_TEST` log | Cắt gọn log khi đưa vào LLM; lưu full log để debug |
| **5** | `GIT_COMMIT_PUSH` | Deterministic | `git diff` | Commit SHA trên remote branch | Chặn nếu `git diff` rỗng hoặc sửa file nhạy cảm (`.env`, secrets) |
| **6** | `CREATE_PR` | Hybrid (LLM tóm tắt) | `git diff --stat`, test summary | Pull Request URL (mặc định **Draft PR**) | Không bao giờ auto-merge; luôn chờ con người review |
| **7** | `REPORT_TICKET` | Deterministic | PR URL, tóm tắt Reason/Solution | Comment lên Jira/GitHub Issue & chuyển status | Retry nếu API Jira/GitHub lỗi tạm thời |

---

## 4. Mô hình dữ liệu Job & Observability

Mỗi lần chạy (`Job`) cần lưu vết để theo dõi tiến độ và kiểm soát chi phí:

- **`Job`:** `id`, `ticketId`, `extraPrompt`, `provider` (`gemini` | `ollama`), `modelName`, `branchName`, `status` (`PENDING | RUNNING | SUCCEEDED | FAILED`), `prUrl`, `startedAt`, `finishedAt`.
- **`JobStep`:** `jobId`, `stepNumber` (1..7), `stepName`, `status`, `logOutput`, `durationMs`.
- **`TokenUsage`:** `jobId`, `provider`, `model`, `inputTokens`, `cachedInputTokens`, `outputTokens`, `totalTokens`, `iterations`.

> **Lưu ý về chi phí & ngữ cảnh theo Provider:**
> - Với **Gemini**: một task trung bình qua nhiều vòng ReAct có thể tiêu thụ hàng trăm nghìn đến vài triệu tokens. Cần tối ưu prompt và tận dụng context caching để giảm chi phí.
> - Với **Ollama (small model)**: cửa sổ ngữ cảnh (context window) nhỏ hơn (thường 8k–32k tokens) và dễ bị loãng khi output quá dài. Cần giữ tool output ngắn gọn và ưu tiên đọc từng đoạn file nhỏ.
