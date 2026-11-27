# 02. Đặc tả 7 `@Tool`, Agent Harness & `CommandExecutor`

## 1. Cô lập trạng thái theo từng Job (`WorkspaceTools`)

Lớp [`WorkspaceTools`](../src/main/java/com/taskpilot/agent/tools/WorkspaceTools.java) **không** phải Spring Singleton Bean. Mỗi khi `CodingAgent.runCodingLoop(request)` chạy, một instance mới được khởi tạo với `workspacePath` riêng của Job:

```java
WorkspaceTools workspaceTools = new WorkspaceTools(request.repoPath(), commandExecutor, properties);
ToolCallback[] toolCallbacks = ToolCallbacks.from(workspaceTools);
```

Cách này đảm bảo nhiều Job chạy song song không bao giờ ghi đè workspace của nhau.

---

## 2. Danh sách 7 Tools chuẩn

| Tool | Tham số | Giới hạn & Bảo vệ (Harness) |
| :--- | :--- | :--- |
| `list_files` | `relativePath`, `maxDepth` | Bỏ qua `.git`, `target`, `build`, `node_modules`, `.venv`, `.idea`, `.gradle`; tối đa 200 entries |
| `read_file` | `relativePath`, `startLine`, `endLine` | Trả kèm số dòng 1-based; giới hạn tối đa `max-read-lines` dòng/lần (`150` cho Ollama, `250` cho Gemini) |
| `search_grep` | `pattern`, `relativePath` | Bỏ qua file > 1MB và thư mục ignore; tối đa `max-grep-matches` (mặc định 40) kết quả |
| `edit_file` | `relativePath`, `oldText`, `newText` | Bắt buộc `oldText` khớp **duy nhất 1 vị trí**; trả hướng dẫn rõ khi không khớp để small model tự sửa |
| `write_file` | `relativePath`, `content` | Tự tạo thư mục cha; đi qua `PathGuard.resolveForWrite(...)` |
| `run_command` | `command`, `timeoutSec` | Chạy qua `CommandExecutor`; timeout mặc định 90s (tối đa 300s); chỉ giữ `max-command-output-lines` dòng cuối |
| `git_status_diff` | `statOnly` | Read-only; giúp Agent tự kiểm tra `git status` / `git diff` trước khi hoàn tất |

---

## 3. Lớp bảo vệ Harness

### A. `PathGuard` (Chống thoát workspace & sửa file nhạy cảm)
- Chặn đường dẫn tuyệt đối (`/etc/passwd`, `C:\...`).
- Chặn path traversal (`../`) bằng chuẩn hóa `workspaceRoot.resolve(candidate).normalize().startsWith(workspaceRoot)`.
- Chặn đọc/ghi `.git/**` và chặn ghi `.env` / `.env.*`.

### B. Tách interface `CommandExecutor` & `SandboxLifecycle`
- **Chế độ `local` (mặc định):** [`LocalProcessCommandExecutor`](../src/main/java/com/taskpilot/agent/executor/LocalProcessCommandExecutor.java) chạy qua `ProcessBuilder`, xóa toàn bộ biến môi trường chứa `API_KEY`, `SECRET`, `TOKEN`, `PASSWORD`, `CREDENTIAL`, `PRIVATE_KEY` trước khi thực thi.
- **Chế độ `docker`:** [`DockerSandboxCommandExecutor`](../src/main/java/com/taskpilot/agent/executor/docker/DockerSandboxCommandExecutor.java) chạy toàn bộ lệnh qua `docker exec` trong container non-root có trần CPU/RAM/PIDs và tự gọi `docker rm -f` khi đóng session.

### C. Chống Reward Hacking & tối ưu cho Ollama Small Model
- System prompt cấm tuyệt đối việc xóa hoặc làm yếu test cũ để ép test pass.
- **Bước 4 (`RUN_TESTS`)** chạy độc lập ngoài vòng lặp AI để xác nhận kết quả thực.
- Profile `ollama` siết `max-iterations=12`, `max-read-lines=150`, `max-command-output-lines=70`, `num-ctx=16384` để tránh tràn context window của các model 7B–14B.
