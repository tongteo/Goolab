"""Shared local execution-input handling for ``colab exec`` and ``colab run``.

Keep file parsing at the local boundary so malformed/unreadable inputs are
rejected before commands allocate or touch remote resources. Notebook parsing
and output persistence live here to prevent ``exec`` and ``run`` from growing
separate, subtly different implementations.
"""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
import re
from typing import Any, Optional

from colab_cli import ipynb_lite as nbformat
from colab_cli.ipynb_lite import new_output

_TITLE_REGEX = re.compile(r"^\s*#\s*@title\s+(.*)", re.MULTILINE)


class ExecutionInputError(ValueError):
    """A local script/notebook could not be read as executable input."""


@dataclass
class ExecutionBlock:
    code: str
    cell_id: Optional[str] = None
    cell: Optional[Any] = None

    @property
    def identifier(self) -> Optional[str]:
        title_match = _TITLE_REGEX.search(self.code)
        if title_match:
            return title_match.group(1).strip()
        return self.cell_id


@dataclass
class ExecutionInput:
    path: str
    blocks: list[ExecutionBlock]
    notebook: Optional[Any] = None

    @property
    def is_notebook(self) -> bool:
        return self.notebook is not None

    @property
    def output_path(self) -> Optional[str]:
        if not self.is_notebook:
            return None
        root, _ = os.path.splitext(self.path)
        return root + "_output.ipynb"


def _normalize_cell_source(source: Any) -> str:
    if source is None:
        return ""
    if isinstance(source, str):
        return source
    if isinstance(source, list) and all(isinstance(part, str) for part in source):
        return "".join(source)
    raise ExecutionInputError(
        "Notebook code cell 'source' must be a string or list of strings."
    )


def load_execution_file(path: str) -> ExecutionInput:
    """Read a local Python script or nbformat-v4 notebook exactly once."""
    try:
        if path.lower().endswith(".ipynb"):
            with open(path, "r", encoding="utf-8") as file_obj:
                nb = nbformat.read(file_obj, as_version=4)

            cells = getattr(nb, "cells", None)
            if not isinstance(cells, list):
                raise ExecutionInputError("Notebook must contain a 'cells' list.")
            for cell in cells:
                if not isinstance(cell, dict):
                    raise ExecutionInputError("Notebook cells must be JSON objects.")

            nbformat.ensure_cell_ids(nb)
            blocks: list[ExecutionBlock] = []
            for cell in cells:
                if getattr(cell, "cell_type", None) != "code":
                    continue
                blocks.append(
                    ExecutionBlock(
                        code=_normalize_cell_source(getattr(cell, "source", "")),
                        cell_id=getattr(cell, "id", None),
                        cell=cell,
                    )
                )
            return ExecutionInput(path=path, blocks=blocks, notebook=nb)

        with open(path, "r", encoding="utf-8") as file_obj:
            return ExecutionInput(
                path=path,
                blocks=[ExecutionBlock(code=file_obj.read())],
            )
    except ExecutionInputError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError, TypeError) as exc:
        raise ExecutionInputError(f"Could not read {path!r}: {exc}") from exc


def save_block_outputs(outputs, block: ExecutionBlock) -> None:
    """Replace one notebook code cell's stored outputs with kernel outputs."""
    cell = block.cell
    if cell is None:
        return

    if not hasattr(cell, "outputs"):
        cell.outputs = []
    else:
        cell.outputs.clear()

    for out in outputs:
        if out.get("output_type") == "stream":
            cell.outputs.append(
                new_output(
                    output_type="stream",
                    name=out.get("name", "stdout"),
                    text=out.get("text", ""),
                )
            )
        elif "data" in out:
            output_type = out.get("output_type", "display_data")
            extra = (
                {"execution_count": out.get("execution_count")}
                if output_type == "execute_result"
                else {}
            )
            cell.outputs.append(
                new_output(
                    output_type=output_type,
                    data=out["data"],
                    metadata=out.get("metadata", {}),
                    **extra,
                )
            )
        elif out.get("output_type") == "error":
            cell.outputs.append(
                new_output(
                    output_type="error",
                    ename=out.get("ename", "Error"),
                    evalue=out.get("evalue", ""),
                    traceback=out.get("traceback", []),
                )
            )


def write_notebook_output(loaded: ExecutionInput) -> Optional[str]:
    """Persist an executed notebook beside its source and return the path."""
    if not loaded.is_notebook:
        return None
    output_path = loaded.output_path
    assert output_path is not None
    with open(output_path, "w", encoding="utf-8") as file_obj:
        nbformat.write(loaded.notebook, file_obj)
    return output_path
