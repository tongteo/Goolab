# Kế hoạch: ColabTerm Android MVP

## Thứ tự triển khai

1. Khởi tạo Gradle Android app riêng tại `app/`, cấu hình API 35/minSdk 24/ARM64, Gradle wrapper và Chaquopy Python 3.13.
2. Tạo Rust crate `native/` với tokenizer shell-like và allowlist lệnh Colab; kiểm thử quoting, lỗi parse, từ chối shell commands.
3. Thêm JNI adapter và Gradle task dùng `cargo-ndk` để đặt `.so` vào `app/src/main/jniLibs/arm64-v8a/`.
4. Thêm Android Activity giao diện terminal, lịch sử, output cuộn, file picker và input bridge.
5. Đóng gói source fork Android `google-colab-cli` 0.7.2.post0+android5.3 cùng license; cài các dependency Python thuần tương thích Android thay vì cài upstream CLI 0.8.0 từ PyPI.
6. Thêm Python runner gọi Typer/Click và chuyển stdout/stderr/input an toàn; chặn command cần PTY/SSH trong parser Rust.
7. Nối OAuth fork vào `AndroidTokenStore.java` bằng AES-GCM/Android Keystore, tắt auto-update nền và không spawn keep-alive subprocess trong Android MVP.
8. Chạy `cargo fmt --check`, `cargo test`, smoke test offline `colab version` và thử build APK Gradle; kiểm tra manifest/ABI/package.
9. Review diff và README, nêu phần build được/chưa thể kiểm tra trên thiết bị; không thực hiện OAuth hay tạo phiên Colab trong kiểm thử.

## Tệp chính

- `settings.gradle`, `build.gradle`, `gradle.properties`, `gradlew*`
- `app/build.gradle`, `app/src/main/AndroidManifest.xml`
- `app/src/main/java/dev/trung/colabterm/MainActivity.java`
- `app/src/main/java/dev/trung/colabterm/AndroidTokenStore.java`
- `app/src/main/python/colab_runner.py`, `app/src/main/python/android_keyring.py`, `app/src/main/python/colab_cli/`
- `native/Cargo.toml`, `native/src/lib.rs`
- `third_party/google-colab-cli-android/`, `THIRD_PARTY_NOTICES.md`, `README.md`
- Tài liệu đặc tả/kế hoạch trong `docs/superpowers/`.

## Kiểm thử

- Rust unit tests: quoting, token rỗng/sai, command allowlist và từ chối shell chaining.
- Smoke test offline `colab version` qua Click/Typer runner; không auth/network.
- Android debug build bằng SDK/NDK sẵn có; nếu resolver thất bại thì xử lý lỗi có bằng chứng, không đổi fork âm thầm.
- Không tạo giả lập thành công cho OAuth, session hay GPU. Build không thay thế kiểm thử trên thiết bị Android thật.
