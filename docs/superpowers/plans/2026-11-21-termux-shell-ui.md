# Plan: Termux Shell with Direct Colab CLI

## Scope

The launcher screen uses Termux `TerminalView` and an Android PTY shell; typing `colab ...` at the prompt invokes the embedded Python/Colab CLI. Keep the existing standalone CLI as a secondary path. Do not add a rootfs, root, or Android permissions.

## Implementation steps

1. Write tests first for the shell environment and helper wire protocol; confirm they fail because the implementation is missing.
2. Add a Rust `colab` client binary (Android ARM64) using a token, random loopback port, argv framing, stdin/stdout relay, and exit code; test framing on the host. Configure Gradle to copy the binary into APK assets.
3. Create `ColabBridgeServer` in Java: loopback-only, 256-bit token, request limits, authentication, existing JNI parser, then sequential Chaquopy runner calls; relay output/prompt/line input and exit code through the protocol.
4. Extend the Python runner to accept normalized `colab` args from the helper while preserving the existing entry point for the CLI screen.
5. Complete the shell Activity using Termux TerminalView, install the helper in `files/bin`, start the server before the PTY, pass path/port/token through the child environment, clean up the server/session when the Activity ends, and add a button to the legacy CLI screen.
6. Update the launcher manifest, notices/license, and README; request no additional permissions.
7. Run Java unit tests, Rust formatting/tests, and Gradle test/assemble. Check that the APK contains `TerminalView`, `libtermux.so`, Rust JNI, and the executable bridge; verify the manifest/permissions and review changed files.
8. If no ADB device is available, report the limitation clearly: PTY, executable-file behavior, and the Colab bridge have not been proven on a real device.

## Tests

```bash
PATH="/home/ubuntu/.local/share/uv/python/cpython-3.13.15-linux-x86_64-gnu/bin:$PATH" ./gradlew :app:testDebugUnitTest :app:assembleDebug
cd native && cargo fmt -- --check && cargo test
```

Inspect the APK with `unzip -l app/build/outputs/apk/debug/app-debug.apk`; confirm there are no new permissions in the merged manifest. Do not call Google/OAuth or create a Colab session during testing.
