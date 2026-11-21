# Thiết kế: Giữ terminal/SSH khi ColabTerm ra nền

## Mục tiêu

Giữ phiên shell PTY và kết nối SSH hiện có trong khi Activity bị đưa ra nền hoặc task bị gỡ khỏi Recents; khi mở ColabTerm lại, gắn giao diện vào đúng terminal session đang sống, không bắt người dùng chạy lại `colab ssh` chỉ vì Activity đóng.

## Quyết định kiến trúc

- Chuyển ownership của `TerminalSession` và `ColabBridgeServer` từ `TermuxShellActivity` sang một `ShellTerminalService` dùng chung tiến trình.
- Activity khởi chạy service từ màn hình đang hiển thị, bind để lấy `TerminalSession`, gắn `TerminalView` và đăng ký listener tạm thời. Activity detach listener khi rời; không kết thúc shell hoặc đóng bridge trong `onDestroy()`.
- Service chạy foreground với một notification thường trực, yên lặng, nêu rõ terminal/SSH đang hoạt động; notification có hành động mở app và dừng shell. Có thao tác dừng tương ứng trong UI để người dùng luôn có lối thoát. Android 13+ cần runtime permission `POST_NOTIFICATIONS` để notification FGS hiện trong notification drawer; nếu bị từ chối, Android vẫn hiện service trong Task Manager. Nguồn: [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission).
- Khai báo `specialUse` với mô tả “interactive terminal/SSH session to a user-selected Colab runtime”. Đây là tác vụ tương tác liên tục không được mô tả phù hợp bởi `dataSync` (giới hạn thời gian mới trên Android) hay `connectedDevice`. Android yêu cầu khai báo type/permission từ API 34; Google Play yêu cầu mô tả/duyệt use case `specialUse` khi gửi app. Nguồn: [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).
- Không dùng `START_STICKY` để tự tạo một shell rỗng sau khi tiến trình bị giết; service chỉ duy trì object PTY đang sống. Không lưu token/command SSH để tự kết nối lại sau khi service/process bị mất.
- `onTaskRemoved` không dừng service; hành động **Dừng shell** trong notification/UI đóng PTY và bridge, rồi dừng foreground service.

## Ngoài phạm vi và giới hạn

- Không bảo đảm duy trì kết nối sau force-stop, reboot, Android/vendor kill, mất mạng, hết hạn phiên Colab hoặc khi người dùng dừng service từ hệ thống.
- Không tự chạy lại `colab ssh`, không tự tạo/dừng runtime, không thay đổi allowlist, OAuth, token bridge, UID/quyền shell hoặc giao thức remote.
- Không thêm keep-alive cho assignment Colab: SSH keepalive 15 giây xử lý idle timeout của đường SSH/WebSocket; hai cơ chế không đồng nghĩa.
- Không thay renderer Termux, input path, cỡ chữ hay cuộn khi chưa có đo đạc trên thiết bị.
- Không tuyên bố background/SSH đã kiểm thử thực tế nếu chưa chạy trên điện thoại. Không kết nối `t4-lab` trong lúc phát triển.

## Kiểm thử và nghiệm thu

1. Unit test thuần kiểm tra chính sách khởi tạo/dừng service và trạng thái notification/action ở mức có thể kiểm thử mà không Android device.
2. Python regression test xác nhận Paramiko bật keepalive 15 giây sau khi xác thực.
3. Gradle unit tests, build debug APK, kiểm tra merged manifest có `specialUse`/permission đúng và service không exported.
4. Không gửi lệnh Colab thật, không tạo/dừng runtime; kiểm thử nền thực tế còn chờ người dùng trên thiết bị: mở SSH hiện có, đưa app ra nền/đóng task, mở lại kiểm tra cùng prompt, rồi thử **Dừng shell**.
5. Báo rõ giới hạn test không có Android device/không có session Colab thực.
