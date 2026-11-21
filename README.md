# ColabTerm Android

ColabTerm là APK Android ARM64 có màn hình terminal dựa trên thư viện terminal mã nguồn mở của Termux. Ứng dụng chạy `/system/bin/sh` tương tác trong PTY, rồi cung cấp lệnh `colab` ngay tại prompt để gọi Google Colab CLI Python 3.13 được nhúng bằng Chaquopy. Parser/allowlist và executable cầu nối `colab` viết bằng Rust qua JNI/native binary.

Ứng dụng dùng `terminal-view` Termux `v0.118.3` (kèm thư viện `terminal-emulator` phụ thuộc) qua JitPack; không đóng gói toàn bộ ứng dụng Termux. Màn hình CLI cũ vẫn có thể mở bằng nút **Colab CLI** trong terminal.

## Shell và lệnh Colab

Màn hình khởi động mở `/system/bin/sh -i` qua PTY, với HOME/TMPDIR trong vùng dữ liệu riêng của app. PATH ưu tiên `files/bin` của app; lệnh `colab` tại đây là symlink tới helper Rust được Android cài trong `nativeLibraryDir`, để tránh chính sách Android mới chặn chạy executable từ thư mục dữ liệu app có thể ghi. PATH sau đó mới tới các lệnh hệ thống Android. Đây là shell thật của Android trong sandbox theo UID/SELinux của ứng dụng; không có root, không có `sudo`, và không phải một bản phân phối Linux đầy đủ.

Ví dụ nhập ngay tại prompt:

```sh
colab --help
colab version
colab --auth oauth2 sessions
```

Helper gửi các đối số argv tới bridge chỉ bind loopback, xác thực bằng token ngẫu nhiên, rồi bridge gọi runner Python/Colab CLI. Mã thoát và output quay về terminal; input được chuyển tiếp khi CLI cần nhập dữ liệu. Các lệnh được parser Rust kiểm tra theo allowlist hiện hữu.

## Kết nối Google Colab

1. Mở ColabTerm, tại shell nhập `colab --help` để xem các lệnh hiện được hỗ trợ. Máy cần có kết nối Internet.
2. Tạo phiên Colab mặc định (CPU):

   ```sh
   colab --auth oauth2 new
   ```

   Lần đầu, CLI in một URL Google. Mở URL bằng trình duyệt, đăng nhập và chấp thuận quyền truy cập; sao chép authorization code Google trả về, quay lại terminal, dán mã vào dấu nhắc rồi nhấn Enter. Token được lưu mã hóa bằng Android Keystore.
3. Khi lệnh báo `Session READY`, xem phiên và tên phiên bằng:

   ```sh
   colab sessions
   colab status
   ```

   Có thể dùng `colab exec -s <tên-phiên> -f <đường-dẫn-tệp.py>` để chạy tệp Python cục bộ trên phiên đó. Màn hình **Colab CLI** có nút **Chọn tệp** để chọn tệp từ điện thoại và tạo lệnh upload gợi ý; kiểm tra lệnh rồi bấm **Gửi**.
4. Khi xong, dừng phiên từ xa để giải phóng tài nguyên:

   ```sh
   colab stop -s <tên-phiên>
   ```

Nếu chỉ muốn kiểm tra đăng nhập mà chưa tạo runtime, dùng `colab --auth oauth2 sessions`. Runtime có thể phụ thuộc quota/khả năng cấp phát của tài khoản Colab. Trong shell nhúng, `colab console -s <tên-phiên>` và `colab ssh -s <tên-phiên>` hỗ trợ chế độ tương tác với phiên đã lưu. Android SSH dùng Paramiko qua endpoint `/colab/ssh` và bật SSH keepalive mỗi 15 giây sau xác thực; điều này không ngăn rớt mạng hoặc runtime hết hạn. `--proxy-mode` và `--rm` bị chặn để không tạo hoặc dừng phiên. Lệnh `repl` vẫn chưa được hỗ trợ. Chưa kiểm thử kết nối Colab thật trên thiết bị Android.

## Giữ phiên terminal/SSH khi ra nền

Một foreground service sở hữu PTY, shell và bridge, nên Activity có thể ra nền hoặc task bị gỡ khỏi Recents mà không tự đóng phiên. Khi mở lại ColabTerm, giao diện gắn vào terminal session còn sống; notification có thể mở app hoặc **Dừng shell**. Có nút dừng tương ứng trong giao diện. Android 13 trở lên có thể yêu cầu quyền **Thông báo** để notification hiện trong khay; nếu không cấp, Android vẫn có thể liệt kê foreground service trong Task Manager.

Foreground service không bảo đảm sống qua force-stop, reboot, process/OS hoặc vendor kill, mất mạng hay hết hạn runtime. App không tự chạy lại `colab ssh`, không tự reconnect SSH sau khi process mất, và không tạo/dừng runtime Colab. Dừng shell trong app/notification chỉ dừng tiến trình cục bộ và bridge, không dừng runtime từ xa.

## Giới hạn và an toàn

Shell chỉ có những tiện ích Android hệ thống vốn có; không kèm `pkg`/`apt`, rootfs, root hay package repository kiểu Termux. `console`/`ssh` yêu cầu phiên Colab đã lưu; Android SSH không tự tạo hoặc dừng phiên và sẽ từ chối `--rm`/`--proxy-mode`. `repl` vẫn chưa được hỗ trợ. Một số lệnh Colab khác có thể tạo, sửa hoặc dừng tài nguyên từ xa; hãy kiểm tra lệnh trước khi gửi.

OAuth token được mã hóa AES-GCM với khóa Android Keystore. App không tự đăng nhập, tạo phiên Colab hay kiểm tra cập nhật khi mở. Shell hiện tại được giữ bởi foreground service khi Activity không hiển thị, nhưng Android vẫn có thể kết thúc service hoặc mất kết nối theo các giới hạn ở trên.

## Build APK debug

Cần JDK 17 trở lên, Android SDK Platform 35, Android NDK, Rust target `aarch64-linux-android`, `cargo-ndk` và Python 3.13 trên máy build (major/minor phải khớp Python nhúng của Chaquopy). Cập nhật đường dẫn tùy môi trường rồi chạy:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/24.0.8215888"
export PATH="$HOME/.cargo/bin:$PATH"
# Nếu Python 3.13 không có tên python3.13 trong PATH, chỉ định executable:
# export CHAQUOPY_BUILD_PYTHON="$(uv python find 3.13)"
./gradlew clean :app:testDebugUnitTest :app:assembleDebug
```

Gradle kiểm tra Python 3.13 trước khi build, biên dịch Rust JNI và helper `colab`, rồi lắp APK `app/build/outputs/apk/debug/app-debug.apk`. `assembleDebug` tự xác minh APK có Chaquopy requirements, các wheel SSH đã pin và native extensions ARM64; thiếu Python hoặc asset sẽ làm build thất bại thay vì tạo APK không chạy được. Bản hiện tại chỉ hỗ trợ ABI `arm64-v8a`.

## Kiểm thử và trạng thái

Các kiểm thử Rust bao gồm parser/allowlist và protocol framing. Gradle unit tests kiểm tra bridge byte/resize/EOF; Python unit tests dùng mock để kiểm tra stdin, console resize/UTF-8, WebSocket adapter và an toàn phiên Android. `verifyDebugChaquopyApk` kiểm tra cấu trúc APK, Python 3.13, metadata của Paramiko/cryptography/bcrypt/PyNaCl/cffi và native extensions ARM64.

Build và unit tests không chứng minh PTY, thực thi helper qua symlink tới `nativeLibraryDir`, OAuth hay lệnh Colab hoạt động trên thiết bị Android thật. Môi trường build chưa có thiết bị Android kết nối qua ADB; cần kiểm tra runtime trên điện thoại trước khi xem đây là bản đã kiểm thử thực tế.

## Chẩn đoán crash trên điện thoại

APK ghi các mốc khởi động và bắt Java exception chưa được xử lý vào vùng lưu trữ riêng của app. Nếu app crash, lần mở tiếp theo sẽ vào màn hình **Chẩn đoán** thay vì khởi chạy lại shell; Trung có thể xem trước rồi bấm **Chia sẻ báo cáo debug**. Báo cáo gồm phiên bản app, thông tin thiết bị, mốc khởi động và stack trace. Trên Android 11 trở lên, app còn thử đọc lý do thoát gần nhất và system trace do Android cung cấp cho native crash/ANR. Trên Android cũ hơn, không thể lấy tombstone hệ thống qua API này.

App không chủ động thu OAuth token, nội dung terminal/tệp hoặc logcat. System trace và thông báo exception có thể chứa đường dẫn hay dữ liệu hệ thống; hãy đọc báo cáo trước khi chia sẻ. Báo cáo chỉ nằm trong dữ liệu riêng của app cho đến khi được chia sẻ hoặc người dùng chọn **Xóa báo cáo và thoát**; sau đó mở lại app để thử khởi động bình thường. Native crash không có exit trace, force-stop, mất điện hoặc lỗi xảy ra trước khi Java handler được cài có thể không tạo báo cáo. Tính năng này không thay thế bug report/ADB khi cần log hệ thống đầy đủ.

## Mã nguồn và license

Mã Python Colab CLI được lấy từ fork Android [google-colab-cli-android](https://github.com/phiiggfdg/google-colab-cli-android), phiên bản `0.7.2.post0+android5.3`, theo Apache-2.0. Thông báo bên thứ ba, nguồn Termux và bản sao license được ghi tại [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) và được đóng gói trong APK assets. Nguồn tham khảo thiết kế: [`docs/superpowers/specs/2026-11-21--termux-shell-ui-design.md`](docs/superpowers/specs/2026-11-21--termux-shell-ui-design.md).
