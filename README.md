# ColabTerm for Android

ColabTerm is an ARM64 Android APK with a terminal screen based on Termux's open-source terminal libraries. It runs an interactive `/system/bin/sh` in a PTY and provides the `colab` command at the prompt to run the Google Colab CLI embedded with Chaquopy and Python 3.13. The parser/allowlist and the `colab` bridge executable are written in Rust and use JNI/native code.

The app uses Termux `terminal-view` `v0.118.3` (with its `terminal-emulator` dependency) through JitPack; it does not bundle the full Termux app. The legacy CLI screen can still be opened with the **Colab CLI** button in the terminal.

## Shell and Colab commands

The startup screen opens `/system/bin/sh -i` through a PTY, with HOME/TMPDIR inside the app's private data area. The app's `files/bin` directory takes priority in PATH; its `colab` command is a symlink to the Rust helper installed by Android in `nativeLibraryDir`, avoiding newer Android policies that block executing binaries from writable app-data directories. Android system commands are added later in PATH. This is the real Android shell, sandboxed under the app's UID/SELinux context: there is no root, no `sudo`, and no complete Linux distribution.

For example, enter these commands at the prompt:

```sh
colab --help
colab version
colab --auth oauth2 sessions
```

The helper sends argv to a loopback-only bridge and authenticates with a random token; the bridge then calls the Python/Colab CLI runner. Exit codes and output are returned to the terminal, and input is forwarded when the CLI requests it. Commands are checked against the existing Rust parser allowlist.

## Connect to Google Colab

1. Open ColabTerm and enter `colab --help` in the shell to see the currently supported commands. An Internet connection is required.
2. Create a default Colab session (CPU):

   ```sh
   colab --auth oauth2 new
   ```

   The first time, the CLI prints a Google URL. Open it in a browser, sign in, and grant access. Copy the authorization code returned by Google, return to the terminal, paste the code at the prompt, and press Enter. The token is stored encrypted with Android Keystore.
3. When the command reports `Session READY`, run these commands to see the session name and check its status:

   ```sh
   colab sessions
   colab status
   ```

   You can run a local Python file on that session with `colab exec -s <session-name> -f <path-to-file.py>`. The **Colab CLI** screen has a **Choose file** button to select a file from your phone and create a suggested upload command; review the command, then tap **Send**.
4. When finished, stop the remote session to release its resources:

   ```sh
   colab stop -s <session-name>
   ```

   To check authentication without creating a runtime, use `colab --auth oauth2 sessions`. Runtime availability may depend on your Colab account's quota and allocation capacity. In the embedded shell, `colab console -s <session-name>` and `colab ssh -s <session-name>` support interactive access to a saved session. Android SSH uses Paramiko through the `/colab/ssh` endpoint and enables SSH keepalive every 15 seconds after authentication; this does not prevent network disconnections or runtime expiration. `--proxy-mode` and `--rm` are blocked to prevent creating or stopping sessions. The `repl` command is not yet supported. A real Colab connection has not been tested on an Android device.

## Keep the terminal/SSH session active in the background

A foreground service owns the PTY, shell, and bridge, so the Activity can move to the background or its task can be removed from Recents without automatically closing the session. When ColabTerm is reopened, the UI attaches to the still-running terminal session; the notification can reopen the app or **Stop shell**. The UI also has a corresponding stop control. Android 13 and later may request **Notifications** permission for the notification to appear in the notification shade; if permission is denied, Android may still list the foreground service in Task Manager.

A foreground service does not guarantee survival through force-stop, reboot, process/OS or vendor termination, network loss, or runtime expiration. The app does not rerun `colab ssh`, automatically reconnect SSH after a process exits, or create/stop a Colab runtime. Stopping the shell in the app or notification stops only the local process and bridge; it does not stop the remote runtime.

## Limitations and safety

The shell only includes Android system utilities; it does not include `pkg`/`apt`, a rootfs, root, or a Termux-style package repository. `console`/`ssh` require a saved Colab session; Android SSH does not create or stop sessions and rejects `--rm`/`--proxy-mode`. `repl` is not yet supported. Other Colab commands may create, modify, or stop remote resources, so review a command before sending it.

The OAuth token is encrypted with AES-GCM using an Android Keystore key. The app does not automatically sign in, create a Colab session, or check for updates at launch. The foreground service keeps the current shell alive while the Activity is not visible, but Android may still terminate the service or the connection may be lost under the limitations above.

## Build a debug APK

The build machine needs JDK 17 or later, Android SDK Platform 35, Android NDK, the Rust target `aarch64-linux-android`, `cargo-ndk`, and Python 3.13 (the major/minor version must match Chaquopy's embedded Python). Adjust the paths for your environment, then run:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/24.0.8215888"
export PATH="$HOME/.cargo/bin:$PATH"
# If Python 3.13 is not available as python3.13 on PATH, set the executable explicitly:
# export CHAQUOPY_BUILD_PYTHON="$(uv python find 3.13)"
./gradlew clean :app:testDebugUnitTest :app:assembleDebug
```

Gradle checks for Python 3.13, builds the Rust JNI library and `colab` helper, and assembles `app/build/outputs/apk/debug/app-debug.apk`. `assembleDebug` also verifies that the APK contains Chaquopy requirements, the pinned SSH wheels, and ARM64 native extensions. A missing Python interpreter or asset causes the build to fail instead of producing a nonfunctional APK. The current build supports only the `arm64-v8a` ABI.

## Tests and status

Rust tests cover the parser/allowlist and protocol framing. Gradle unit tests cover bridge bytes, resize, and EOF; Python unit tests use mocks to test stdin, console resize/UTF-8, the WebSocket adapter, and Android session safety. `verifyDebugChaquopyApk` checks the APK structure, Python 3.13, Paramiko/cryptography/bcrypt/PyNaCl/cffi metadata, and ARM64 native extensions.

Builds and unit tests do not prove that the PTY, execution of the helper through a symlink to `nativeLibraryDir`, OAuth, or Colab commands work on a real Android device. No Android device is connected to the build environment through ADB; test the runtime on a phone before treating this as a device-verified build.

## Diagnose a crash on a phone

The APK records startup milestones and captures unhandled Java exceptions in the app's private storage. After a crash, the next launch opens the **Diagnostics** screen instead of starting the shell again; Trung can review the report and tap **Share debug report**. The report includes the app version, device information, startup milestones, and stack trace. On Android 11 and later, the app also attempts to read the most recent exit reason and system trace provided by Android for native crashes/ANRs. On older Android versions, this API cannot retrieve the system tombstone.

The app does not actively collect OAuth tokens, terminal/file contents, or logcat. System traces and exception messages may contain paths or system data; review the report before sharing it. The report remains in the app's private data until it is shared or the user chooses **Clear report and exit**. Then reopen the app to try a normal startup. A native crash without an exit trace, force-stop, power loss, or an error before the Java handler is installed may not produce a report. This feature does not replace a bug report/ADB when full system logs are needed.

## Source and licenses

The Python Colab CLI code comes from the Android fork [google-colab-cli-android](https://github.com/phiiggfdg/google-colab-cli-android), version `0.7.2.post0+android5.3`, under Apache-2.0. Third-party notices, Termux sources, and license copies are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and bundled in the APK assets. Design references: [`docs/superpowers/specs/2026-11-21--termux-shell-ui-design.md`](docs/superpowers/specs/2026-11-21--termux-shell-ui-design.md).
