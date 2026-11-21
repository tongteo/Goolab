# Plan: ColabTerm Android MVP

## Implementation order

1. Initialize a standalone Gradle Android app in `app/`, configure API 35/minSdk 24/ARM64, the Gradle wrapper, and Chaquopy Python 3.13.
2. Create the `native/` Rust crate with a shell-like tokenizer and Colab-command allowlist; test quoting, parse errors, and rejection of shell commands.
3. Add a JNI adapter and a Gradle task using `cargo-ndk` to place `.so` files in `app/src/main/jniLibs/arm64-v8a/`.
4. Add the Android Activity with a terminal UI, history, scrolling output, file picker, and input bridge.
5. Bundle the Android `google-colab-cli` fork source, version 0.7.2.post0+android5.3, with its license; install Android-compatible pure-Python dependencies instead of installing upstream CLI 0.8.0 from PyPI.
6. Add a Python runner that calls Typer/Click and safely forwards stdout/stderr/input; block commands requiring PTY/SSH in the Rust parser.
7. Connect the fork's OAuth flow to `AndroidTokenStore.java` with AES-GCM/Android Keystore, disable background auto-update, and do not spawn a keep-alive subprocess in the Android MVP.
8. Run `cargo fmt --check`, `cargo test`, an offline `colab version` smoke test, and a Gradle APK build; check the manifest/ABI/package.
9. Review the diff and README, report what built and what could not be tested on a device, and do not perform OAuth or create a Colab session during testing.

## Main files

- `settings.gradle`, `build.gradle`, `gradle.properties`, `gradlew*`
- `app/build.gradle`, `app/src/main/AndroidManifest.xml`
- `app/src/main/java/dev/trung/colabterm/MainActivity.java`
- `app/src/main/java/dev/trung/colabterm/AndroidTokenStore.java`
- `app/src/main/python/colab_runner.py`, `app/src/main/python/android_keyring.py`, `app/src/main/python/colab_cli/`
- `native/Cargo.toml`, `native/src/lib.rs`
- `third_party/google-colab-cli-android/`, `THIRD_PARTY_NOTICES.md`, `README.md`
- Design and plan documents in `docs/superpowers/`.

## Tests

- Rust unit tests: quoting, empty/invalid tokens, command allowlist, and rejection of shell chaining.
- Offline `colab version` smoke test through the Click/Typer runner; no auth/network.
- Android debug build using the available SDK/NDK; if dependency resolution fails, address the evidenced error without silently changing the fork.
- Do not simulate successful OAuth, sessions, or GPU access. A build does not replace testing on a real Android device.
