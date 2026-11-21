# Design: Interactive Colab Console/SSH in the Android Terminal

## Goal

Allow `colab console -s <existing-session>` and `colab ssh -s <existing-session>` directly in the ColabTerm shell, with raw-byte input, ANSI output, and synchronized terminal dimensions. Do not change session creation/stopping during implementation; the Android build should connect only to existing sessions and must not perform `--rm`.

## Evidence and choices

The existing Python CLI provides console over the `/colab/tty` WebSocket; when a TTY is available, the CLI switches to raw mode, sends/receives bytes, and reports dimensions through `SIGWINCH`. SSH currently invokes the OpenSSH executable with `-t` and the `/colab/ssh` ProxyCommand; the APK does not bundle `ssh`/`ssh-keygen`, and the Python runner has no real TTY file descriptor. Therefore, connecting the current line-oriented `sys.stdin` alone is insufficient.

The design uses a new version of the loopback bridge between the Rust helper and Java: the request carries the initial PTY dimensions; subsequent input consists of byte/resize/EOF frames; the Rust helper puts the local terminal in raw mode only for interactive commands and restores it on exit. The Java bridge converts frames into a byte-stream API for the Chaquopy runner. The runner provides text/binary stdin/stdout compatible with the terminal stream and dimension/resize callbacks.

`console` keeps the upstream endpoint/flow but receives a terminal-size provider and resize callback from the Android runner. On Android, `ssh` uses Paramiko as an SSHv2 client directly over a WebSocket-socket adapter to the `/colab/ssh` endpoint; it uses an available Ed25519/ECDSA private key or generates a temporary ECDSA key in memory, without an `ssh` executable. The desktop CLI retains its current OpenSSH path. Import/dependency errors must be explicit; do not silently fall back to another command.

The Rust parser only expands its allowlist for `console` and `ssh`; `repl` remains rejected. The Android path requires a saved session and does not auto-create one; `--rm` must not stop the session. The code sends no commands and creates/stops no runtime during build/test.

## Non-goals

- Do not add Colab commands or change the API/remote runtime.
- Do not use Colab console to simulate an SSH command.
- Do not change font handling, Tab, or `TerminalView` in this change; the 9sp issue remains a separate task pending an on-device test.
- Do not test by connecting to the user's session; use only unit tests/mocks/build.

## Dependencies and licenses

Verified that Chaquopy installs successfully on CPython 3.13 ARM64 Android: `bcrypt-3.2.2-0`, `cffi-1.17.1-0`, `cryptography-42.0.8-1`, and `PyNaCl-1.5.0-2` (the suffixes are Chaquopy build revisions). `chaquopy_libffi-3.3-3` is a native Android wheel; `paramiko-5.1.0`, `invoke-3.0.3`, and `pycparser-3.12` are pure-Python wheels. License metadata was read for all resolved packages, and license texts were copied into `app/src/main/assets/licenses/`: Paramiko LGPL-2.1; cryptography Apache-2.0 OR BSD-3-Clause; bcrypt and PyNaCl Apache-2.0; cffi MIT; invoke BSD-2-Clause; pycparser BSD-3-Clause; chaquopy-libffi includes an MIT-style license and a GPL-2.0 notice for auxiliary build/test tools. Chaquopy says Python 3.13+ addresses many 16 KB page-size issues, but the APK still needs to be built and tested on a device before claiming runtime support.

## Acceptance criteria

1. The parser accepts `colab console`/`colab ssh` while still rejecting `repl` and shell operators.
2. The helper/bridge transmits control bytes, UTF-8, initial PTY dimensions, and resize events accurately; the local terminal is restored after EOF/error.
3. The console WebSocket supports interactive reads/writes through a mocked stream, sends its size, and updates it on resize.
4. The SSH adapter maps binary WebSocket frames to a socket stream; Paramiko is tested with transport/socket mocks, without connecting to a real runtime.
5. The Android path resolves only existing sessions; `--rm`/auto-create cannot stop/create a session.
6. Unit tests, Rust tests, Gradle tests, and debug APK build pass; the APK includes dependencies and licenses.
7. Do not claim that a real remote connection works unless it has been tested on a device/runtime with the user's permission.

## Technical sources checked

- Chaquopy installation/packages: https://chaquo.com/chaquopy/doc/current/android.html
- Chaquopy 16 KB support / Python wheels: https://chaquo.com/chaquopy/author/smith/
- Chaquopy 16 KB and cryptography issue: https://github.com/chaquo/chaquopy/issues/1171
- Paramiko PyPI 5.1.0 and metadata: https://pypi.org/project/paramiko/
- Paramiko installation/cryptography dependency: https://www.paramiko.org/installing.html
- Chaquopy CPython 3.13 Android ARM64 package wheels: https://chaquo.com/pypi-13.1/cryptography/ ; https://chaquo.com/pypi-13.1/bcrypt/ ; https://chaquo.com/pypi-13.1/pynacl/ ; https://chaquo.com/pypi-13.1/cffi/
- Paramiko 5.1.0 package license (LGPL-2.1): https://pypi.org/project/paramiko/5.1.0/
- bcrypt 3.2.2 and PyNaCl 1.5.0 package metadata/license: https://pypi.org/project/bcrypt/3.2.2/ ; https://pypi.org/project/PyNaCl/1.5.0/
- cryptography 42.0.8 project/license information: https://cryptography.io/en/42.0.8/ ; https://cryptography.io/en/42.0.8/license/
- cffi 1.17.1 package license (MIT): https://pypi.org/project/cffi/1.17.1/
- invoke 3.0.3 and pycparser 3.12 package metadata/license: https://pypi.org/project/invoke/3.0.3/ ; https://pypi.org/project/pycparser/3.12/
