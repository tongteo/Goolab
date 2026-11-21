# Colab CLI Android/Termux compatibility build — v5.3

This tree is based on the latest upstream `google-colab-cli` source included in
this package. The Android port keeps upstream command behavior and replaces only
native dependency paths that are known to be problematic on Termux.

## Install

From the extracted source directory:

```bash
pip install . --break-system-packages
```

Python 3.12 or newer is required.

## Why compatibility modules exist

The upstream package currently pulls dependency chains that are difficult to
install on Android/Termux ARM64:

- `nbformat -> jsonschema -> referencing -> rpds-py`
- `pydantic -> pydantic-core`
- newer `google-auth -> cryptography`
- `jupyter-kernel-client` and its transitive stack

This build avoids replacing broad application logic. Instead it uses focused,
pure-Python compatibility layers:

- `colab_cli.ipynb_lite` for the nbformat-v4 operations used by the CLI
- `colab_cli.pydantic_lite` for the model/alias/coercion operations used by the CLI
- `colab_cli.ws_kernel` for the Jupyter JSON WebSocket kernel transport
- `google-auth>=2.47,<2.48` to retain the authentication API used by this CLI
  without forcing the newer native cryptography dependency chain

The upstream `uv.lock` file is intentionally omitted because it describes the
official dependency graph, including packages removed by this Android port.
`pip install . --break-system-packages` is the supported installation path for
this source package.

## Diagnostics

Run all local, non-network compatibility checks:

```bash
colab test
```

Run one layer at a time:

```bash
colab test environment
colab test notebook
colab test models
colab test kernel-codec
```

After creating a real Colab session, test the live kernel transport separately:

```bash
colab new -s android-test
colab test kernel -s android-test
```

The live kernel test executes only a small `print()` statement. It is not run by
`colab test` automatically, so diagnostics never allocate or touch a remote
runtime unless the user explicitly requests it.

## Updating

On Termux, `colab update --install` is intentionally disabled. Installing the
official PyPI build over this port can restore native dependencies that fail on
Android. `colab update` may still report newer upstream versions for reference;
rebase Android compatibility changes onto the newer upstream source instead of
blindly replacing the package.

## SSH GPU environment fix

This build also carries an upstream-facing SSH fix discovered while auditing a T4
runtime: the GPU device nodes can exist while an SSH login shell cannot locate
`libnvidia-ml.so.1` / `libcuda.so.1`. Direct `colab ssh` now conditionally
bootstraps known NVIDIA driver-library directories before starting the login
shell. The logic preserves an existing `LD_LIBRARY_PATH`, avoids duplicates, and
does nothing on CPU/TPU runtimes where those libraries are absent.

The audit also covered sibling execution paths. `colab exec`, `colab run`, and
`colab console` use the notebook kernel environment and do not need this SSH
bootstrap. `colab ssh --proxy-mode` is intentionally a raw transport; external
SSH clients that need GPU shell libraries must use the equivalent `RemoteCommand`
documented in `docs/06_ssh_access.md`.

## Portable integration-test runner

Integration scripts no longer hardcode `uv run colab`. They share
`integration/_runner.sh`, which keeps `uv` as the normal upstream-development
runner but uses the active Python against this checkout's `src/` tree on
Android/Termux. This matters because `.python-version` intentionally remains
`3.12` for upstream development while Termux may provide a newer compatible
Python (for example 3.14) and cannot necessarily install uv's managed CPython
builds.

The runner is also used to build OpenSSH `ProxyCommand` strings, so direct test
commands and SSH bridge commands exercise the same checkout. Set
`COLAB_TEST_RUNNER=uv|source|installed` only when deliberately testing a
specific runner mode.

For the T4 SSH regression:

```bash
RUN_GPU_SSH_E2E=1 bash integration/repro_ssh_gpu/test.sh
```

A local runner/import error now fails the test instead of being mislabeled as
"T4 allocation unavailable". Only recognized quota/resource/entitlement
failures are treated as an accelerator-availability skip.


## One-shot notebook execution

`colab run` now accepts `.ipynb` as well as `.py`. Notebook parsing/output handling is shared with `colab exec` through `colab_cli.execution_input`, so the two commands cannot silently drift into separate notebook formats. Local input is parsed before allocation; code cells run in order in one kernel and outputs are saved beside the source as `*_output.ipynb`.

## Long-running runtime-proxy renewal

Colab runtime-proxy credentials have a finite lifetime independent of the VM assignment. This build records the expiry on session creation and renews the proxy token/URL from `list_assignments()` before expiry using the existing keep-alive process. Named session resolution also refreshes legacy or near-expiry entries, and 401/404 cleanup now confirms the assignment is actually gone before removing local state.

Session writes use locked field-level updates for mutable runtime state so a long-running command cannot overwrite credentials renewed by the detached keep-alive process.

Fast live regression (forces stale credentials instead of waiting about an hour):

```bash
bash integration/repro_runtime_proxy_refresh/test.sh
```
