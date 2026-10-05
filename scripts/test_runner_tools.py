import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from local_test_tools import (
    LocalTestError,
    adb_call,
    ci_enabled,
    execute_instrumentation,
    install_apk,
    require_offline,
    select_device,
    validate_instrumentation_output,
)

SUCCESS = """INSTRUMENTATION_STATUS: class=sample.ExampleTest
INSTRUMENTATION_STATUS_CODE: -1
Time: 0.01
OK (1 test)
INSTRUMENTATION_CODE: -1
"""
FAILURE = """INSTRUMENTATION_STATUS_CODE: -2
FAILURES!!!
Tests run: 1, Failures: 1
INSTRUMENTATION_CODE: -1
"""
CRASH = """INSTRUMENTATION_RESULT: shortMsg=Process crashed.
INSTRUMENTATION_FAILED: instrumentation crashed
"""


class RunnerProtocolTests(unittest.TestCase):
    def test_accepts_nonzero_success_and_ignores_per_test_minus_one(self):
        self.assertEqual(1, validate_instrumentation_output(SUCCESS))

    def test_accepts_line_bounded_summary_terminal_whitespace_and_newlines(self):
        for output in (
            "OK (24 tests)\n\nINSTRUMENTATION_CODE: -1\n",
            "  OK (2 tests) \t\r\n\r\n \tINSTRUMENTATION_CODE : -1 \t\r\n",
            "OK (3 test)\n\n\nINSTRUMENTATION_CODE: -1\n",
        ):
            with self.subTest(output=output):
                self.assertGreater(validate_instrumentation_output(output), 0)

    def test_rejects_cross_line_summary_terminal_and_premature_terminal(self):
        for output in (
            "OK\n(2 tests)\nINSTRUMENTATION_CODE: -1\n",
            "OK (2\n tests)\nINSTRUMENTATION_CODE: -1\n",
            "OK (2 tests\n)\nINSTRUMENTATION_CODE: -1\n",
            "OK (2 tests)\nINSTRUMENTATION_CODE:\n-1\n",
            "INSTRUMENTATION_CODE: -1\n\nOK (2 tests)\n",
        ):
            with self.subTest(output=output), self.assertRaises(LocalTestError):
                validate_instrumentation_output(output)

    def test_rejects_failure_crash_and_timeout_even_when_success_summary_appears(self):
        success_with_failure = SUCCESS + "FAILURES!!!\n"
        for output in (success_with_failure, SUCCESS + CRASH):
            with self.subTest(output=output), self.assertRaises(LocalTestError):
                validate_instrumentation_output(output)
        with self.assertRaisesRegex(LocalTestError, "timed out"):
            validate_instrumentation_output(SUCCESS, timed_out=True)

    def test_rejects_zero_exit_junit_failure(self):
        with self.assertRaisesRegex(LocalTestError, "FAILURES"):
            validate_instrumentation_output(FAILURE, returncode=0)

    def test_rejects_instrumentation_crash(self):
        for output in (CRASH, "INSTRUMENTATION_FAILED: no instrumentation\n"):
            with self.subTest(output=output), self.assertRaises(LocalTestError):
                validate_instrumentation_output(output)

    def test_rejects_instrumentation_result_error(self):
        with self.assertRaisesRegex(LocalTestError, "error result"):
            validate_instrumentation_output("INSTRUMENTATION_RESULT: error=bad runner\n")

    def test_rejects_missing_or_zero_test_summary_and_missing_terminal(self):
        for output in (
            "",
            "INSTRUMENTATION_CODE: -1\n",
            "OK (0 tests)\nINSTRUMENTATION_CODE: -1\n",
            "OK (2 tests)\n",
            "OK (2 tests)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: 0\n",
        ):
            with self.subTest(output=output), self.assertRaises(LocalTestError):
                validate_instrumentation_output(output)

    def test_rejects_nonzero_process_exit_even_if_output_looks_successful(self):
        with self.assertRaisesRegex(LocalTestError, "status 7"):
            validate_instrumentation_output(SUCCESS, returncode=7)

    def test_rejects_timeout(self):
        with self.assertRaisesRegex(LocalTestError, "timed out"):
            validate_instrumentation_output(SUCCESS, timed_out=True)

    def test_execute_rejects_zero_exit_failure_and_writes_report(self):
        def fake_run(command, **_kwargs):
            return subprocess.CompletedProcess(command, 0, FAILURE, "")

        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "instrumentation.txt"
            with self.assertRaisesRegex(LocalTestError, "FAILURES"):
                execute_instrumentation("adb", "emulator-1", report, runner=fake_run)
            self.assertIn("FAILURES!!!", report.read_text())

    def test_timeout_force_stops_only_selected_snapbudget_package(self):
        calls = []

        def fake_run(command, **_kwargs):
            calls.append(command)
            if "instrument" in command:
                raise subprocess.TimeoutExpired(command, 1, output="partial")
            return subprocess.CompletedProcess(command, 0, "", "")

        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "instrumentation.txt"
            with self.assertRaisesRegex(LocalTestError, "no app data was cleared"):
                execute_instrumentation("adb", "serial-A", report, timeout=1, runner=fake_run)
            self.assertEqual("partial", report.read_text())
        self.assertEqual("serial-A", calls[1][2])
        self.assertEqual(["shell", "am", "force-stop", "dev.snapbudget"], calls[1][3:])

    def test_timeout_cleanup_failure_reports_manual_recovery(self):
        def fake_run(command, **_kwargs):
            if "instrument" in command:
                raise subprocess.TimeoutExpired(command, 1)
            return subprocess.CompletedProcess(command, 1, "", "offline")

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(LocalTestError, "manually inspect/recover"):
                execute_instrumentation("adb", "serial-A", Path(directory) / "out", runner=fake_run)


class LocalPreconditionTests(unittest.TestCase):
    def test_ci_truth_values_and_jenkins_presence(self):
        self.assertTrue(ci_enabled({"CI": "true"}))
        self.assertTrue(ci_enabled({"GITHUB_ACTIONS": "yes"}))
        self.assertTrue(ci_enabled({"BUILDKITE": "1"}))
        self.assertTrue(ci_enabled({"GITLAB_CI": "true"}))
        self.assertTrue(ci_enabled({"TF_BUILD": "True"}))
        self.assertTrue(ci_enabled({"JENKINS_URL": "https://jenkins.example"}))
        self.assertFalse(ci_enabled({"CI": "false", "GITHUB_ACTIONS": "0", "BUILDKITE": "no"}))
        self.assertFalse(ci_enabled({}))

    def test_device_requires_explicit_serial_for_ambiguity(self):
        devices = "List of devices attached\nemulator-1\tdevice\nemulator-2\tdevice\n"
        with self.assertRaisesRegex(LocalTestError, "requires --serial"):
            select_device(devices)
        self.assertEqual("emulator-2", select_device(devices, "emulator-2"))

    def test_device_requires_online_explicit_serial(self):
        devices = "List of devices attached\nemulator-1\tdevice\nemulator-2\toffline\n"
        self.assertEqual("emulator-1", select_device(devices, "emulator-1"))
        with self.assertRaisesRegex(LocalTestError, "not an online"):
            select_device(devices, "emulator-2")

    def test_device_rejects_no_device(self):
        with self.assertRaisesRegex(LocalTestError, "zero or multiple"):
            select_device("List of devices attached\n")

    def test_offline_check_requires_airplane_mode_and_wifi_off(self):
        require_offline("1", "0")
        for airplane, wifi in (("0", "0"), ("1", "1"), ("unknown", "0")):
            with self.subTest(airplane=airplane, wifi=wifi), self.assertRaisesRegex(LocalTestError, "not verified offline"):
                require_offline(airplane, wifi)

    def test_all_mode_creates_report_directory_and_preserves_gradle_failure(self):
        repository = Path(__file__).resolve().parent.parent
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / "scripts"
            scripts.mkdir()
            for filename in ("test-local.sh", "local_test_tools.py"):
                shutil.copy2(repository / "scripts" / filename, scripts / filename)
            (scripts / "test-local.sh").chmod(0o755)

            java_home = root / "fake-jdk"
            (java_home / "bin").mkdir(parents=True)
            java = java_home / "bin" / "java"
            java.write_text('#!/bin/sh\necho \'openjdk version "25.0.1"\' >&2\n')
            java.chmod(0o755)
            sdk = root / "fake-sdk"
            (sdk / "platforms" / "android-36").mkdir(parents=True)
            (sdk / "platforms" / "android-36" / "android.jar").touch()
            (sdk / "build-tools" / "36.0.0").mkdir(parents=True)
            aapt = sdk / "build-tools" / "36.0.0" / "aapt"
            aapt.write_text("#!/bin/sh\nexit 0\n")
            aapt.chmod(0o755)

            marker = root / "gradle-invoked"
            gradlew = root / "gradlew"
            gradlew.write_text(
                '#!/bin/sh\necho called > "$FAKE_GRADLE_MARKER"\n'
                'echo "synthetic Gradle failure"\nexit 7\n'
            )
            gradlew.chmod(0o755)

            shim_bin = root / "bin"
            shim_bin.mkdir()
            python = shim_bin / "python3"
            python.write_text(
                "#!/bin/sh\n"
                'if [ "$1" = "scripts/test_runner_tools.py" ]; then exit 0; fi\n'
                f'exec "{sys.executable}" "$@"\n'
            )
            python.chmod(0o755)
            environment = os.environ.copy()
            for key in (
                "CI", "GITHUB_ACTIONS", "BUILDKITE", "GITLAB_CI", "CIRCLECI", "TRAVIS",
                "TF_BUILD", "TEAMCITY_VERSION", "APPVEYOR", "DRONE", "CODEBUILD_BUILD_ID", "JENKINS_URL",
            ):
                environment.pop(key, None)
            environment.update({
                "JAVA_HOME": str(java_home),
                "ANDROID_HOME": str(sdk),
                "ANDROID_SDK_ROOT": str(sdk),
                "FAKE_GRADLE_MARKER": str(marker),
                "PATH": f"{shim_bin}:{os.environ['PATH']}",
            })
            self.assertFalse((root / "app" / "build").exists())
            result = subprocess.run(
                [str(scripts / "test-local.sh"), "all"],
                cwd=root, env=environment, capture_output=True, text=True, timeout=30,
            )

            report = root / "app" / "build" / "reports" / "local-tests" / "unit-gradle.log"
            self.assertEqual(7, result.returncode, result.stderr)
            self.assertTrue(marker.exists())
            self.assertTrue(report.is_file())
            self.assertIn("synthetic Gradle failure", report.read_text())
            self.assertNotIn("No such file or directory", result.stderr)

    def test_adb_call_is_bounded_and_apk_install_targets_selected_serial(self):
        def timeout_run(command, **kwargs):
            raise subprocess.TimeoutExpired(command, kwargs["timeout"])

        with self.assertRaisesRegex(LocalTestError, "Timed out"):
            adb_call("adb", "serial", "devices", runner=timeout_run)

        calls = []

        def success_run(command, **_kwargs):
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, "Success", "")

        install_apk("adb", "serial", "app.apk", runner=success_run)
        self.assertEqual(["adb", "-s", "serial", "install", "-r", "-t", "app.apk"], calls[0])


if __name__ == "__main__":
    unittest.main(verbosity=2)
