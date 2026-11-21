# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import datetime
import re
import sys
import typer
from rich.console import Console
from typing import List, Optional
from typing_extensions import Annotated

from colab_cli.execution_input import (
    ExecutionBlock,
    ExecutionInputError,
    load_execution_file,
    save_block_outputs,
    write_notebook_output,
)
from colab_cli.runtime import ColabRuntime
from colab_cli.utils import handle_image, is_terminal_error, render_display_data
from colab_cli.console import connect_console

_console = Console()

ENV_KEY_REGEX = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def is_stdin_tty():
    return sys.stdin.isatty()


def _parse_env_vars(env: Optional[List[str]]) -> dict[str, str]:
    """Parse repeatable --env KEY=VALUE entries into an ordered mapping."""
    env_vars = {}
    for item in env or []:
        if "=" not in item:
            typer.echo(
                f"[colab] Invalid --env value {item!r}. Expected KEY=VALUE.",
                err=True,
            )
            raise typer.Exit(2)

        key, value = item.split("=", 1)
        if not ENV_KEY_REGEX.fullmatch(key):
            typer.echo(
                f"[colab] Invalid --env key {key!r}. Expected a valid "
                "environment variable name.",
                err=True,
            )
            raise typer.Exit(2)

        env_vars[key] = value
    return env_vars


def _build_env_prelude(env_vars: dict[str, str]) -> str:
    """Build Python source that sets environment variables in the remote kernel."""
    if not env_vars:
        return ""

    lines = ["import os"]
    lines.extend(f"os.environ[{key!r}] = {value!r}" for key, value in env_vars.items())
    return "\n".join(lines) + "\n"



def display_output(out, output_image=None):
    if out.get("output_type") == "stream":
        stream = sys.stderr if out.get("name") == "stderr" else sys.stdout
        stream.write(out.get("text", ""))
        stream.flush()
    elif "data" in out:
        data = out["data"]
        text = render_display_data(data)
        if text is not None:
            _console.print(text)
        if png := data.get("image/png"):
            handle_image(png, "image/png", target_path=output_image)
        elif jpeg := data.get("image/jpeg"):
            handle_image(jpeg, "image/jpeg", target_path=output_image)
    elif out.get("output_type") == "error":
        tb = out.get("traceback", [])
        if tb:
            sys.stderr.write("".join(tb) + "\n")
        else:
            ename = out.get("ename", "Error")
            evalue = out.get("evalue", "")
            sys.stderr.write(f"{ename}: {evalue}\n")
    else:
        # Ignore silent outputs like metadata or clear_output for streaming
        pass


def exec_command(
    session: Annotated[
        Optional[str], typer.Option("-s", "--session", help="Session name")
    ] = None,
    file: Annotated[
        Optional[str], typer.Option("-f", "--file", help="File to execute")
    ] = None,
    output_image: Annotated[
        Optional[str], typer.Option("--output-image", help="Path to save plot")
    ] = None,
    timeout: Annotated[
        Optional[float],
        typer.Option("--timeout", help="Timeout in seconds for code execution"),
    ] = 30.0,
    env: Annotated[
        Optional[List[str]],
        typer.Option(
            "--env",
            help=(
                "Set an environment variable in the remote kernel as KEY=VALUE. "
                "Repeat for multiple variables."
            ),
        ),
    ] = None,
):
    """Execute code in a session"""
    from colab_cli.common import state

    env_vars = _parse_env_vars(env)
    name = state.resolve_session(session)
    s = state.store.get(name)
    if not s:
        typer.echo(f"[colab] Session '{name}' not found.")
        raise typer.Exit(1)

    loaded = None
    if file:
        try:
            loaded = load_execution_file(file)
        except ExecutionInputError as exc:
            typer.echo(f"[colab] {exc}", err=True)
            raise typer.Exit(2)
        if loaded.is_notebook:
            typer.echo(f"[colab] Parsing notebook '{file}'...")
        code_blocks = loaded.blocks
    else:
        if is_stdin_tty():
            typer.echo("[colab] Error: No input provided. Pipe code or provide a file.")
            raise typer.Exit(1)
        code_blocks = [ExecutionBlock(code=sys.stdin.read())]

    if not any(block.code.strip() for block in code_blocks):
        raise typer.Exit(0)

    def on_started(kid):
        s.kernel_id = kid
        state.store.update(name, kernel_id=kid)

    def on_sess_started(sid):
        s.session_id = sid
        state.store.update(name, session_id=sid)

    runtime = ColabRuntime(
        s.url,
        s.token,
        kernel_id=s.kernel_id,
        session_id=s.session_id,
        on_kernel_started=on_started,
        on_session_started=on_sess_started,
    )
    try:
        # Ensure we are in /content which is the standard Colab working directory
        runtime.execute_code(
            "import os; os.makedirs('/content', exist_ok=True); os.chdir('/content')"
        )
    except Exception as e:
        if is_terminal_error(e):
            if state.prune_session(name):
                typer.echo(
                    f"[colab] Session '{name}' is no longer assigned; local state was cleaned up."
                )
            else:
                typer.echo(
                    f"[colab] Runtime proxy for session '{name}' returned 404/401, "
                    "but the assignment is still alive. Credentials were refreshed; retry the command.",
                    err=True,
                )
            raise typer.Exit(1)
        raise e

    try:
        is_nb = bool(loaded and loaded.is_notebook)
        s.running = f"exec({file or 'stdin'})"
        state.store.update(name, running=s.running)

        for i, block in enumerate(code_blocks):
            code = _build_env_prelude(env_vars) + block.code
            identifier = block.identifier if is_nb else None
            if is_nb:
                identifier_str = f" - {identifier}" if identifier else ""
                typer.echo(
                    f"[colab] Executing cell {i + 1}/{len(code_blocks)}{identifier_str}..."
                )

            s.last_execution = (
                file or "stdin",
                identifier,
                datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            )
            state.store.update(name, last_execution=s.last_execution)

            outputs = runtime.execute_code(
                code,
                output_hook=lambda o: display_output(o, output_image),
                timeout=timeout,
            )
            if is_nb:
                save_block_outputs(outputs, block)
            state.history.log_event(
                name,
                "execution",
                {
                    "code": code,
                    "outputs": outputs,
                    "cell_index": i if len(code_blocks) > 1 else None,
                    "cell_id": block.cell_id,
                },
            )
    finally:
        s.running = None
        state.store.update(name, running=None)
        runtime.stop()
        if loaded and loaded.is_notebook:
            output_file = loaded.output_path
            typer.echo(f"[colab] Saving notebook with outputs to '{output_file}'...")
            write_notebook_output(loaded)


def repl(
    session: Annotated[
        Optional[str], typer.Option("-s", "--session", help="Session name")
    ] = None,
    output_image: Annotated[
        Optional[str], typer.Option("--output-image", help="Path to save plot")
    ] = None,
):
    """Start an interactive REPL"""
    from colab_cli.common import state

    name = state.resolve_session(session)
    s = state.store.get(name)
    if not s:
        typer.echo(f"[colab] Session '{name}' not found.")
        raise typer.Exit(1)

    def on_started(kid):
        s.kernel_id = kid
        state.store.update(name, kernel_id=kid)

    def on_sess_started(sid):
        s.session_id = sid
        state.store.update(name, session_id=sid)

    runtime = ColabRuntime(
        s.url,
        s.token,
        kernel_id=s.kernel_id,
        session_id=s.session_id,
        on_kernel_started=on_started,
        on_session_started=on_sess_started,
    )
    try:
        # Ensure we are in /content which is the standard Colab working directory
        runtime.execute_code(
            "import os; os.makedirs('/content', exist_ok=True); os.chdir('/content')"
        )
    except Exception as e:
        if is_terminal_error(e):
            if state.prune_session(name):
                typer.echo(
                    f"[colab] Session '{name}' is no longer assigned; local state was cleaned up."
                )
            else:
                typer.echo(
                    f"[colab] Runtime proxy for session '{name}' returned 404/401, "
                    "but the assignment is still alive. Credentials were refreshed; retry the command.",
                    err=True,
                )
            raise typer.Exit(1)
        raise e

    if not is_stdin_tty():
        code = sys.stdin.read()
        if not code.strip():
            raise typer.Exit(0)

        s.last_execution = (
            "stdin",
            None,
            datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        )
        s.running = "repl(stdin)"
        state.store.update(
            name, running=s.running, last_execution=s.last_execution
        )
        try:
            outputs = runtime.execute_code(
                code, output_hook=lambda o: display_output(o, output_image)
            )
            state.history.log_event(
                name, "execution", {"code": code, "outputs": outputs, "source": "piped"}
            )
        finally:
            s.running = None
            state.store.update(name, running=None)
            runtime.stop()
    else:
        from colab_cli.repl import ColabREPL

        s.running = "repl"
        state.store.update(name, running=s.running)
        try:
            repl_inst = ColabREPL(
                runtime,
                session_name=s.name,
                history_logger=state.history,
                output_image=output_image,
            )
            state.history.log_event(name, "repl_started", {})
            repl_inst.run()
        finally:
            s.running = None
            state.store.update(name, running=None)


def console(
    session: Annotated[
        Optional[str], typer.Option("-s", "--session", help="Session name")
    ] = None,
):
    """Connect to raw TTY console"""
    from colab_cli.common import state
    from colab_cli.terminal_io import get_bridge

    name = state.resolve_session(session)
    s = state.store.get(name)
    if not s:
        typer.echo(f"[colab] Session '{name}' not found.")
        raise typer.Exit(1)
    state.history.log_event(s.name, "console_started", {})
    s.running = "console"
    state.store.update(name, running=s.running)
    try:
        connect_console(s, bridge=get_bridge())
    except Exception as e:
        if is_terminal_error(e):
            if state.prune_session(name):
                typer.echo(
                    f"[colab] Session '{name}' is no longer assigned; local state was cleaned up."
                )
            else:
                typer.echo(
                    f"[colab] Runtime proxy for session '{name}' returned 404/401, "
                    "but the assignment is still alive. Credentials were refreshed; retry the command.",
                    err=True,
                )
            raise typer.Exit(1)
        raise e
    finally:
        s.running = None
        state.store.update(name, running=None)


def register(app: typer.Typer):
    app.command(name="exec")(exec_command)
    app.command()(repl)
    app.command()(console)
