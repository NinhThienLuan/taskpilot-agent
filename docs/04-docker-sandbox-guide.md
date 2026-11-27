# 04. Hướng dẫn chạy Docker Sandbox (Phase 2)

Phase 2 cô lập toàn bộ các lệnh build/test (`mvn test`, linter, script...) vào trong Docker container riêng cho từng Job mà **không cần sửa bất kỳ dòng code nào của `WorkspaceTools` hay `CodingAgent`**.

## 1. Build Docker Sandbox Image (chỉ cần làm 1 lần)

```bash
docker build -f docker/Dockerfile.sandbox -t taskpilot-sandbox:java21 .
```

*(Tùy chọn)* Tạo volume cache Maven để các lần chạy sau không phải tải lại dependency:
```bash
docker volume create taskpilot-m2-cache
```

## 2. Chạy TaskPilot ở chế độ Docker Sandbox

Chỉ cần thêm `--taskpilot.sandbox.mode=docker` (có thể kết hợp với cả profile `ollama` hoặc `gemini`):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=ollama \
  -Dspring-boot.run.arguments="--taskpilot.run.enabled=true \
    --taskpilot.sandbox.mode=docker \
    --taskpilot.sandbox.maven-cache-volume=taskpilot-m2-cache \
    --taskpilot.run.repo=./samples/demo-calc-repo \
    --taskpilot.run.task='Add divide(int a, int b) with zero check and unit tests'"
```

## 3. Cơ chế an toàn đã áp dụng
- **Non-root user (`sandbox`):** Container không chạy bằng `root`.
- **Giới hạn tài nguyên mặc định:** `--cpus 2.0`, `--memory 2g`, `--pids-limit 256` (chống fork bomb / OOM).
- **Cách ly secrets:** API key (`GEMINI_API_KEY`) chỉ nằm ở tiến trình host JVM, không truyền vào container.
- **Tự động dọn dẹp:** `LocalPipelineRunner` mở session trong khối `try-with-resources`; khi job kết thúc (dù thành công hay lỗi), `docker rm -f <container>` luôn được gọi tự động.
