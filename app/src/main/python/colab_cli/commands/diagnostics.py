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

"""Self-diagnostics for the Android/Termux compatibility build."""

from __future__ import annotations

import io
import os
import platform
import sys
from typing import Callable, Optional

import typer
from typing_extensions import Annotated

from colab_cli import ipynb_lite, pydantic_lite, ws_kernel
from colab_cli.consumption import CcuInfo
from colab_cli.runtime import ColabRuntime


Check = tuple[str, Callable[[], None]]


def _check_environment() -> None:
    if sys.version_info < (3, 12):
        raise RuntimeError("Python 3.12 or newer is required")

    # Verify that the runtime is wired to the compatibility transport rather
    # than accidentally importing the upstream native dependency chain.
    import colab_cli.runtime as runtime_module

    if runtime_module.jupyter_kernel_client is not ws_kernel:
        raise RuntimeError("runtime.py is not using colab_cli.ws_kernel")

    from colab_cli import client, state

    if client.BaseModel is not pydantic_lite.BaseModel:
        raise RuntimeError("client.py is not using colab_cli.pydantic_lite")
    if state.BaseModel is not pydantic_lite.BaseModel:
        raise RuntimeError("state.py is not using colab_cli.pydantic_lite")


def _check_notebook() -> None:
    nb = ipynb_lite.v4.new_notebook()
    nb.cells.append(ipynb_lite.v4.new_code_cell("print('ok')"))
    buf = io.StringIO()
    ipynb_lite.write(nb, buf)
    buf.seek(0)
    loaded = ipynb_lite.read(buf, as_version=4)
    if loaded.nbformat != 4 or loaded.cells[0].source != "print('ok')":
        raise RuntimeError("notebook JSON round-trip failed")


def _check_models() -> None:
    info = CcuInfo(
        currentBalance="12.5",
        consumptionRateHourly="0.25",
        assignmentsCount="2",
        ignoredFutureField=True,
    )
    if info.current_balance != 12.5:
        raise RuntimeError("alias/float parsing failed")
    if info.consumption_rate_hourly != 0.25:
        raise RuntimeError("hourly-rate parsing failed")
    if info.assignments_count != 2:
        raise RuntimeError("integer parsing failed")


def _check_kernel_codec() -> None:
    msg = {
        "header": {"msg_type": "stream"},
        "content": {"name": "stdout", "text": "hello\n"},
    }
    output = ws_kernel._iopub_to_output(msg)
    if output != {"output_type": "stream", "name": "stdout", "text": "hello\n"}:
        raise RuntimeError("Jupyter stream-output conversion failed")


def _live_kernel_check(session: str, timeout: float) -> None:
    from colab_cli.common import state

    saved = state.store.get(session)
    if not saved:
        raise RuntimeError(f"local session '{session}' was not found")

    runtime = ColabRuntime(
        saved.url,
        saved.token,
        kernel_id=saved.kernel_id,
        session_id=saved.session_id,
    )
    try:
        outputs = runtime.execute_code(
            "print('COLAB_ANDROID_TEST_OK')", timeout=timeout
        )
    finally:
        runtime.stop()

    text = "".join(
        str(item.get("text", ""))
        for item in outputs
        if item.get("output_type") == "stream"
    )
    if "COLAB_ANDROID_TEST_OK" not in text:
        raise RuntimeError(f"unexpected kernel output: {outputs!r}")


def test_command(
    check: Annotated[
        str,
        typer.Argument(
            help=(
                "Check to run: all, environment, notebook, models, kernel-codec, "
                "or kernel."
            )
        ),
    ] = "all",
    session: Annotated[
        Optional[str],
        typer.Option(
            "-s",
            "--session",
            help="Existing Colab session name. Required for the live kernel check.",
        ),
    ] = None,
    timeout: Annotated[
        float,
        typer.Option("--timeout", help="Timeout in seconds for the live kernel check."),
    ] = 30.0,
):
    """Run Android/Termux compatibility diagnostics."""

    local_checks: list[Check] = [
        ("environment", _check_environment),
        ("notebook", _check_notebook),
        ("models", _check_models),
        ("kernel-codec", _check_kernel_codec),
    ]
    allowed = {name for name, _ in local_checks} | {"all", "kernel"}
    if check not in allowed:
        typer.echo(
            f"[colab test] Unknown check '{check}'. Choose: {', '.join(sorted(allowed))}",
            err=True,
        )
        raise typer.Exit(2)

    typer.echo("Colab CLI Android/Termux diagnostics")
    typer.echo(f"Python:   {platform.python_version()}")
    typer.echo(f"Platform: {platform.platform()}")
    if os.environ.get("TERMUX_VERSION") or "com.termux" in os.environ.get("PREFIX", ""):
        typer.echo("Runtime:  Termux detected")

    selected = local_checks if check == "all" else [c for c in local_checks if c[0] == check]
    failures = 0
    for name, fn in selected:
        try:
            fn()
        except Exception as exc:
            failures += 1
            typer.echo(f"[FAIL] {name}: {exc}")
        else:
            typer.echo(f"[PASS] {name}")

    if check == "kernel":
        if not session:
            typer.echo("[FAIL] kernel: pass an existing session with -s/--session")
            failures += 1
        else:
            try:
                _live_kernel_check(session, timeout)
            except Exception as exc:
                failures += 1
                typer.echo(f"[FAIL] kernel: {exc}")
            else:
                typer.echo(f"[PASS] kernel: live execution succeeded on '{session}'")

    if check == "all":
        typer.echo(
            "Live kernel execution is intentionally not run by default. "
            "Use: colab test kernel -s <session>"
        )

    if failures:
        raise typer.Exit(1)


def register(app: typer.Typer):
    app.command(name="test")(test_command)
