import json
import socket
import unittest
from unittest import mock

import typer
import websocket

from colab_cli import console
from colab_cli.commands import ssh as ssh_command
from colab_cli.terminal_io import reset_bridge, set_bridge
from colab_runner import _TerminalReader, _create_terminal_output, _terminal_input


class ByteBridge:
    def __init__(self, payload):
        self.payload = bytearray(payload)
        self.line_results = []

    def readTerminalBytesForPython(self, maximum_bytes):
        if maximum_bytes < 0:
            count = len(self.payload)
        else:
            count = min(maximum_bytes, len(self.payload))
        result = bytes(self.payload[:count])
        del self.payload[:count]
        return result

    def readLineForPython(self, prompt):
        return self.line_results.pop(0)


class InteractiveTerminalTests(unittest.TestCase):
    def test_terminal_output_batches_fragments_and_flushes_before_blocking_read(self):
        class RecordingBridge(ByteBridge):
            def __init__(self):
                super().__init__(b"answer\n")
                self.output = []
                self.events = []

            def appendTerminalBytes(self, payload):
                self.output.append(bytes(payload))
                self.events.append("output")

            def readTerminalBytesForPython(self, maximum_bytes):
                self.events.append("input")
                return super().readTerminalBytesForPython(maximum_bytes)

        bridge = RecordingBridge()
        output = _create_terminal_output(bridge)

        output.write("ready")
        self.assertEqual(bridge.output, [])
        output.write(" now\n")
        self.assertEqual(bridge.output, [b"ready now\n"])

        output.write("prompt>")
        reader = _TerminalReader(bridge, before_read=output.flush)
        self.assertEqual(reader.readline(), "answer\n")
        self.assertEqual(bridge.output, [b"ready now\n", b"prompt>"])
        self.assertEqual(bridge.events, ["output", "output"] + ["input"] * 7)
        output.close()

    def test_text_reader_reads_all_lines_and_preserves_bounded_read_remainder(self):
        reader = _TerminalReader(ByteBridge(b"first\nsecond\nthird"))

        self.assertEqual(reader.read(3), "fir")
        self.assertEqual(reader.read(), "st\nsecond\nthird")
        self.assertEqual(reader.read(), "")

    def test_text_reader_readline_keeps_line_endings_and_eof(self):
        reader = _TerminalReader(ByteBridge(b"first\nsecond"))

        self.assertEqual(reader.readline(), "first\n")
        self.assertEqual(reader.readline(), "second")
        self.assertEqual(reader.readline(), "")

    def test_input_distinguishes_blank_line_from_eof(self):
        bridge = ByteBridge(b"")
        bridge.line_results = ["", None]

        self.assertEqual(_terminal_input(bridge), "")
        with self.assertRaises(EOFError):
            _terminal_input(bridge)

    def test_console_decodes_utf8_across_input_frames_and_sends_resize(self):
        class Bridge:
            def __init__(self):
                self.events = ["I", "I", "R", "C"]
                self.payloads = [b"\xc3", b"\xa9"]
                self.size = (80, 24)

            def getTerminalSizeForPython(self):
                return self.size

            def readTerminalEventTypeForPython(self):
                event = self.events.pop(0)
                if event == "R":
                    self.size = (100, 30)
                return event

            def readTerminalInputBytesForPython(self):
                return self.payloads.pop(0)

        class WebSocket:
            def __init__(self):
                self.sent = []
                self.closed = False

            def send(self, value):
                self.sent.append(value)

            def close(self):
                self.closed = True
                console.on_close(self, None, None)

        class ImmediateThread:
            def __init__(self, target, daemon=False):
                self.target = target

            def start(self):
                self.target()

        bridge = Bridge()
        ws = WebSocket()
        with mock.patch.object(console.threading, "Thread", ImmediateThread):
            console.on_open(ws, bridge)

        messages = [json.loads(value) for value in ws.sent]
        self.assertEqual(messages, [
            {"cols": 80, "rows": 24},
            {"data": "é"},
            {"cols": 100, "rows": 30},
        ])
        self.assertTrue(ws.closed)

    def test_android_close_frame_still_closes_underlying_websocket(self):
        class WebSocket:
            def __init__(self):
                self.closed = False
                self.frames = [(websocket.ABNF.OPCODE_CLOSE, b"")]

            def recv_data(self, control_frame=False):
                return self.frames.pop(0)

            def close(self):
                self.closed = True

        ws = WebSocket()
        adapter = ssh_command._WebSocketSocketAdapter(ws)

        self.assertEqual(adapter.recv(10), b"")
        adapter.close()
        self.assertTrue(ws.closed)

    def test_websocket_adapter_preserves_stream_bytes_and_maps_timeout(self):
        class WebSocket:
            def __init__(self):
                self.sent = []
                self.frames = [
                    (websocket.ABNF.OPCODE_BINARY, b"abc"),
                    (websocket.ABNF.OPCODE_TEXT, "def"),
                ]
                self.timeout = None

            def recv_data(self, control_frame=False):
                return self.frames.pop(0)

            def send_binary(self, payload):
                self.sent.append(payload)

            def settimeout(self, timeout):
                self.timeout = timeout

        ws = WebSocket()
        adapter = ssh_command._WebSocketSocketAdapter(ws)

        self.assertEqual(adapter.recv(2), b"ab")
        self.assertEqual(adapter.recv(10), b"c")
        self.assertEqual(adapter.recv(10), b"def")
        self.assertEqual(adapter.send(b"client"), 6)
        self.assertEqual(ws.sent, [b"client"])
        adapter.settimeout(0.25)
        self.assertEqual(adapter.gettimeout(), 0.25)
        self.assertEqual(ws.timeout, 0.25)

        class TimeoutWebSocket:
            def recv_data(self, control_frame=False):
                raise websocket.WebSocketTimeoutException("timeout")

        with self.assertRaises(socket.timeout):
            ssh_command._WebSocketSocketAdapter(TimeoutWebSocket()).recv(1)

    def test_android_ssh_enables_periodic_keepalive(self):
        from types import SimpleNamespace

        class Key:
            def get_name(self):
                return "ecdsa-sha2-nistp256"

            def get_base64(self):
                return "test-public-key"

        class Channel:
            def get_pty(self, **kwargs):
                pass

            def exec_command(self, command):
                pass

            def recv_ready(self):
                return False

            def recv_stderr_ready(self):
                return False

            def exit_status_ready(self):
                return True

            def recv_exit_status(self):
                return 0

            def close(self):
                pass

        transports = []

        class Transport:
            def __init__(self, adapter):
                self.keepalive_intervals = []
                self.channel = Channel()
                transports.append(self)

            def start_client(self, timeout):
                pass

            def auth_publickey(self, username, key):
                pass

            def is_authenticated(self):
                return True

            def set_keepalive(self, interval):
                self.keepalive_intervals.append(interval)

            def open_session(self, timeout):
                return self.channel

            def is_active(self):
                return True

            def close(self):
                pass

        class WebSocket:
            def close(self):
                pass

        class Bridge:
            def getTerminalSizeForPython(self):
                return (80, 24)

            def readTerminalEventTypeForPython(self):
                return "C"

        fake_paramiko = SimpleNamespace(Transport=Transport)
        with mock.patch.dict("sys.modules", {"paramiko": fake_paramiko}):
            with mock.patch.object(ssh_command, "_load_android_ssh_key", return_value=Key()):
                with mock.patch.object(
                    ssh_command, "_connect_websocket", return_value=WebSocket()
                ):
                    result = ssh_command._run_android_interactive_ssh(
                        SimpleNamespace(url="https://example.test", token="test"),
                        None,
                        Bridge(),
                    )

        self.assertEqual(result, 0)
        self.assertEqual(len(transports), 1)
        self.assertEqual(transports[0].keepalive_intervals, [15])

    def test_android_rm_is_rejected_without_resolving_or_stopping_a_session(self):
        token = set_bridge(object())
        try:
            with mock.patch.object(ssh_command, "_resolve_session") as resolve:
                with mock.patch.object(ssh_command, "_stop_session") as stop:
                    with mock.patch.object(ssh_command, "_auto_create_session") as create:
                        with self.assertRaises(typer.Exit) as raised:
                            ssh_command.ssh(
                                session="t4-lab",
                                proxy_mode=False,
                                identity=None,
                                gpu=None,
                                tpu=None,
                                high_mem=False,
                                rm=True,
                            )

            self.assertEqual(raised.exception.exit_code, 2)
            resolve.assert_not_called()
            stop.assert_not_called()
            create.assert_not_called()
        finally:
            reset_bridge(token)

    def test_android_ssh_resolves_saved_session_without_create_or_stop(self):
        bridge = object()
        session = object()
        token = set_bridge(bridge)
        try:
            with mock.patch.object(
                ssh_command, "_resolve_session", return_value=session
            ) as resolve:
                with mock.patch.object(
                    ssh_command, "_run_android_interactive_ssh", return_value=0
                ) as run_ssh:
                    with mock.patch.object(ssh_command, "_auto_create_session") as create:
                        with mock.patch.object(ssh_command, "_stop_session") as stop:
                            with self.assertRaises(typer.Exit) as raised:
                                ssh_command.ssh(
                                    session="t4-lab",
                                    proxy_mode=False,
                                    identity=None,
                                    gpu=None,
                                    tpu=None,
                                    high_mem=False,
                                    rm=False,
                                )

            self.assertEqual(raised.exception.exit_code, 0)
            resolve.assert_called_once_with("t4-lab")
            run_ssh.assert_called_once_with(session, None, bridge)
            create.assert_not_called()
            stop.assert_not_called()
        finally:
            reset_bridge(token)


if __name__ == "__main__":
    unittest.main()
