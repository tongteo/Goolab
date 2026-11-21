# Thiết kế: Colab console/SSH tương tác trong terminal Android

## Mục tiêu

Cho phép dùng `colab console -s <phiên-có-sẵn>` và `colab ssh -s <phiên-có-sẵn>` ngay trong shell ColabTerm, với nhập byte thô, ANSI output và kích thước terminal đồng bộ. Không thay đổi tiến trình tạo/dừng phiên trong lúc triển khai; bản Android nên chỉ kết nối phiên hiện có và không thực hiện `--rm`.

## Bằng chứng và lựa chọn

CLI Python hiện có console qua WebSocket `/colab/tty`; khi TTY, CLI đặt raw mode, gửi/nhận byte và thông báo kích thước qua `SIGWINCH`. SSH hiện gọi executable OpenSSH với `-t` và ProxyCommand `/colab/ssh`; APK không đóng gói `ssh`/`ssh-keygen`, còn Python runner không có file descriptor TTY thật. Vì vậy, chỉ nối `sys.stdin` dạng dòng hiện tại là không đủ.

Thiết kế dùng phiên bản mới của bridge loopback giữa helper Rust và Java: request mang kích thước PTY ban đầu; input sau request là các frame byte/resize/EOF; helper Rust đặt terminal cục bộ ở raw mode chỉ cho lệnh tương tác và khôi phục khi thoát. Java bridge biến các frame thành API byte stream cho runner Chaquopy. Runner cung cấp text/binary stdin/stdout tương thích với terminal stream và kích thước/resize callbacks.

`console` giữ nguyên endpoint/flow upstream, nhưng nhận terminal-size provider và resize callback từ runner Android. `ssh` trong Android dùng Paramiko làm SSHv2 client trực tiếp trên một adapter WebSocket-socket tới endpoint `/colab/ssh`; dùng Ed25519/ECDSA private key có sẵn hoặc sinh key ECDSA tạm trong bộ nhớ, không cần executable `ssh`. Desktop CLI giữ nguyên nhánh OpenSSH hiện hành. Lỗi import/dependency phải hiện thông báo rõ, không fallback âm thầm sang lệnh khác.

Parser Rust chỉ nới allowlist cho `console` và `ssh`; `repl` vẫn bị từ chối. Nhánh Android yêu cầu một phiên đã lưu, không auto-create; `--rm` không được phép dừng phiên. Mã không gửi lệnh, tạo hoặc dừng runtime trong giai đoạn build/test.

## Phạm vi ngoài mục tiêu

- Không thêm lệnh Colab mới hoặc thay đổi API/remote runtime.
- Không dùng Colab console như giả lập cho lệnh SSH.
- Không thay đổi xử lý font, Tab, hoặc `TerminalView` trong thay đổi này; lỗi 9sp được giữ thành việc riêng, chờ phép thử trên thiết bị.
- Không kiểm thử bằng cách mở kết nối tới phiên người dùng; chỉ dùng unit tests/mocks/build.

## Phụ thuộc và license

Đã xác minh Chaquopy cài thành công trên CPython 3.13 ARM64 Android: `bcrypt-3.2.2-0`, `cffi-1.17.1-0`, `cryptography-42.0.8-1`, `PyNaCl-1.5.0-2` (các hậu tố là revision build Chaquopy). `chaquopy_libffi-3.3-3` là wheel Android native; `paramiko-5.1.0`, `invoke-3.0.3` và `pycparser-3.12` là pure Python wheels. Đã đọc license metadata của các gói được resolve và sao chép license texts vào `app/src/main/assets/licenses/`: Paramiko LGPL-2.1; cryptography Apache-2.0 OR BSD-3-Clause; bcrypt và PyNaCl Apache-2.0; cffi MIT; invoke BSD-2-Clause; pycparser BSD-3-Clause; chaquopy-libffi kèm license MIT-style và thông báo GPL-2.0 cho công cụ build/test phụ trợ. Chaquopy ghi Python 3.13+ khắc phục nhiều vấn đề 16 KB page size, nhưng APK vẫn cần build và kiểm tra trên thiết bị trước khi tuyên bố runtime hoạt động.

## Tiêu chí nghiệm thu

1. Parser chấp nhận `colab console`/`colab ssh`, vẫn từ chối `repl` và shell operators.
2. Helper/bridge truyền chính xác byte điều khiển, UTF-8, kích thước PTY ban đầu và sự kiện resize; terminal local được khôi phục sau EOF/lỗi.
3. Console WebSocket có thể đọc/ghi tương tác qua stream được mock, gửi size và cập nhật khi resize.
4. SSH adapter truyền binary WebSocket frames thành socket stream; Paramiko test bằng transport/socket mocks, không kết nối runtime thật.
5. Android path chỉ resolve session tồn tại; `--rm`/auto-create không thể dừng/tạo session.
6. Unit tests, Rust tests, Gradle test và debug APK build pass; APK package có dependency và licenses đầy đủ.
7. Không khẳng định kết nối remote thật hoạt động nếu chưa thử trên thiết bị/runtime do người dùng cho phép.

## Nguồn kỹ thuật đã kiểm tra

- Chaquopy install/packages: https://chaquo.com/chaquopy/doc/current/android.html
- Chaquopy 16 KB support / Python wheels: https://chaquo.com/chaquopy/author/smith/
- Chaquopy issue 16 KB and cryptography: https://github.com/chaquo/chaquopy/issues/1171
- Paramiko PyPI 5.1.0 and metadata: https://pypi.org/project/paramiko/
- Paramiko installation/cryptography dependency: https://www.paramiko.org/installing.html
- Chaquopy CPython 3.13 Android ARM64 package wheels: https://chaquo.com/pypi-13.1/cryptography/ ; https://chaquo.com/pypi-13.1/bcrypt/ ; https://chaquo.com/pypi-13.1/pynacl/ ; https://chaquo.com/pypi-13.1/cffi/
- Paramiko 5.1.0 package license (LGPL-2.1): https://pypi.org/project/paramiko/5.1.0/
- bcrypt 3.2.2 and PyNaCl 1.5.0 package metadata/license: https://pypi.org/project/bcrypt/3.2.2/ ; https://pypi.org/project/PyNaCl/1.5.0/
- cryptography 42.0.8 project/license information: https://cryptography.io/en/42.0.8/ ; https://cryptography.io/en/42.0.8/license/
- cffi 1.17.1 package license (MIT): https://pypi.org/project/cffi/1.17.1/
- invoke 3.0.3 and pycparser 3.12 package metadata/license: https://pypi.org/project/invoke/3.0.3/ ; https://pypi.org/project/pycparser/3.12/
