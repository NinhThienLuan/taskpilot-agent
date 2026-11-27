# 02. Đặc tả bộ Tools & Agent Harness (Bước 3: AI Coding Loop)

## 1. Danh sách Tools tối thiểu cho Agent

Agent chỉ được tương tác với codebase thông qua 6 tools chuẩn hóa bên trong thư mục sandbox (`WORKDIR`):

| Tool | Mục đích | Tham số chính | Quy tắc an toàn (Harness) |
| :--- | :--- | :--- | :--- |
| `list_files` | Xem cấu trúc thư mục | `path`, `max_depth` | Bỏ qua `.git/`, `node_modules/`, `target/`, `.venv/` |
| `read_file` | Đọc nội dung file theo dòng | `path`, `start_line`, `end_line` | Giới hạn tối đa 300–500 dòng/lần để tránh tràn context |
| `search_grep` | Tìm chuỗi/regex trong codebase | `pattern`, `path`, `include` | Giới hạn tối đa 50 kết quả trả về |
| `edit_file` | Sửa chính xác một đoạn trong file | `path`, `old_text`, `new_text` | Khớp chính xác `old_text`; ưu tiên sửa nhỏ thay vì ghi đè |
| `write_file` | Tạo file mới hoặc ghi file | `path`, `content` | Chặn ghi đè các file cấu hình nhạy cảm / lockfile ngoài phạm vi |
| `run_command` | Chạy lệnh build, test, linter | `command`, `timeout_sec` | Chạy trong sandbox, timeout mặc định 60–120s, cắt gọn log > 150 dòng cuối |

---

## 2. Vòng lặp ReAct & Cơ chế Self-Correction

```text
System Prompt + Ticket + Extra Prompt + Repo Map
        │
        ▼
  ┌─▶ [LLM suy luận & gọi Tool]
  │         │
  │         ▼
  │   [Harness kiểm tra & thực thi Tool]
  │         │
  │         ▼
  └── [Trả kết quả Tool / Log lỗi test vào History]
        │ (Lặp cho tới khi LLM báo xong hoặc chạm max_iterations)
        ▼
  [Harness chạy lại Full Test Suite để xác nhận độc lập]
```

### Quy tắc Harness bắt buộc:
1. **Giới hạn vòng lặp (`max_iterations = 15`):** Tránh Agent lặp vô tận khi gặp lỗi khó.
2. **Truncate Tool Output:** Log Maven/Gradle/Pytest dài hàng nghìn dòng phải được rút gọn (giữ phần tóm tắt lỗi và 80–120 dòng stack trace cuối).
3. **Chống Reward Hacking (Test pass ảo):**
   - Không cho Agent xóa hoặc nới lỏng các test case hiện có chỉ để làm test pass.
   - Bước 4 (`RUN_TESTS`) chạy độc lập ngoài prompt của Agent để xác nhận kết quả thật.
4. **Cách ly Token & Secrets:**
   - GitHub Token, Jira Token, API Key của hệ thống **không** được đưa vào môi trường mà `run_command` có thể đọc.
   - Bước 5, 6, 7 do lớp Orchestrator bên ngoài thực hiện bằng quyền riêng.
