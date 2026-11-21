# Kế hoạch: Termux shell có Colab CLI trực tiếp

## Phạm vi

Màn hình launcher dùng Termux `TerminalView` và PTY Android shell; gõ `colab ...` ngay tại prompt sẽ gọi Python/Colab CLI nhúng. CLI riêng hiện tại giữ làm lối phụ. Không thêm rootfs, root hoặc quyền Android.

## Các bước triển khai

1. Viết test trước cho shell environment và wire protocol của helper; xác nhận test đỏ do phần triển khai còn thiếu.
2. Thêm Rust `colab` client binary (ARM64 Android) dùng token, port loopback ngẫu nhiên, argv framing, stdin/stdout relay và exit code; test framing trên host. Cấu hình Gradle chép binary vào assets APK.
3. Tạo `ColabBridgeServer` Java: loopback-only, token 256-bit, giới hạn request, xác thực, gọi parser JNI hiện có, rồi gọi Chaquopy runner tuần tự; relay output/prompt/line input và exit code qua protocol.
4. Mở rộng Python runner để nhận `colab` normalized args từ helper, giữ nguyên entrypoint cũ của màn hình CLI.
5. Hoàn thiện Activity shell dùng Termux TerminalView, cài helper vào `files/bin`, khởi chạy server trước PTY, đưa path/port/token qua environment con, dọn server/session khi Activity kết thúc; thêm nút vào màn hình CLI cũ.
6. Cập nhật launcher manifest, notices/license và README; không xin thêm permission.
7. Chạy Java unit test, Rust format/test và Gradle test/assemble. Kiểm tra APK có `TerminalView`, `libtermux.so`, Rust JNI, executable bridge; kiểm tra manifest/permission và rà soát tệp đã đổi.
8. Nếu không có thiết bị ADB, báo giới hạn rõ: chưa chứng minh PTY, file execute và Colab bridge chạy trên máy thật.

## Kiểm thử

```bash
PATH="/home/ubuntu/.local/share/uv/python/cpython-3.13.15-linux-x86_64-gnu/bin:$PATH" ./gradlew :app:testDebugUnitTest :app:assembleDebug
cd native && cargo fmt -- --check && cargo test
```

Kiểm tra APK bằng `unzip -l app/build/outputs/apk/debug/app-debug.apk`; xác nhận không có quyền mới trong manifest đã merge. Không gọi Google/OAuth hay tạo phiên Colab khi test/build.
