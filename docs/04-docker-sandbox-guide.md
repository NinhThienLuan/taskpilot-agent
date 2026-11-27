# 04. Hướng dẫn vận hành Docker Sandbox

Chế độ `docker` cô lập toàn bộ các lệnh shell (`mvn test`, build, linter...) vào một Docker container non-root riêng cho từng Job mà không thay đổi bất kỳ logic nào của `WorkspaceTools` hay `CodingAgent`.

## 1. Build Docker Sandbox Image (1 lần duy nhất)

```bash
docker build -f docker/Dockerfile.sandbox -t taskpilot-sandbox:java21 .
```

*(Khuyên dùng)* Tạo named volume để cache thư mục `.m2` giữa các lần chạy:
```bash
docker volume create taskpilot-m2-cache
```

## 2. Chạy ứng dụng với Docker Sandbox

**Chạy Web Server + Dashboard + Docker Sandbox:**
```powershell
docker compose up -d
mvn spring-boot:run "-Dspring-boot.run.arguments=--taskpilot.sandbox.mode=docker --taskpilot.sandbox.maven-cache-volume=taskpilot-m2-cache"
```

**Chạy CLI + Docker Sandbox:**
```powershell
java -jar target/taskpilot-agent-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=ollama `
  --taskpilot.sandbox.mode=docker `
  --taskpilot.sandbox.maven-cache-volume=taskpilot-m2-cache `
  --taskpilot.run.enabled=true `
  --taskpilot.run.repo=./samples/demo-calc-repo `
  --taskpilot.run.task="Add divide(int a, int b) with zero check and unit tests"
```

## 3. Tham số giới hạn tài nguyên (`application.yml`)

```yaml
taskpilot:
  sandbox:
    mode: docker
    image: taskpilot-sandbox:java21
    cpus: "2.0"
    memory: "2g"
    pids-limit: 256
    network-mode: bridge     # Đổi thành "none" nếu dependencies đã có sẵn trong m2 cache
    maven-cache-volume: "taskpilot-m2-cache"
```

## 4. Cơ chế an toàn & tự dọn dẹp
- Chạy dưới user non-root `sandbox` (khai báo trong `docker/Dockerfile.sandbox`).
- Secrets (`GEMINI_API_KEY`, `GITHUB_TOKEN`, `JIRA_API_TOKEN`) chỉ nằm ở host JVM, không đưa vào container.
- Container được mở trong khối `try-with-resources` bởi `SandboxLifecycle.openSession(...)` và **luôn gọi `docker rm -f <container>`** khi job hoàn tất hoặc gặp lỗi.
