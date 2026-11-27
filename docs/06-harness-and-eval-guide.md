# 06. Agent Harness Guardrails & Eval Harness Guide (Phase 5)

## 1. Agent Harness Guardrails (Bảo vệ chủ động khi chạy)

| Lớp bảo vệ | Class | Tác dụng |
| :--- | :--- | :--- |
| **Token Budget Cutoff** | `CodingAgent` | Ngắt vòng lặp ngay (`TOKEN_BUDGET_EXCEEDED`) khi tổng token vượt `taskpilot.agent.max-total-tokens` (mặc định `500,000`) |
| **Stuck Loop Detector** | `CodingAgent` | Ngắt sớm (`STUCK_LOOP`) nếu Agent gọi lặp lại đúng 1 tool + tham số `max-consecutive-duplicate-tool-calls` lần liên tiếp (mặc định `3`) |
| **Command Denylist** | `CommandGuard` | Chặn lệnh nguy hiểm (`rm -rf /`, `sudo`, `git push --force`, `curl \| bash`...) trước khi chạy trong `run_command` |
| **Anti-Reward-Hacking** | `AntiRewardHackingChecker` | Quét `git diff HEAD` ở Step 4 để phát hiện và đánh fail (`REWARD_HACKING`) nếu Agent xóa `@Test` hoặc làm yếu `assert*` trong file test cũ |

---

## 2. Eval Harness (Đo lường định lượng Ollama vs Gemini)

Bộ benchmark mặc định nằm tại `eval/cases/cases.json` gồm 5 cases (`EVAL-01`..`EVAL-05`, trải đều `EASY`, `MEDIUM`, `HARD`) kèm **Hidden Grading Tests** tại `eval/hidden-tests/EVAL-01..05/` (chỉ được chép vào workspace **sau khi** Agent đã hoàn tất).

### Chạy toàn bộ 5 benchmark cases với Ollama (local)
```powershell
mvn package -DskipTests
java -jar target/taskpilot-agent-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=ollama `
  --spring.main.web-application-type=none `
  --taskpilot.queue.mode=in-memory `
  --taskpilot.eval.enabled=true
```

### Chạy 1 case cụ thể với Gemini
```powershell
$env:GEMINI_API_KEY="AIzaSy..."
java -jar target/taskpilot-agent-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=gemini `
  --spring.main.web-application-type=none `
  --taskpilot.queue.mode=in-memory `
  --taskpilot.eval.enabled=true `
  --taskpilot.eval.case-id=EVAL-01
```

### Kết quả báo cáo (`eval-reports/`)
Sau khi chạy xong, Eval Harness tự tạo 2 file trong thư mục `eval-reports/`:
- `eval-report-<profile>-<timestamp>.md` — Bảng tổng hợp `Pass@1 Rate`, phân tách theo độ khó, trạng thái 3 lớp chấm điểm (`Reg / Hygiene / Hidden`), số vòng lặp, thời gian và tổng token.
- `eval-report-<profile>-<timestamp>.json` — Dữ liệu cấu trúc phục vụ so sánh regression giữa các phiên bản prompt/model.
