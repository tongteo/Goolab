# Plan: Keep the Terminal/SSH Session Alive in the Background

## Constraints

- Preserve the existing permissions, loopback/token bridge, `/system/bin/sh` shell, parser/allowlist, and SSH flow.
- Do not run commands against the `t4-lab` session; do not create/stop a runtime; do not automatically reconnect after process death.
- The handoff directory has no `.git`; do not reset/clean it, and preserve all files except those explicitly listed for modification below.
- Android API 34+ requires a foreground-service type; use `specialUse` with an ongoing notification and stop action. A Google Play release will require declaring/reviewing the use case.

## Steps

1. **Design recorded:** `docs/superpowers/specs/2026-11-21--background-terminal-session-design.md`; the service owns the PTY/bridge, and the Activity only binds/attaches.
2. **Implement the service lifecycle first:** add `ShellTerminalService.java` with a LocalBinder, create the foreground notification early, create/retain `TerminalSession` + `ColabBridgeServer`, deliver callbacks safely to the UI listener, and support explicit stop; do not sticky-restart an empty shell. Check these boundaries before extracting code from the Activity.
3. **Bind/detach the Activity:** `TermuxShellActivity.java` starts/binds the service while visible, reattaches to the running session, holds the UI client through a weak reference, and clears/unbinds in `onStop/onDestroy`; the Activity does not close the session/bridge. Add **New shell** and **Stop** buttons.
4. **Android declaration/notification:** declare the service in `AndroidManifest.xml` with `exported=false`, `foregroundServiceType=specialUse`, permission, and subtype metadata. The quiet notification opens the app or stops the shell. Android 13+ can request `POST_NOTIFICATIONS`; if denied, explain that the service may still appear in Task Manager. Do not put tokens/secrets in the notification.
5. **Testing:** run the Python mock suite and Gradle unit tests/debug APK build; Gradle was invoked with `-x :app:installDebugPythonRequirements` because Python 3.13 was missing on the machine. Check the merged manifest/APK. Do not run `cargo` because Rust is unchanged. There is no automated test that simulates the Android Service lifecycle.
6. **Review:** inspect listener leaks, Activity destroy/reattach, stop action, service running after the shell exits, permissions/types for API 24–36, and bridge security. There is no `.git`; review manually by reading code, grepping, and building.
7. **Handoff:** no Android device or real Colab session is available in the sandbox. After installing the new APK, test on the phone with an existing SSH session: move the app to the background, remove its task from Recents, reopen it to confirm the terminal prompt remains, then try stopping from the app/notification. Do not call `colab ssh` during build/test.

## Acceptance criteria

- The Activity can be destroyed/recreated without ending the PTY/SSH session or closing the bridge while the foreground service is alive.
- The foreground notification can return to the app or explicitly stop the shell.
- Stopping from the notification/UI closes the session and bridge exactly once.
- Unit tests and build pass, or an environment blocker is accurately reported; no unrelated Android permissions are added.
- Do not claim survival through force-stop/process death/Android kill; there is no automatic reconnect.
