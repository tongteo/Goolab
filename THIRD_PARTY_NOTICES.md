# Third-party notices

## Termux terminal libraries

ColabTerm uses `terminal-view` version `0.118.3` and its `terminal-emulator` dependency from the [Termux application repository](https://github.com/termux/termux-app/tree/v0.118.3), resolved through JitPack. These libraries provide the terminal view/emulator; the app starts Android's own `/system/bin/sh` in a PTY.

The upstream [license statement for v0.118.3](https://raw.githubusercontent.com/termux/termux-app/v0.118.3/LICENSE.md) says the Termux application repository is GPLv3-only, with an Apache License 2.0 exception for the `terminal-view` and `terminal-emulator` libraries (which include code from Android Terminal Emulator). This project links those separately licensed terminal libraries; it does not bundle the full Termux application or `termux-shared`. The [upstream library guide](https://github.com/termux/termux-app/wiki/Termux-Libraries) documents the published library dependency.

A copy of Apache License 2.0 is included in the source and APK at `app/src/main/assets/licenses/Apache-2.0.txt`.

## Google Colab CLI Android fork

ColabTerm bundles Python source from [google-colab-cli-android](https://github.com/phiiggfdg/google-colab-cli-android), version `0.7.2.post0+android5.3`, distributed under Apache License 2.0. The upstream license and Android notes are retained at [`third_party/google-colab-cli-android/LICENSE`](third_party/google-colab-cli-android/LICENSE) and [`third_party/google-colab-cli-android/ANDROID_NOTES.md`](third_party/google-colab-cli-android/ANDROID_NOTES.md). The license is also copied into APK assets as `app/src/main/assets/licenses/google-colab-cli-android-Apache-2.0.txt`.

App-specific changes include Android Keystore OAuth token storage, disabling automatic background updates and Python subprocess keep-alive behavior, and invoking the CLI through the Chaquopy runner/loopback bridge.

## Android SSH dependencies

The Android build pins Paramiko `5.1.0`, `cryptography` `42.0.8`, `bcrypt` `3.2.2`, `PyNaCl` `1.5.0`, and `cffi` `1.17.1`. Chaquopy also resolves Paramiko's pure-Python dependencies `invoke` `3.0.3` and `pycparser` `3.12`, plus the Android `chaquopy-libffi` `3.3` native support package. Package metadata and license texts were checked from the exact resolved wheels in the Chaquopy build output.

| Package | License declared by resolved package metadata | Included license asset |
| --- | --- | --- |
| Paramiko 5.1.0 | LGPL-2.1 | `app/src/main/assets/licenses/Paramiko-5.1.0-LGPL-2.1.txt` |
| cryptography 42.0.8 | Apache-2.0 OR BSD-3-Clause | `cryptography-42.0.8-Apache-2.0.txt`, `cryptography-42.0.8-BSD-3-Clause.txt` |
| bcrypt 3.2.2 | Apache-2.0 | `bcrypt-3.2.2-Apache-2.0.txt` |
| PyNaCl 1.5.0 | Apache-2.0 | `PyNaCl-1.5.0-Apache-2.0.txt` |
| cffi 1.17.1 | MIT | `cffi-1.17.1-MIT.txt` |
| invoke 3.0.3 | BSD-2-Clause | `invoke-3.0.3-BSD-2-Clause.txt` |
| pycparser 3.12 | BSD-3-Clause | `pycparser-3.12-BSD-3-Clause.txt` |
| chaquopy-libffi 3.3 | MIT-style license text shipped with the wheel | `chaquopy-libffi-3.3-License.txt` |

The `chaquopy-libffi` wheel also contains a GPL-2.0 notice for auxiliary build/test tools; the original notice is retained as `app/src/main/assets/licenses/chaquopy-libffi-BUILDTOOLS-GPL-2.0-notice.txt`. This notice describes those tools and does not mean the app includes their executable code.

The package indexes and metadata sources are [Chaquopy's Android Python package index](https://chaquo.com/pypi-13.1/), [Paramiko 5.1.0](https://pypi.org/project/paramiko/5.1.0/), [cryptography 42.0.8](https://pypi.org/project/cryptography/42.0.8/), [bcrypt 3.2.2](https://pypi.org/project/bcrypt/3.2.2/), [PyNaCl 1.5.0](https://pypi.org/project/PyNaCl/1.5.0/), [cffi 1.17.1](https://pypi.org/project/cffi/1.17.1/), [invoke 3.0.3](https://pypi.org/project/invoke/3.0.3/), and [pycparser 3.12](https://pypi.org/project/pycparser/3.12/).
