# Kế hoạch: Giữ phiên terminal/SSH khi ra nền

## Ràng buộc

- Giữ nguyên quyền, bridge loopback/token, shell `/system/bin/sh`, parser/allowlist và flow SSH hiện có.
- Không chạy lệnh tới phiên `t4-lab`; không tạo/dừng runtime; không tự reconnect sau process death.
- Thư mục bàn giao không có `.git`; không reset/clean, giữ nguyên mọi tệp ngoài phần sửa được ghi dưới đây.
- Android API 34+ cần foreground-service type; dùng `specialUse` với ongoing notification và nút dừng. Google Play phát hành sẽ cần khai báo/duyệt use case.

## Các bước

1. **Thiết kế đã ghi:** `docs/superpowers/specs/2026-11-21--background-terminal-session-design.md`; service sở hữu PTY/bridge, Activity chỉ bind/attach.
2. **Service lifecycle trước:** thêm `ShellTerminalService.java` với LocalBinder, tạo foreground notification sớm, tạo/giữ `TerminalSession` + `ColabBridgeServer`, callback an toàn sang listener UI, explicit stop; không sticky-restart shell rỗng. Kiểm tra các ranh giới trước khi tách code khỏi Activity.
3. **Activity bind/detach:** `TermuxShellActivity.java` start/bind service khi đang visible, gắn lại session đang chạy, giữ UI client qua weak reference và clear/unbind trong `onStop/onDestroy`; Activity không đóng session/bridge. Có nút **Shell mới** và **Dừng**.
4. **Android declaration/notification:** `AndroidManifest.xml` khai báo service `exported=false`, `foregroundServiceType=specialUse`, permission và subtype metadata. Notification yên lặng mở app/dừng shell; Android 13+ có nút xin quyền `POST_NOTIFICATIONS`, nếu bị từ chối giải thích service vẫn có thể hiện trong Task Manager. Không đưa token/secret vào notification.
5. **Kiểm thử:** chạy Python mock suite và Gradle unit tests/debug APK build; Gradle được gọi với `-x :app:installDebugPythonRequirements` do thiếu Python 3.13 trên máy. Kiểm tra merged manifest/APK. Không chạy `cargo` vì Rust không đổi. Chưa có test tự động mô phỏng Android Service lifecycle.
6. **Review:** rà soát listener leak, Activity destroy/reattach, stop action, service đang chạy nhưng shell đã exit, permission/type theo API 24–36, bảo mật bridge. Không có `.git`; kiểm tra thủ công bằng đọc mã, grep và build.
7. **Bàn giao:** chưa có Android device hoặc phiên Colab thật trong sandbox. Khi cài APK mới, kiểm tra trên điện thoại với SSH hiện có: đưa app ra nền, gỡ task khỏi Recents, mở lại để xác nhận terminal còn prompt, rồi thử dừng qua app/notification; không gọi `colab ssh` trong lúc build/test.

## Tiêu chí đạt

- Activity có thể bị đóng/tạo lại mà không kết thúc PTY/SSH hoặc đóng bridge khi foreground service còn sống.
- Notification foreground có thể dùng để quay lại app hoặc dừng rõ ràng phiên shell.
- Dừng qua notification/UI đóng session và bridge đúng một lần.
- Unit tests và build pass hoặc rào cản môi trường được nêu chính xác; không có quyền Android không liên quan.
- Không tuyên bố sống sót qua force-stop/process death/Android kill; không có reconnect tự động.
