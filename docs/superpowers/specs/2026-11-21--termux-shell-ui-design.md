# Thiết kế: Termux terminal với Colab CLI trong shell

## Mục tiêu

Dùng terminal UI/PTY upstream của Termux làm màn hình chính, đồng thời cho phép gõ `colab ...` ngay tại prompt shell và thực thi Google Colab CLI Python đã nhúng bằng Chaquopy. Màn hình Colab CLI hiện tại có thể giữ làm lối phụ.

## Kiến trúc đã chọn

App dùng `TerminalView` upstream và dependency `terminal-emulator` ghim tại Termux `v0.118.3`, lấy qua JitPack. Không tích hợp app Termux hoặc module `termux-shared`: repository Termux app là GPLv3, còn hai thư viện terminal có ngoại lệ Apache-2.0. Giữ attribution và license upstream trong notices.

Shell là `/system/bin/sh -i` chạy qua PTY trong tiến trình con với UID/SELinux sandbox của app, không root. HOME và TMPDIR nằm trong data/cache riêng của app; PATH có thư mục `files/bin` của app rồi tới `/system/bin:/system/xbin`. Không cấp thêm quyền Android.

Một helper native ARM64 tên `colab` được chép từ APK vào `files/bin`. Helper gửi argv và stdin qua TCP loopback tới bridge Java cục bộ; cổng ngẫu nhiên kèm token ngẫu nhiên 256-bit chỉ được truyền cho tiến trình shell. Bridge kiểm tra argv qua parser/allowlist Rust hiện hữu, rồi gọi `colab_runner` trong Chaquopy. Output, mã thoát và các prompt đầu vào được chuyển ngược qua PTY. Không chuyển command của người dùng sang `/system/bin/sh -c`.

Bridge chỉ chấp nhận kết nối loopback, xác thực token và giới hạn kích thước request; việc gọi Python được tuần tự hóa vì runner tạm thay `sys.stdout`/`builtins.input`. Token không được ghi log. `colab` chỉ chạy các subcommand trong allowlist hiện tại; shell hệ thống vẫn là shell cục bộ trong sandbox.

## Giới hạn

Đây không phải bộ môi trường Termux đầy đủ: không rootfs, `pkg`/`apt`, root, package repository hay daemon nền. Môi trường chỉ có lệnh Android hệ thống sẵn có, Python/Colab CLI nhúng và wrapper `colab`. Phiên có thể mất khi Activity/app bị kết thúc. Cần kiểm thử trên thiết bị thật để xác minh PTY và việc thực thi ELF trong data riêng; build APK không thay thế kiểm chứng đó.

## Các tệp chính

- `settings.gradle`, `app/build.gradle`: JitPack, thư viện terminal và stage helper native vào assets.
- `native/src/bin/colab.rs`: client TCP/stdio có framing và mã thoát; `native/src/lib.rs`: allowlist hiện có vẫn là cổng kiểm soát lệnh.
- `app/src/main/java/dev/trung/colabterm/TermuxShellActivity.java`: Termux TerminalView, PTY shell, path/install helper và lifecycle.
- `app/src/main/java/dev/trung/colabterm/ColabBridgeServer.java`: loopback authenticated bridge, protocol validation, Chaquopy calls và prompt/input relay.
- `app/src/main/java/dev/trung/colabterm/ShellConfig.java`, `app/src/test/java/dev/trung/colabterm/ShellConfigTest.java`: argv/environment shell.
- `app/src/main/java/dev/trung/colabterm/MainActivity.java`, `app/src/main/python/colab_runner.py`: reuse parser and support both existing screen and bridge argv.
- `AndroidManifest.xml`, `README.md`, `THIRD_PARTY_NOTICES.md`: launcher, giới hạn, nguồn/license.

## Nghiệm thu

1. Unit tests xác nhận shell env/path và Rust protocol framing/argv.
2. Rust formatter/test suite qua.
3. Gradle unit test và ARM64 debug APK build qua.
4. APK có `TerminalView`, native PTY `libtermux.so`, Rust JNI và executable `colab`.
5. Manifest không thêm quyền Android. Bridge chỉ bind loopback, token không log; parser Rust từ chối lệnh ngoài allowlist.
6. Không tuyên bố runtime Android hoạt động nếu chưa cài/chạy thử trên thiết bị.

## Nguồn upstream

- https://github.com/termux/termux-app
- https://raw.githubusercontent.com/termux/termux-app/v0.118.3/LICENSE.md
- https://github.com/termux/termux-app/wiki/Termux-Libraries
- https://github.com/termux/termux-app/tree/v0.118.3/terminal-view
