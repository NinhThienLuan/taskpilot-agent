# 05. Kiến trúc Async Platform (RabbitMQ, PostgreSQL, REST API & Integrations)

## 1. Luồng xử lý bất đồng bộ (Claim-Check Pattern)

1. Client/Dashboard gọi `POST /api/jobs`.
2. `JobController` lưu bản ghi `JobRunEntity` (`PENDING`) và khởi tạo sẵn 7 `JobStepEntity` (`PENDING`) vào **PostgreSQL**.
3. `JobDispatcher` (`RabbitMqJobDispatcher`) chỉ bắn **tín hiệu nhẹ `jobId`** vào queue `taskpilot.jobs.queue` và trả về `202 Accepted` ngay lập tức (~20ms).
4. `@RabbitListener` nhận `jobId`, đọc đầy đủ trạng thái từ PostgreSQL, thực thi `AsyncJobPipelineService.processJob(jobId)` qua 7 bước và ghi tiến độ/log/`TokenUsage` ngược vào DB sau mỗi bước.
5. Web Dashboard (`GET /`) tự động polling `GET /api/jobs/{id}` mỗi 2 giây để cập nhật tiến độ thời gian thực.

---

## 2. Danh sách REST API Endpoints

| Method | Endpoint | Mô tả |
| :--- | :--- | :--- |
| `POST` | `/api/jobs` | Tạo job mới, khởi tạo 7 steps, đẩy vào queue (`202 Accepted`) |
| `GET` | `/api/jobs` | Lấy danh sách 20 job gần nhất |
| `GET` | `/api/jobs/{id}` | Xem chi tiết job: `status`, `branchName`, `prUrl`, `tokenUsage`, `gitDiffStat`, `summary`, và mảng 7 `steps` |
| `GET` | `/api/health` | Kiểm tra trạng thái hệ thống: active profiles, `sandboxMode`, `queueMode`, `githubEnabled`, `jiraEnabled` |

### Ví dụ payload `POST /api/jobs`
```json
{
  "ticketId": "JB-4",
  "taskDescription": "Add divide(int a, int b) with zero check and unit tests",
  "extraPrompt": "Keep changes minimal and cover division by zero",
  "repoPath": "./samples/demo-calc-repo",
  "testCommand": "mvn test"
}
```
*(Khi bật Jira integration `JIRA_INTEGRATION_ENABLED=true`, có thể bỏ trống `taskDescription` để Step 1 tự động fetch từ Jira.)*

---

## 3. Kích hoạt tích hợp GitHub PR & Jira

### GitHub Draft Pull Request (Step 5 & 6)
```powershell
$env:GITHUB_INTEGRATION_ENABLED="true"
$env:GITHUB_TOKEN="ghp_..."
$env:GITHUB_OWNER="NinhThienLuan"
$env:GITHUB_REPO="your-target-repo"
$env:GITHUB_BASE_BRANCH="main"
```
- **Guardrail:** Mặc định `draft-pr: true` và không bao giờ tự merge PR; con người luôn review trước khi merge.

### Jira Cloud REST API v3 (Step 1 & 7)
```powershell
$env:JIRA_INTEGRATION_ENABLED="true"
$env:JIRA_BASE_URL="https://your-domain.atlassian.net"
$env:JIRA_EMAIL="you@example.com"
$env:JIRA_API_TOKEN="your-jira-api-token"
$env:JIRA_TRANSITION_STATUS="In Review"
```
- Step 1 tự đọc `summary` và `description` (chuyển đổi từ Atlassian Document Format sang plain text).
- Step 7 tự đăng comment theo cấu trúc `1. Reason / 2. Solution / 3. Pull Request` và chuyển status ticket sang `In Review`.
