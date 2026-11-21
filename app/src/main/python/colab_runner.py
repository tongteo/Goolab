"""Run the official Colab CLI inside the embedded Python interpreter."""

import builtins
import codecs
import contextlib
import io
import sys

from colab_cli.terminal_io import reset_bridge, set_bridge


class _TerminalRawWriter(io.RawIOBase):
    def __init__(self, bridge):
        self.bridge = bridge

    def writable(self):
        return True

    def isatty(self):
        return False

    def write(self, value):
        payload = bytes(value)
        if payload:
            self.bridge.appendTerminalBytes(payload)
        return len(payload)

    def flush(self):
        return None


def _create_terminal_output(bridge):
    raw_output = _TerminalRawWriter(bridge)
    output_buffer = io.BufferedWriter(raw_output, buffer_size=4096)
    return io.TextIOWrapper(
        output_buffer,
        encoding="utf-8",
        errors="replace",
        line_buffering=True,
    )


class _TerminalReader(io.TextIOBase):
    def __init__(self, bridge, before_read=None):
        self.bridge = bridge
        self._before_read = before_read
        self._pending = ""
        self._eof = False
        self._decoder = io.IncrementalNewlineDecoder(
            codecs.getincrementaldecoder("utf-8")("replace"), translate=True
        )

    @property
    def encoding(self):
        return "utf-8"

    def readable(self):
        return True

    def isatty(self):
        return False

    def _fill(self, minimum_chars=1, read_to_eof=False):
        while not self._eof and (
            read_to_eof or len(self._pending) < minimum_chars
        ):
            maximum_bytes = (
                -1
                if read_to_eof
                else max(1, minimum_chars - len(self._pending))
            )
            if self._before_read is not None:
                self._before_read()
            data = bytes(self.bridge.readTerminalBytesForPython(maximum_bytes))
            if data:
                self._pending += self._decoder.decode(data, final=False)
            else:
                self._pending += self._decoder.decode(b"", final=True)
                self._eof = True

    def readline(self, size=-1):
        if size == 0:
            return ""
        result = []
        while size < 0 or len(result) < size:
            if not self._pending:
                self._fill(1)
                if not self._pending:
                    break
            character, self._pending = self._pending[0], self._pending[1:]
            result.append(character)
            if character == "\n":
                break
        return "".join(result)

    def read(self, size=-1):
        if size == 0:
            return ""
        if size < 0:
            self._fill(read_to_eof=True)
            result, self._pending = self._pending, ""
            return result
        self._fill(size)
        result, self._pending = self._pending[:size], self._pending[size:]
        return result


def _terminal_input(bridge, prompt=""):
    value = bridge.readLineForPython(str(prompt))
    if value is None:
        raise EOFError
    return value


def run_colab(args_text, bridge):
    """Run normalized argv (one argument per line) without launching a shell."""
    args = args_text.split("\n") if args_text else ["--help"]
    output = _create_terminal_output(bridge)
    original_input = builtins.input
    original_stdin = sys.stdin
    bridge_token = set_bridge(bridge)

    try:
        def terminal_input(prompt=""):
            output.flush()
            return _terminal_input(bridge, prompt)

        builtins.input = terminal_input
        sys.stdin = _TerminalReader(bridge, before_read=output.flush)
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            from typer.main import get_command
            from colab_cli.cli import app

            command = get_command(app)
            command.main(args=args, prog_name="colab", standalone_mode=False)
        return 0
    except SystemExit as exc:
        return int(exc.code) if isinstance(exc.code, int) else 0
    except Exception as exc:
        try:
            output.flush()
        except Exception:
            pass
        bridge.appendTerminalText(
            f"\n[ColabTerm] {type(exc).__name__}: {exc}\n"
        )
        return 1
    finally:
        builtins.input = original_input
        sys.stdin = original_stdin
        try:
            output.flush()
        except Exception:
            pass
        reset_bridge(bridge_token)
