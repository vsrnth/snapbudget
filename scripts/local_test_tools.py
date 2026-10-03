#!/usr/bin/env python3
"""Bounded local device helpers and AndroidJUnitRunner result validation."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Callable


class LocalTestError(RuntimeError):
    """A local test precondition or execution failed."""


def ci_enabled(environment: dict[str, str] | None = None) -> bool:
    env = os.environ if environment is None else environment
    if env.get("JENKINS_URL", "").strip():
        return True
    false_values = {"", "0", "false", "no", "off"}
    return any(env.get(name, "").strip().lower() not in false_values for name in (
        "CI", "GITHUB_ACTIONS", "BUILDKITE", "GITLAB_CI", "CIRCLECI", "TRAVIS",
        "TF_BUILD", "TEAMCITY_VERSION", "APPVEYOR", "DRONE", "CODEBUILD_BUILD_ID",
    ))


def select_device(devices_output: str, requested_serial: str = "") -> str:
    online = [line.split()[0] for line in devices_output.splitlines()[1:]
              if len(line.split()) >= 2 and line.split()[1] == "device"]
    if requested_serial:
        if requested_serial not in online:
            raise LocalTestError(
                f"Explicit serial '{requested_serial}' is not an online adb device. "
                f"Online devices: {', '.join(online) or '(none)'}"
            )
        return requested_serial
    if len(online) != 1:
        raise LocalTestError(
            "Device mode requires --serial when zero or multiple devices are online. "
            f"Online devices: {', '.join(online) or '(none)'}"
        )
    return online[0]


def require_offline(airplane_mode: str, wifi_enabled: str) -> None:
    if airplane_mode.strip() != "1" or wifi_enabled.strip() != "0":
        raise LocalTestError(
            "Selected device is not verified offline "
            f"(airplane_mode_on={airplane_mode.strip()}, wifi_on={wifi_enabled.strip()}). "
            "Enable airplane mode and disable Wi-Fi manually; networking is never changed by this runner."
        )


def adb_call(adb: str, serial: str, *arguments: str, timeout: float = 15,
             runner: Callable = subprocess.run) -> str:
    command = [adb]
    if serial:
        command.extend(["-s", serial])
    command.extend(arguments)
    try:
        result = runner(command, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        raise LocalTestError(f"Timed out after {timeout:g}s: {' '.join(command)}") from error
    if result.returncode != 0:
        details = (result.stderr or result.stdout or "").strip()
        raise LocalTestError(f"Command failed ({result.returncode}): {' '.join(command)}\n{details}")
    return result.stdout


def validate_instrumentation_output(output: str, returncode: int = 0,
                                    timed_out: bool = False) -> int:
    if timed_out:
        raise LocalTestError("Instrumentation timed out.")
    if returncode != 0:
        raise LocalTestError(f"adb instrumentation command exited with status {returncode}.")
    if re.search(r"(?im)^\s*FAILURES!!!\s*$", output):
        raise LocalTestError("AndroidJUnitRunner reported FAILURES!!!")
    if re.search(r"(?im)^\s*INSTRUMENTATION_FAILED\s*:", output):
        raise LocalTestError("AndroidJUnitRunner reported INSTRUMENTATION_FAILED.")
    if re.search(r"(?im)^\s*INSTRUMENTATION_STATUS_CODE\s*:\s*-2\s*$", output):
        raise LocalTestError("AndroidJUnitRunner reported a failed test status (-2).")
    if re.search(r"(?im)^\s*INSTRUMENTATION_RESULT\s*:\s*(?:shortMsg|error)\s*=", output):
        raise LocalTestError("AndroidJUnitRunner returned an instrumentation error result.")

    summaries = list(re.finditer(r"(?m)^\s*OK\s*\((\d+)\s+tests?\)\s*$", output))
    if not summaries:
        raise LocalTestError("Missing final successful AndroidJUnitRunner OK (N tests) summary.")
    count = int(summaries[-1].group(1))
    if count <= 0:
        raise LocalTestError("AndroidJUnitRunner reported a zero-test suite.")
    terminals = list(re.finditer(r"(?m)^\s*INSTRUMENTATION_CODE\s*:\s*(-?\d+)\s*$", output))
    if not terminals or terminals[-1].group(1) != "-1" or terminals[-1].start() < summaries[-1].end():
        raise LocalTestError("Missing successful terminal INSTRUMENTATION_CODE: -1 after test summary.")
    return count


def execute_instrumentation(adb: str, serial: str, report_path: str | Path,
                            timeout: float = 600, runner: Callable = subprocess.run,
                            cleanup_timeout: float = 15) -> int:
    command = [adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
               "dev.snapbudget.test/androidx.test.runner.AndroidJUnitRunner"]
    try:
        result = runner(command, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        partial = error.stdout or ""
        if isinstance(partial, bytes):
            partial = partial.decode(errors="replace")
        Path(report_path).write_text(partial, encoding="utf-8")
        cleanup = [adb, "-s", serial, "shell", "am", "force-stop", "dev.snapbudget"]
        try:
            cleanup_result = runner(cleanup, capture_output=True, text=True, timeout=cleanup_timeout)
            if cleanup_result.returncode != 0:
                raise LocalTestError("Selected test-process force-stop command failed.")
        except (subprocess.TimeoutExpired, OSError, LocalTestError) as cleanup_error:
            raise LocalTestError(
                f"Instrumentation timed out after {timeout:g}s. Partial report: {report_path}. "
                "Could not confirm selected SnapBudget test-process force-stop; manually inspect/recover "
                f"only device {serial}. No app data was cleared."
            ) from cleanup_error
        raise LocalTestError(
            f"Instrumentation timed out after {timeout:g}s. Partial report: {report_path}. "
            f"Requested force-stop of only dev.snapbudget on selected device {serial}; no app data was cleared."
        ) from error
    output = result.stdout or ""
    if result.stderr:
        output += "\n" + result.stderr
    Path(report_path).write_text(output, encoding="utf-8")
    try:
        return validate_instrumentation_output(output, result.returncode)
    except LocalTestError as error:
        raise LocalTestError(f"{error} Report: {report_path}\n{output[-12000:]}") from error


def wait_for_boot(adb: str, serial: str, timeout: float = 120,
                  runner: Callable = subprocess.run, sleeper: Callable = time.sleep) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if adb_call(adb, serial, "shell", "getprop", "sys.boot_completed",
                        timeout=min(5, max(0.1, deadline - time.monotonic())), runner=runner).strip() == "1":
                return
        except LocalTestError:
            pass
        sleeper(min(2, max(0, deadline - time.monotonic())))
    raise LocalTestError(f"Selected device did not finish booting within {timeout:g} seconds.")


def install_apk(adb: str, serial: str, apk: str, timeout: float = 120,
                runner: Callable = subprocess.run) -> None:
    adb_call(adb, serial, "install", "-r", "-t", apk, timeout=timeout, runner=runner)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    commands.add_parser("check-ci")
    device_parser = commands.add_parser("select-device")
    device_parser.add_argument("--adb", required=True)
    device_parser.add_argument("--serial", default="")
    install_parser = commands.add_parser("install")
    install_parser.add_argument("--adb", required=True)
    install_parser.add_argument("--serial", required=True)
    install_parser.add_argument("--apk", required=True)
    install_parser.add_argument("--timeout", type=float, default=120)
    instrument_parser = commands.add_parser("instrument")
    instrument_parser.add_argument("--adb", required=True)
    instrument_parser.add_argument("--serial", required=True)
    instrument_parser.add_argument("--report", required=True)
    instrument_parser.add_argument("--timeout", type=float, default=600)
    arguments = parser.parse_args()
    if arguments.action == "check-ci":
        if ci_enabled():
            print("ERROR: Local Android tests are forbidden on CI.", file=sys.stderr)
            return 2
        return 0
    try:
        if arguments.action == "select-device":
            devices = adb_call(arguments.adb, "", "devices", timeout=15)
            serial = select_device(devices, arguments.serial)
            airplane = adb_call(arguments.adb, serial, "shell", "settings", "get", "global",
                                "airplane_mode_on", timeout=15)
            wifi = adb_call(arguments.adb, serial, "shell", "settings", "get", "global",
                            "wifi_on", timeout=15)
            require_offline(airplane, wifi)
            wait_for_boot(arguments.adb, serial)
            print(serial)
        elif arguments.action == "install":
            install_apk(arguments.adb, arguments.serial, arguments.apk, arguments.timeout)
        else:
            count = execute_instrumentation(arguments.adb, arguments.serial, arguments.report,
                                            arguments.timeout)
            print(f"Instrumentation passed: {count} tests. Report: {arguments.report}")
    except LocalTestError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
