# Ghi chú nguồn kỹ thuật bên ngoài

## Nguồn và kết luận

- Google Colab CLI chính thức: https://github.com/googlecolab/google-colab-cli — README ghi rõ platform support hiện là Linux và macOS; CLI upstream tại thời điểm khảo sát là Python, yêu cầu Python >=3.12.
- Metadata upstream: https://raw.githubusercontent.com/googlecolab/google-colab-cli/main/pyproject.toml — CLI 0.8.0 khai báo `pydantic`, `nbformat`, `jupyter-kernel-client`, `keyring`, `google-auth`, Click/Typer và các dependencies khác; entry point `colab_cli.cli:main`.
- Android/Termux discussion: https://github.com/googlecolab/google-colab-cli/issues/90 — thảo luận chỉ ra một Android/Termux build đã thay một dependency native bằng giải pháp Python thuần.
- Fork đã được người dùng chọn cho MVP: https://github.com/phiiggfdg/google-colab-cli-android — archive v5.3 có version fallback `0.7.2.post0+android5.3`; `ANDROID_NOTES.md` mô tả `pydantic_lite`, `ipynb_lite`, `ws_kernel` và `google-auth>=2.47,<2.48` để giảm dependency native. Tài liệu của fork nói đã thử trên Termux; không phải bằng chứng đã chạy trong APK/Chaquopy.
- Chaquopy: https://chaquo.com/chaquopy/doc/current/android.html — tài liệu plugin 17.2.0 nêu minSdk 24, hỗ trợ Python 3.13/3.14 và ABI arm64-v8a. Tài liệu này không bảo đảm mọi package PyPI có Android wheel.

## Bằng chứng build tại Sandbox

- Chaquopy/Python 3.14 với upstream CLI 0.8.0 dừng ở dependency `cryptography` không có wheel Android phù hợp cho 3.14.
- Python 3.13 có Android wheels cho `cryptography`/`cffi`, nhưng upstream CLI 0.8.0 dừng vì thiếu Android wheel cho `pydantic-core`.
- Chưa có OAuth, lời gọi Google, hay phiên Colab nào được tạo trong kiểm thử.
