# Design: Termux Terminal with Colab CLI in the Shell

## Goal

Use the upstream Termux terminal UI/PTY as the primary screen while allowing `colab ...` to be entered directly at the shell prompt and execute the Google Colab CLI Python code embedded with Chaquopy. The existing Colab CLI screen may remain as a secondary path.

## Selected architecture

The app uses upstream `TerminalView` and its `terminal-emulator` dependency pinned to Termux `v0.118.3`, fetched through JitPack. It does not integrate the Termux app or the `termux-shared` module: the Termux app repository is GPLv3, while the two terminal libraries have an Apache-2.0 exception. Preserve upstream attribution and licenses in the notices.

The shell is `/system/bin/sh -i`, running through a PTY in a child process under the app's UID/SELinux sandbox, without root. HOME and TMPDIR are in the app's private data/cache area; PATH includes the app's `files/bin` directory, followed by `/system/bin:/system/xbin`. Do not grant additional Android permissions.

A native ARM64 helper named `colab` is copied from the APK to `files/bin`. It sends argv and stdin over TCP loopback to a local Java bridge; a random port and a random 256-bit token are passed only to the shell process. The bridge checks argv through the existing Rust parser/allowlist, then calls `colab_runner` in Chaquopy. Output, exit codes, and input prompts are relayed back through the PTY. Do not pass a user command to `/system/bin/sh -c`.

The bridge accepts loopback connections only, authenticates the token, and limits request size; Python calls are serialized because the runner temporarily replaces `sys.stdout`/`builtins.input`. The token is not logged. `colab` only runs subcommands on the current allowlist; the system shell remains a local shell inside the sandbox.

## Limitations

This is not a complete Termux environment: there is no rootfs, `pkg`/`apt`, root, package repository, or background daemon. The environment contains only the available Android system commands, embedded Python/Colab CLI, and the `colab` wrapper. The session may be lost when the Activity/app is terminated. A real device test is needed to verify the PTY and execution of the ELF from private app data; building the APK does not replace that verification.

## Main files

- `settings.gradle`, `app/build.gradle`: JitPack, terminal libraries, and staging the native helper into assets.
- `native/src/bin/colab.rs`: TCP/stdio client with framing and exit code; `native/src/lib.rs`: the existing allowlist remains the command control gate.
- `app/src/main/java/dev/trung/colabterm/TermuxShellActivity.java`: Termux TerminalView, PTY shell, helper path/install, and lifecycle.
- `app/src/main/java/dev/trung/colabterm/ColabBridgeServer.java`: authenticated loopback bridge, protocol validation, Chaquopy calls, and prompt/input relay.
- `app/src/main/java/dev/trung/colabterm/ShellConfig.java`, `app/src/test/java/dev/trung/colabterm/ShellConfigTest.java`: shell argv/environment.
- `app/src/main/java/dev/trung/colabterm/MainActivity.java`, `app/src/main/python/colab_runner.py`: reuse the parser and support both the existing screen and bridge argv.
- `AndroidManifest.xml`, `README.md`, `THIRD_PARTY_NOTICES.md`: launcher, limitations, sources/licenses.

## Acceptance

1. Unit tests confirm shell environment/path and Rust protocol framing/argv.
2. Rust formatter/test suite passes.
3. Gradle unit tests and ARM64 debug APK build pass.
4. The APK contains `TerminalView`, native PTY `libtermux.so`, Rust JNI, and executable `colab`.
5. The manifest adds no Android permissions. The bridge binds only to loopback, does not log the token, and the Rust parser rejects commands outside the allowlist.
6. Do not claim Android runtime functionality without installing and testing it on a device.

## Upstream sources

- https://github.com/termux/termux-app
- https://raw.githubusercontent.com/termux/termux-app/v0.118.3/LICENSE.md
- https://github.com/termux/termux-app/wiki/Termux-Libraries
- https://github.com/termux/termux-app/tree/v0.118.3/terminal-view
