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

import logging
import os
import signal
import sys
import time
from typing import Optional

import typer

from colab_cli.auth import AuthProvider, get_credentials
from colab_cli.client import Client, Prod
from colab_cli.history import HistoryLogger
from colab_cli.state import StateStore, SettingsStore


RUNTIME_PROXY_REFRESH_MARGIN_SECONDS = 10 * 60


class State:
    def __init__(self):
        self.client_oauth_config = os.path.expanduser("~/.colab-cli-oauth-config.json")
        self.config_path = None
        self.logtostderr = False
        self.auth_provider = AuthProvider.OAUTH2
        self._client = None
        self._store = None
        self._settings_store = None
        self._history = None
        self._sessions = None

    @property
    def store(self):
        if self._store is None:
            self._store = StateStore(self.config_path)
        return self._store

    @property
    def settings_store(self):
        if self._settings_store is None:
            # We don't currently allow overriding settings path via CLI,
            # but we could if needed. For now, use default.
            self._settings_store = SettingsStore()
        return self._settings_store

    @property
    def history(self):
        if self._history is None:
            self._history = HistoryLogger()
        return self._history

    @property
    def client(self):
        if self._client is None:
            creds = get_credentials(
                self.client_oauth_config, provider=self.auth_provider
            )
            self._client = Client(Prod(), creds)
        return self._client

    def _remove_local_session(self, name: str):
        """Remove local state after the backend has confirmed it is gone."""
        s = self.store.get(name)
        if s and s.keep_alive_pid:
            kill_process(s.keep_alive_pid)
        self.store.remove(name)
        if self._sessions and name in self._sessions:
            del self._sessions[name]
        self.history.log_event(name, "session_terminated", {"reason": "pruned"})

    @staticmethod
    def _proxy_expiry_from_assignment(assignment, now: Optional[float] = None):
        ttl = getattr(assignment.runtime_proxy_info, "token_expires_in_seconds", None)
        if ttl is None:
            return None
        return (time.time() if now is None else now) + max(0, float(ttl))

    def _apply_assignment_runtime_proxy(self, s, assignment, now=None):
        """Adopt the current runtime-proxy credentials for one assignment."""
        rpi = assignment.runtime_proxy_info
        expires_at = self._proxy_expiry_from_assignment(assignment, now=now)
        changed = (
            s.token != rpi.token
            or s.url != rpi.url
            or s.token_expires_at != expires_at
        )
        if changed:
            updated = self.store.update(
                s.name,
                token=rpi.token,
                url=rpi.url,
                token_expires_at=expires_at,
            )
            if updated is not None:
                s = updated
                if self._sessions is not None:
                    self._sessions[s.name] = s
        return s

    def refresh_session_runtime_proxy(
        self, name: str, *, force: bool = False, tolerate_errors: bool = False
    ):
        """Refresh a session's expiring runtime-proxy credentials.

        Returns the current SessionState, or ``None`` when the backend
        definitively no longer lists the assignment.  Control-plane failures
        can be tolerated so a transient listing outage does not break a still
        usable runtime connection.
        """
        s = self.store.get(name)
        if s is None:
            return None
        now = time.time()
        if (
            not force
            and s.token_expires_at is not None
            and s.token_expires_at - now > RUNTIME_PROXY_REFRESH_MARGIN_SECONDS
        ):
            return s
        try:
            assignments = self.client.list_assignments()
        except Exception:
            if tolerate_errors:
                logging.debug(
                    "Unable to refresh runtime-proxy credentials for %s",
                    name,
                    exc_info=True,
                )
                return s
            raise
        for assignment in assignments:
            if assignment.endpoint == s.endpoint:
                return self._apply_assignment_runtime_proxy(s, assignment, now=now)

        # A successful assignment listing that omits this endpoint is
        # authoritative.  Avoid leaving a local binding to a gone runtime.
        self._remove_local_session(name)
        return None

    def prune_session(self, name: str):
        """Prune only after re-confirming that the assignment is gone.

        A 401/404 from the runtime tunnel can mean an expired proxy token, not
        a dead VM.  If the assignment still exists, refresh its credentials
        instead and keep the local binding.  Returns True only when pruning
        actually occurred.
        """
        s = self.store.get(name)
        if s is None:
            return True
        try:
            refreshed = self.refresh_session_runtime_proxy(
                name, force=True, tolerate_errors=False
            )
        except Exception:
            # Inconclusive control-plane state must never destroy a possibly
            # live local binding.
            logging.debug(
                "Refusing to prune %s because assignment status is unknown",
                name,
                exc_info=True,
            )
            return False
        return refreshed is None

    def sync_sessions(self):
        # Always reconcile against the current assignment listing.  The old
        # in-process cache returned stale proxy credentials after the first
        # sync and defeated token renewal.
        local_sessions = self.store.list()
        if not local_sessions:
            self._sessions = {}
            try:
                assignments = self.client.list_assignments()
            except SystemExit:
                assignments = []
            return self._sessions, assignments

        assignments = self.client.list_assignments()
        by_endpoint = {a.endpoint: a for a in assignments}

        self._sessions = dict(local_sessions)
        pruned = 0
        now = time.time()
        for name, session in list(self._sessions.items()):
            assignment = by_endpoint.get(session.endpoint)
            if assignment is None:
                self._remove_local_session(name)
                pruned += 1
                continue
            self._sessions[name] = self._apply_assignment_runtime_proxy(
                session, assignment, now=now
            )

        if pruned > 0:
            typer.echo(f"[colab] Pruned {pruned} stale local session(s).")

        return self._sessions, assignments


    def resolve_session(self, session_name: Optional[str]) -> str:
        if session_name:
            # Named commands used to bypass synchronization entirely, leaving
            # them permanently tied to the token captured by `colab new`.
            # Refresh only when the token is unknown/near expiry, and tolerate
            # control-plane outages so a cached credential can still be tried.
            if self.store.get(session_name) is not None:
                self.refresh_session_runtime_proxy(
                    session_name, tolerate_errors=True
                )
            return session_name

        # Check local store first to avoid hitting the backend (and triggering auth) if we don't have to
        local_sessions = self.store.list()
        if not local_sessions:
            typer.echo(
                "[colab] Error: No active sessions found. Create one with 'colab new'."
            )
            raise typer.Exit(1)

        # If we have local sessions, we need to sync to make sure they are still valid.
        # This will trigger auth if valid credentials are not present.
        sessions, _ = self.sync_sessions()
        active_names = list(sessions.keys())

        if len(active_names) == 1:
            name = active_names[0]
            typer.echo(f"[colab] Using unique session '{name}'.")
            return name
        elif len(active_names) > 1:
            typer.echo(
                f"[colab] Error: Multiple active sessions found. Specify one with -s: {', '.join(active_names)}"
            )
            raise typer.Exit(1)
        else:
            typer.echo(
                "[colab] Error: No active sessions found. Create one with 'colab new'."
            )
            raise typer.Exit(1)


state = State()


def kill_process(pid: int):
    """Safely terminates a process by PID."""
    if not pid:
        return
    try:
        os.kill(pid, signal.SIGTERM)
        # Give it a moment to exit
        for _ in range(5):
            time.sleep(0.1)
            os.kill(pid, 0)
    except OSError:
        # Already dead
        pass
    except Exception:
        logging.debug(f"Failed to kill process {pid}")


def setup_logging(log_to_stderr: bool):
    log_format = "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
    logger = logging.getLogger()
    logger.setLevel(logging.DEBUG)

    requests_log = logging.getLogger("urllib3")
    requests_log.setLevel(logging.DEBUG)
    requests_log.propagate = True

    log_dir = os.path.expanduser("~/.config/colab-cli")
    os.makedirs(log_dir, exist_ok=True)
    file_handler = logging.FileHandler(os.path.join(log_dir, "colab.log"))
    file_handler.setFormatter(logging.Formatter(log_format))
    logger.addHandler(file_handler)

    if log_to_stderr:
        stream_handler = logging.StreamHandler(sys.stderr)
        stream_handler.setFormatter(logging.Formatter(log_format))
        logger.addHandler(stream_handler)
