#!/usr/bin/env python3
"""Verify that a ColabTerm APK contains its complete Chaquopy Python runtime."""

from __future__ import annotations

import json
import sys
import zipfile
from email.parser import Parser
from pathlib import Path

EXPECTED_PYTHON_VERSION = "3.13"
REQUIRED_ASSETS = (
    "assets/chaquopy/app.imy",
    "assets/chaquopy/requirements-common.imy",
    "assets/chaquopy/requirements-arm64-v8a.imy",
)
PINNED_DISTRIBUTIONS = {
    "bcrypt": "3.2.2",
    "cffi": "1.17.1",
    "cryptography": "42.0.8",
    "paramiko": "5.1.0",
    "pynacl": "1.5.0",
}
REQUIRED_NATIVE_EXTENSIONS = {
    "_cffi_backend.so",
    "bcrypt/_bcrypt.so",
    "cryptography/hazmat/bindings/_rust.so",
    "nacl/_sodium.so",
}


def fail(message: str) -> None:
    raise SystemExit(f"Chaquopy APK verification failed: {message}")


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {Path(sys.argv[0]).name} APK", file=sys.stderr)
        return 2

    apk_path = Path(sys.argv[1])
    if not apk_path.is_file():
        fail(f"APK does not exist: {apk_path}")

    try:
        with zipfile.ZipFile(apk_path) as apk:
            apk_names = set(apk.namelist())
            missing = [name for name in REQUIRED_ASSETS if name not in apk_names]
            if missing:
                fail("missing required assets: " + ", ".join(missing))

            build_info = json.loads(apk.read("assets/chaquopy/build.json"))
            if build_info.get("python_version") != EXPECTED_PYTHON_VERSION:
                fail(
                    "expected Python "
                    f"{EXPECTED_PYTHON_VERSION}, found {build_info.get('python_version')!r}"
                )
            declared_assets = build_info.get("assets", {})
            undeclared = [
                name.removeprefix("assets/chaquopy/")
                for name in REQUIRED_ASSETS
                if name.removeprefix("assets/chaquopy/") not in declared_assets
            ]
            if undeclared:
                fail("build.json does not declare: " + ", ".join(undeclared))

            common_archive = zipfile.ZipFile(
                apk.open("assets/chaquopy/requirements-common.imy")
            )
            with common_archive:
                common_members = set(common_archive.namelist())
                installed: dict[str, str] = {}
                for member in common_members:
                    if not member.endswith(".dist-info/METADATA"):
                        continue
                    metadata = Parser().parsestr(
                        common_archive.read(member).decode("utf-8", errors="replace")
                    )
                    name = (metadata.get("Name") or "").lower().replace("-", "").replace("_", "")
                    version = metadata.get("Version")
                    if name and version:
                        installed[name] = version

            missing_distributions = [
                f"{name}=={version} (found {installed.get(name, 'missing')})"
                for name, version in PINNED_DISTRIBUTIONS.items()
                if installed.get(name) != version
            ]
            if missing_distributions:
                fail(
                    "required Python distributions are absent or wrong: "
                    + ", ".join(missing_distributions)
                )

            native_extensions = {
                name for name in common_members if name.endswith(".so")
            }
            missing_native_extensions = sorted(REQUIRED_NATIVE_EXTENSIONS - native_extensions)
            if missing_native_extensions:
                fail(
                    "native wheel extensions are absent from requirements-common.imy: "
                    + ", ".join(missing_native_extensions)
                )

    except (OSError, zipfile.BadZipFile, KeyError, json.JSONDecodeError) as exc:
        fail(f"cannot inspect APK contents: {exc}")

    print(
        f"Chaquopy APK verification passed: {apk_path} "
        f"(Python {EXPECTED_PYTHON_VERSION}; "
        f"{len(PINNED_DISTRIBUTIONS)} pinned distributions; "
        f"{len(native_extensions)} ARM64 native extensions)."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
