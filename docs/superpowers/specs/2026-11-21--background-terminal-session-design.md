# Design: Keep the Terminal/SSH Session Alive in the Background

## Goal

Keep the existing PTY shell session and SSH connection while the Activity moves to the background or its task is removed from Recents. When ColabTerm is reopened, reattach the UI to the same live terminal session; do not make the user rerun `colab ssh` just because the Activity closed.

## Architecture decisions

- Move ownership of `TerminalSession` and `ColabBridgeServer` from `TermuxShellActivity` to a process-wide `ShellTerminalService`.
- The Activity starts the service while visible, binds to obtain `TerminalSession`, attaches `TerminalView`, and registers a temporary listener. The Activity detaches its listener when leaving; it does not terminate the shell or close the bridge in `onDestroy()`.
- The service runs in the foreground with a persistent, quiet notification that clearly states the terminal/SSH is active; the notification has actions to open the app and stop the shell. The UI also has a stop control so the user always has an exit path. Android 13+ requires runtime permission `POST_NOTIFICATIONS` for the FGS notification to appear in the notification drawer; if permission is denied, Android still shows the service in Task Manager. Source: [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission).
- Declare `specialUse` with the description “interactive terminal/SSH session to a user-selected Colab runtime”. This is a continuous interactive task not appropriately described by `dataSync` (which has new time limits on Android) or `connectedDevice`. Android requires the type/permission from API 34; Google Play requires a description/review of the `specialUse` use case when submitting the app. Source: [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).
- Do not use `START_STICKY` to create an empty shell after the process is killed; the service only retains the live PTY object. Do not save SSH tokens/commands to reconnect automatically after the service/process is lost.
- `onTaskRemoved` does not stop the service; the **Stop shell** action in the notification/UI closes the PTY and bridge, then stops the foreground service.

## Out of scope and limitations

- Do not guarantee connection persistence after force-stop, reboot, Android/vendor termination, network loss, Colab session expiration, or the user stopping the service from the system.
- Do not rerun `colab ssh`, automatically create/stop a runtime, or change the allowlist, OAuth, token bridge, UID/shell permissions, or remote protocol.
- Do not add a keep-alive for the Colab assignment: a 15-second SSH keepalive addresses idle timeouts on the SSH/WebSocket connection; the two mechanisms are not equivalent.
- Do not change the Termux renderer, input path, font size, or scrolling before collecting device measurements.
- Do not claim background/SSH has been tested in practice without running it on a phone. Do not connect to `t4-lab` during development.

## Testing and acceptance

1. Pure unit tests check service startup/stop policy and notification/action state to the extent testable without an Android device.
2. Python regression test confirms Paramiko enables a 15-second keepalive after authentication.
3. Gradle unit tests, debug APK build, and merged-manifest checks verify the correct `specialUse`/permission and that the service is not exported.
4. Do not send real Colab commands or create/stop a runtime. Real background testing is still pending on the user's device: open an existing SSH session, background the app/remove its task, reopen it and check for the same prompt, then try **Stop shell**.
5. Clearly report the lack of an Android device/real Colab session for testing.
