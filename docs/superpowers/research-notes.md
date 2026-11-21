# External Technical Research Notes

## Sources and findings

- Official Google Colab CLI: https://github.com/googlecolab/google-colab-cli — The README states that supported platforms are currently Linux and macOS; the upstream CLI is Python and requires Python >=3.12.
- Upstream metadata: https://raw.githubusercontent.com/googlecolab/google-colab-cli/main/pyproject.toml — CLI 0.8.0 declares `pydantic`, `nbformat`, `jupyter-kernel-client`, `keyring`, `google-auth`, Click/Typer, and other dependencies; its entry point is `colab_cli.cli:main`.
- Android/Termux discussion: https://github.com/googlecolab/google-colab-cli/issues/90 — The discussion points out that an Android/Termux build replaced a native dependency with a pure-Python solution.
- Fork selected by the user for the MVP: https://github.com/phiiggfdg/google-colab-cli-android — Archive v5.3 has the fallback version `0.7.2.post0+android5.3`; `ANDROID_NOTES.md` describes `pydantic_lite`, `ipynb_lite`, `ws_kernel`, and `google-auth>=2.47,<2.48` to reduce native dependencies. The fork's documentation says it was tested on Termux; that is not evidence that it runs in an APK/Chaquopy.
- Chaquopy: https://chaquo.com/chaquopy/doc/current/android.html — Plugin 17.2.0 documentation specifies minSdk 24, Python 3.13/3.14 support, and the arm64-v8a ABI. This does not guarantee that every PyPI package has a compatible Android wheel.

## Build evidence in the sandbox

- Chaquopy/Python 3.14 with upstream CLI 0.8.0 stops at the `cryptography` dependency because no suitable Android wheel is available for 3.14.
- Python 3.13 has Android wheels for `cryptography`/`cffi`, but upstream CLI 0.8.0 stops because an Android wheel for `pydantic-core` is unavailable.
- No OAuth flow, Google API call, or Colab session was created during testing.
