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

import json
import logging
import time
from typing import Any, Callable, Dict, List, Optional

from colab_cli import ws_kernel as jupyter_kernel_client
import requests


class ColabRuntime:
    def __init__(
        self,
        url: str,
        token: str,
        session_name: Optional[str] = None,
        history: Optional[Any] = None,
        kernel_id: Optional[str] = None,
        session_id: Optional[str] = None,
        on_kernel_started: Optional[Callable[[str], None]] = None,
        on_session_started: Optional[Callable[[str], None]] = None,
    ):
        self.url = url
        self.token = token
        self.session_name = session_name
        self.history = history
        self.kernel_id = kernel_id
        self.session_id = session_id
        self.on_kernel_started = on_kernel_started
        self.on_session_started = on_session_started
        self._kernel_client = None
        self.colab_request_hook: Optional[Callable[[Dict[str, Any], Any], None]] = None

    def _apply_ws_hook(self):
        """Intercept Colab-specific stdin requests without replacing dispatch.

        ``ws_kernel`` exposes a lightweight ``on_message`` hook. The hook may
        consume ``colab_request`` messages, while every other message falls
        through to the transport's normal Jupyter dispatcher.
        """
        wsclient = self._kernel_client._manager.client

        def hooked_on_message(_ws_client, raw_message):
            if self.colab_request_hook:
                try:
                    if isinstance(raw_message, bytes):
                        raw_message = raw_message.decode("utf-8")
                    message = json.loads(raw_message)
                    msg_type = message.get("header", {}).get("msg_type")
                    if msg_type == "colab_request":
                        if self.colab_request_hook(message, wsclient):
                            return
                except Exception as exc:
                    logging.debug("Error in colab_request hook: %s", exc)

            # Tell ws_kernel to continue with its normal dispatcher.
            raise jupyter_kernel_client._PassThrough()

        wsclient.kernel_socket.on_message = hooked_on_message

    @property
    def kernel_client(self):
        if not self._kernel_client:
            retries = 3
            backoff = 2
            last_err = None

            for i in range(retries):
                try:
                    client_kwargs = {
                        "extra_params": {"colab-runtime-proxy-token": self.token},
                    }
                    if self.session_id:
                        client_kwargs["session"] = self.session_id

                    self._kernel_client = jupyter_kernel_client.WSKernelClient(
                        server_url=self.url,
                        token=self.token,
                        kernel_id=self.kernel_id,
                        client_kwargs=client_kwargs,
                        headers={
                            "X-Colab-Client-Agent": "colab-cli",
                            "X-Colab-Runtime-Proxy-Token": self.token,
                        },
                    )
                    # Force _own_kernel to False. This prevents jupyter-kernel-client
                    # from automatically deleting the kernel when the client is closed or deleted.
                    self._kernel_client._own_kernel = False

                    self._kernel_client.start()
                    self._apply_ws_hook()

                    # Capture IDs if we started fresh
                    if not self.kernel_id and self._kernel_client.id:
                        self.kernel_id = self._kernel_client.id
                        if self.on_kernel_started:
                            self.on_kernel_started(self.kernel_id)

                    if (
                        not self.session_id
                        and self._kernel_client._manager.client.session.session
                    ):
                        self.session_id = (
                            self._kernel_client._manager.client.session.session
                        )
                        if self.on_session_started:
                            self.on_session_started(self.session_id)
                    break
                except (
                    requests.exceptions.ReadTimeout,
                    requests.exceptions.ConnectTimeout,
                ) as e:
                    last_err = e
                    if i < retries - 1:
                        sleep_time = backoff ** (i + 1)
                        logging.debug(
                            f"Kernel startup timeout, retrying in {sleep_time}s..."
                            f" ({i + 1}/{retries})"
                        )
                        time.sleep(sleep_time)
                    else:
                        raise last_err
                except Exception as e:
                    raise e

        return self._kernel_client

    def restart(
        self,
        timeout: Optional[float] = None,
    ):
        self.kernel_client.restart(timeout=timeout)

    def execute_code(
        self,
        code: str,
        allow_stdin: bool = False,
        stdin_hook: Any = None,
        output_hook: Optional[Callable[[Dict[str, Any]], None]] = None,
        timeout: Optional[float] = None,
    ) -> List[Dict[str, Any]]:
        # The Android transport treats ``timeout`` as an inactivity ceiling.
        # Every iopub/stdin event refreshes the timer, so long-running code can
        # continue as long as the kernel remains active. Callers that expect a
        # long silent period (for example browser OAuth during Drive mounting)
        # can pass a larger timeout explicitly.
        kwargs = {"allow_stdin": allow_stdin}
        if timeout is not None:
            kwargs["timeout"] = timeout

        # Wrap stdin_hook to log inputs
        original_stdin_hook = stdin_hook

        def wrapped_stdin_hook(prompt):
            if self.history and self.session_name:
                self.history.log_event(
                    self.session_name, "stdin_request", {"prompt": prompt}
                )

            res = original_stdin_hook(prompt) if original_stdin_hook else input(prompt)

            if self.history and self.session_name:
                self.history.log_event(self.session_name, "input_reply", {"value": res})
            return res

        if allow_stdin:
            kwargs["stdin_hook"] = wrapped_stdin_hook

        if output_hook:
            # ws_kernel emits normalized notebook-output dictionaries directly.
            # Keep buffering semantics here so clear_output(wait=True) behaves
            # the same for streaming and non-streaming execution.
            outputs = []
            clear_pending = False

            def wrapped_output_hook(out):
                nonlocal clear_pending
                if out.get("output_type") == "clear_output":
                    if out.get("wait"):
                        clear_pending = True
                    else:
                        outputs.clear()
                    return
                if clear_pending:
                    outputs.clear()
                    clear_pending = False
                outputs.append(out)
                output_hook(out)

            reply_content = self.kernel_client.execute_interactive(
                code, output_hook=wrapped_output_hook, **kwargs
            )
            if not reply_content:
                reply_content = {"status": "error"}
        else:
            reply = self.kernel_client.execute(code, **kwargs)
            if not reply:
                return []
            outputs = reply.get("outputs", [])
            reply_content = reply

        # If there's an error status but no error in outputs, synthesize one
        if reply_content.get("status") == "error":
            has_error_output = any(o.get("output_type") == "error" for o in outputs)
            if not has_error_output:
                outputs.append(
                    {
                        "output_type": "error",
                        "ename": reply_content.get("ename", "Error"),
                        "evalue": reply_content.get("evalue", "Unknown error"),
                        "traceback": reply_content.get("traceback", []),
                    }
                )

        return outputs

    def stop(self, shutdown_kernel: bool = False):
        if self._kernel_client:
            try:
                # We manage kernel lifecycle explicitly.
                # To prevent automatic shutdown, we bypass the manager's stop() and
                # directly close the channels and socket.
                client = self._kernel_client._manager.client
                client.stop_channels()
                if client.kernel_socket:
                    client.kernel_socket.close()

                if shutdown_kernel:
                    self._kernel_client._manager.shutdown_kernel(now=True)
            except Exception:
                logging.exception("Error stopping kernel client")
