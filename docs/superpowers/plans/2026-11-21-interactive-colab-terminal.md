# Plan: Interactive Colab Console/SSH

## Constraints

Local build/test only. Do not run `colab console`, `colab ssh`, `colab new`, `colab stop`, or any command against a real runtime. Preserve the `t4-lab` session. Do not change the font-size/Tab behavior without independent evidence.

## Implementation steps

1. **Test the protocol first (Rust):** extend the parser for console/ssh; add an interactive-command type; write CTC2 framing tests (terminal dimensions, input frames, resize, EOF) and terminal raw-mode/restore tests that do not require a real PTY. Run the tests to confirm they fail before implementation.
2. **Rust helper:** version the header; read `TIOCGWINSZ`; set raw mode only for console/ssh; poll stdin and terminal dimensions, and send input/resize/EOF frames sequentially over the socket; restore termios on every exit path. Run cargo fmt/test and review protocol limits.
3. **Java bridge:** parse CTC2 and limit frame sizes; `PythonBridgeIO` provides byte/text-line reads, resize/dimension notifications, and text/raw-byte writes; pure-logic tests should be separable from Android Context. Preserve loopback/token authentication and serialize Python calls as currently implemented.
4. **Python runner/console:** add a binary writer, incremental UTF-8 text reader, EOF, and resize hook; update console to accept optional terminal-size providers/resize callbacks while preserving default desktop behavior. Write unittest stream/mocks.
5. **Android SSH:** add a pinned Paramiko version and confirm all Android/Python 3.13 ARM64 wheels; implement a WebSocket socket adapter, key resolution/generation, TTY request/shell/resize, and byte relay through the runner. The Android path must use a saved session and reject `--rm`; keep OpenSSH for the desktop path. Test the adapter and key selection with mocks; do not open a real WebSocket.
6. **Integrate/package:** update the Gradle dependency, `THIRD_PARTY_NOTICES.md`, APK assets, README/spec; run Python unittest, `cargo fmt --check`, `cargo test`, `./gradlew :app:testDebugUnitTest :app:assembleDebug`; verify the APK contains the helper, Paramiko/cryptography ARM64, and licenses.
7. **Final review:** inspect the diff manually (the workspace currently has no `.git`), protocol backward/version consistency, token/private-key logging, EOF/resize, and missing-wheel failures; report any blocked tests. Confirm no remote requests/commands were sent during the work.

## Verified progress

- Completed CTC2 byte-stream/resize/EOF, raw terminal restoration, Java bridge, console UTF-8 stream, Paramiko WebSocket socket adapter, and Android SSH using only saved sessions; `--proxy-mode`/`--rm` are rejected. Also fixed the stdin reader so it does not lose input after the first line and distinguishes a blank line from EOF.
- Passing checks: `cargo fmt --check && cargo test` (11 library + 6 helper tests); Python mock suite (8 tests) and `py_compile`; `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug`.
- APK `app/build/outputs/apk/debug/app-debug.apk` built successfully (22 MB). ZIP inspection showed `libcolab_cli.so` is an AArch64 PIE executable and `libcolab_termux_core.so` is an AArch64 shared object; `requirements-common.imy` contains Paramiko 5.1.0 and cryptography/bcrypt/PyNaCl/cffi; license assets are bundled.
- No Android device is connected, and no connection/command was sent to Colab. The `t4-lab` session was untouched; console/SSH on a real device/runtime remains unverified.
- The 9sp investigation was limited to local code: the TAB button always sends `\t`; Termux `TerminalView.setTextSize` immediately calls `updateSize()`, recalculating the rows/columns and updating the PTY. No special 9sp branch was found, so no speculative fix was made; use `stty size` before/after TAB at 8sp/9sp and try a new shell to distinguish wrapping/resize from an input issue.

## Stop/adjustment conditions

If Chaquopy has no compatible wheel for cryptography/bcrypt/pynacl or Paramiko causes alignment/build errors, do not replace it with an unverified executable or simulate SSH through console. Complete console if independent, keep `ssh` reporting a clear reason, and state the missing dependency/risk before expanding further.
