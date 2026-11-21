# Kế hoạch: Colab console/SSH tương tác

## Ràng buộc

Chỉ build/test cục bộ. Không chạy `colab console`, `colab ssh`, `colab new`, `colab stop` hay lệnh nào lên runtime thật. Giữ nguyên phiên `t4-lab`. Không sửa luồng cỡ chữ/Tab nếu không có bằng chứng độc lập.

## Bước triển khai

1. **Kiểm thử giao thức trước (Rust):** mở rộng parser cho console/ssh; thêm loại lệnh tương tác; viết test framing CTC2 (kích thước terminal, frame input, resize, EOF) và test terminal raw-mode/restore theo hướng không cần PTY thật. Chạy test để xác nhận đỏ trước khi triển khai.
2. **Helper Rust:** phiên bản hóa header; lấy `TIOCGWINSZ`; đặt raw mode chỉ cho console/ssh; poll stdin + kích thước terminal, phát frame input/resize/EOF tuần tự qua socket; khôi phục termios khi mọi nhánh kết thúc. Chạy cargo fmt/test và review protocol limits.
3. **Bridge Java:** parse CTC2 và giới hạn frame; `PythonBridgeIO` cung cấp đọc byte/text-line, thông báo resize/kích thước, ghi text/raw bytes; test pure logic có thể tách khỏi Android Context. Giữ authentication loopback/token và tuần tự hóa Python như hiện tại.
4. **Runner Python/console:** bổ sung binary writer, incremental UTF-8 text reader, EOF và resize hook; sửa console để nhận terminal-size provider/resize callback tùy chọn mà vẫn giữ hành vi desktop mặc định. Viết unittest stream/mocks.
5. **SSH Android:** thêm Paramiko version pin và xác nhận toàn bộ wheel Android/Python 3.13 ARM64; implement socket adapter cho WebSocket, key resolve/generate, TTY request/shell/resize và relay byte qua runner. Android path bắt buộc dùng phiên đã lưu và chặn `--rm`; Desktop path giữ OpenSSH. Test adapter và key selection bằng mocks, không mở WebSocket thật.
6. **Tích hợp/đóng gói:** cập nhật Gradle dependency, `THIRD_PARTY_NOTICES.md`, APK assets, README/spec; chạy Python unittest, `cargo fmt --check`, `cargo test`, `./gradlew :app:testDebugUnitTest :app:assembleDebug`; kiểm tra APK chứa helper, Paramiko/cryptography ARM64 và license.
7. **Review cuối:** kiểm tra diff thủ công (workspace hiện không có `.git`), protocol backward/version consistency, không log token/private key, EOF/resize, lỗi thiếu wheel; báo rõ test nào bị chặn. Xác nhận không có request/command remote nào được gửi trong quá trình làm.

## Tiến độ đã kiểm chứng

- Đã hoàn tất CTC2 byte-stream/resize/EOF, raw terminal restoration, Java bridge, console UTF-8 stream, Paramiko WebSocket socket adapter và Android SSH chỉ dùng phiên đã lưu; `--proxy-mode`/`--rm` bị từ chối. Sửa thêm stdin reader để không mất phần sau dòng đầu và phân biệt blank line với EOF.
- Kiểm tra xanh: `cargo fmt --check && cargo test` (11 thư viện + 6 helper); Python mock suite (8 test) và `py_compile`; `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug`.
- APK `app/build/outputs/apk/debug/app-debug.apk` được build thành công (22 MB). Kiểm tra ZIP thấy `libcolab_cli.so` là PIE executable AArch64, `libcolab_termux_core.so` là shared object AArch64; `requirements-common.imy` chứa Paramiko 5.1.0 cùng cryptography/bcrypt/PyNaCl/cffi; các license asset được đóng gói.
- Không có thiết bị Android kết nối và không mở kết nối/lệnh lên Colab. Phiên `t4-lab` không bị đụng tới; console/SSH trên thiết bị/runtime thật vẫn chưa được xác thực.
- Điều tra 9sp chỉ ở mã cục bộ: nút TAB luôn gửi `\t`; Termux `TerminalView.setTextSize` lập tức gọi `updateSize()` và tính lại lưới hàng/cột rồi cập nhật PTY. Không thấy nhánh đặc biệt ở 9sp, nên chưa sửa phỏng đoán; cần `stty size` tại 8sp/9sp trước-sau TAB và thử shell mới để xác định wrap/resize so với lỗi nhập.

## Điều kiện dừng/điều chỉnh

Nếu Chaquopy không có wheel tương thích cho cryptography/bcrypt/pynacl hoặc Paramiko gây lỗi alignment/build, không thay bằng executable không kiểm chứng hoặc giả lập SSH qua console. Hoàn thiện console nếu độc lập, giữ `ssh` báo lỗi có lý do và nêu dependency/rủi ro còn thiếu trước khi mở rộng tiếp.
