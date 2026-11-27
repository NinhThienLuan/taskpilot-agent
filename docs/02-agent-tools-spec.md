# 02. Đặc tả bộ Tools & Agent Harness (Spring AI)

## 1. Thiết kế `@Tool` theo Job & Abstraction `CommandExecutor`

### Cô lập trạng thái theo từng Job (`WorkspaceTools`)
Không khai báo các tool đọc/ghi file dưới dạng Spring `@Component` singleton dùng chung 1 biến `workspacePath`, vì chạy nhiều job đồng thời sẽ gây xung đột dữ liệu.
Thay vào đó:
- Tạo lớp **`WorkspaceTools`** được khởi tạo mới cho mỗi Job: `new WorkspaceTools(workspacePath, commandExecutor, harnessPolicy)`.
- Khai báo các phương thức bằng annotation chuẩn của Spring AI: `@Tool(description = "...")` và `@ToolParam(description = "...")`.
- Đăng ký động vào `ToolCallingChatOptions` (qua `ToolCallbacks.from(workspaceTools)`) ở mỗi lượt chạy Job.

### Tách interface `CommandExecutor` (cầu nối Phase 3 → Phase 2)
Mọi lệnh shell (`mvn test`, `git diff`, v.v.) không gọi trực tiếp `ProcessBuilder` trong `WorkspaceTools`, mà đi qua interface:

```java
public interface CommandExecutor {
    CommandResult execute(Path workspacePath, String command, Duration timeout);
}
```
- **Phase 3 (Local MVP):** `LocalProcessCommandExecutor` chạy qua `ProcessBuilder`, lọc sạch biến môi trường nhạy cảm (`GEMINI_API_KEY`, `GITHUB_TOKEN`, `JIRA_TOKEN`, ...) và áp đặt timeout cứng.
- **Phase 2 (Docker Sandbox):** `DockerSandboxCommandExecutor` chạy qua `docker exec` vào container cô lập của Job. Toàn bộ `WorkspaceTools` và `CodingAgent` **giữ nguyên không cần sửa đổi**.

---

## 2. Danh sách 7 Tools chuẩn cho `WorkspaceTools`

| Tool | Mục đích | Tham số chính | Quy tắc an toàn & tối ưu (Harness) |
| :--- | :--- | :--- | :--- |
| `list_files` | Xem cấu trúc thư mục | `relativePath`, `maxDepth` | Chặn path traversal (`..`); bỏ qua `.git/`, `target/`, `node_modules/`, `.venv/` |
| `read_file` | Đọc nội dung file theo khoảng dòng | `relativePath`, `startLine`, `endLine` | Giới hạn mặc định **200 dòng/lần** (an toàn cho cả Ollama small model) |
| `search_grep` | Tìm chuỗi/regex trong codebase | `pattern`, `relativePath` | Giới hạn tối đa **30–50 kết quả** trả về |
| `edit_file` | Sửa chính xác một đoạn trong file | `relativePath`, `oldText`, `newText` | Khớp duy nhất 1 vị trí `oldText`; báo lỗi rõ nếu không khớp hoặc khớp nhiều chỗ |
| `write_file` | Tạo file mới hoặc ghi đè file | `relativePath`, `content` | Chặn ghi vào file nhạy cảm (`.env`, `.git/**`, CI secrets) |
| `run_command` | Chạy lệnh build, test, linter | `command`, `timeoutSec` | Chạy qua `CommandExecutor`; timeout mặc định 90s; cắt gọn chỉ giữ **80–100 dòng cuối** |
| `git_status_diff` | Xem `git status` và `git diff` hiện tại | `statOnly` (boolean) | Read-only; giúp Agent tự rà soát thay đổi trước khi kết thúc vòng lặp |

---

## 3. Vòng lặp Explicit ReAct & Self-Correction

```text
System Prompt + Ticket + Extra Prompt + Initial Repo Tree
        │
        ▼
  ┌─▶ [ChatModel.call(prompt)]  ──▶ Cộng dồn TokenUsage (input/output)
  │         │
  │         ├── Không có ToolCall ──▶ Kết thúc vòng lặp (Final Summary)
  │         ▼
  │   [ToolCallingManager.executeToolCalls()]
  │         │  (Path guard + Secret filter + Output truncation)
  │         ▼
  └── [Cập nhật Prompt History, kiểm tra iteration < maxIterations]
        │
        ▼
  [Orchestrator chạy lại Full Test Suite độc lập (Bước 4)]
```

### Quy tắc Harness bắt buộc:
1. **Giới hạn vòng lặp (`maxIterations = 15`, cấu hình được theo profile):** Tránh lặp vô tận, đặc biệt quan trọng khi chạy Ollama small model.
2. **Path Traversal Guard:** Mọi `relativePath` đều phải chuẩn hóa (`workspaceRoot.resolve(path).normalize()`) và kiểm tra `resolved.startsWith(workspaceRoot)`.
3. **Output Truncation (Tối ưu cho Gemini & Ollama):**
   - Output dài từ `run_command` hay `git_status_diff` bị cắt bớt kèm thông báo `[... truncated N lines ...]` để không làm tràn context window của small model và không đội chi phí token của Gemini.
4. **Chống Reward Hacking (Test pass ảo):**
   - Prompt và Harness yêu cầu Agent không được xóa hoặc làm yếu các test case hiện có để ép test pass.
   - Bước 4 (`RUN_TESTS`) chạy độc lập ngoài prompt của Agent để xác nhận kết quả thật.
5. **Cách ly Secrets:**
   - API keys & Git/Jira tokens bị xóa khỏi environment của subprocess khi thực thi `run_command`.

### Lưu ý riêng khi chạy Ollama (Small Model)
- Mô tả `@Tool` và `@ToolParam` phải **ngắn gọn, tường minh**, tránh các tham số lồng nhau phức tạp (chỉ dùng kiểu cơ bản `String`, `Integer`, `Boolean`).
- Khi tool trả lỗi (ví dụ `oldText` không khớp trong `edit_file`), thông báo lỗi cần hướng dẫn cụ thể bước sửa tiếp theo (vd: *"oldText not found. Call read_file to inspect exact lines before retrying."*).
