#!/usr/bin/env python3
"""Validate Tailware's Wear VPN bootstrap scripts and guide-link policy.

Development mode permits the private test-branch guide URL. Release mode
requires the stable main-branch URL in every production Android source
reference. Optional fixtures exercise both helpers against a stateful fake ADB;
they never contact a real Android device.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
ANDROID_MAIN = ROOT / "android" / "src" / "main"
POWERSHELL_HELPER = ROOT / "tools" / "authorize-wear-vpn.ps1"
POSIX_HELPER = ROOT / "tools" / "authorize-wear-vpn.sh"
GUIDE = ROOT / "docs" / "wear-vpn-setup.md"
ANDROID_BUILD = ROOT / "android" / "build.gradle"

PRIVATE_GUIDE_URL = (
    "https://github.com/vladbezm/tailware/blob/"
    "agent/wear-v1/docs/wear-vpn-setup.md"
)
RELEASE_GUIDE_URL = (
    "https://github.com/vladbezm/tailware/blob/main/docs/wear-vpn-setup.md"
)
RUNTIME_GUIDE_MARKER_PATTERN = re.compile(r"wear-vpn-setup\.md")
RUNTIME_GUIDE_DECLARATION_PATTERN = re.compile(
    r"^[ \t]*private[ \t]+const[ \t]+val[ \t]+VPN_SETUP_GUIDE_URL"
    r"[ \t]*=[ \t]*(?:\r?\n[ \t]*)?\"(?P<url>[^\"\\\r\n]*)\"[ \t]*$",
    re.MULTILINE,
)
POSIX_SHELL = (
    os.environ.get("TAILWARE_TEST_SH")
    or ("/bin/sh" if Path("/bin/sh").exists() else "")
    or shutil.which("sh")
    or (
        r"C:\Program Files\Git\usr\bin\sh.exe"
        if Path(r"C:\Program Files\Git\usr\bin\sh.exe").exists()
        else ""
    )
)
POWERSHELL = shutil.which("pwsh") or shutil.which("powershell")


def fail(message: str) -> None:
    raise AssertionError(message)


def assert_contains(text: str, tokens: list[str], label: str) -> None:
    missing = [token for token in tokens if token not in text]
    if missing:
        fail(f"{label} is missing required safety markers: {missing}")


def runtime_guide_url(source: str, label: str) -> str:
    """Return the complete value of the sole simple Kotlin bootstrap constant."""
    matches = list(RUNTIME_GUIDE_DECLARATION_PATTERN.finditer(source))
    if len(matches) != 1:
        fail(
            f"{label} must define exactly one simple VPN_SETUP_GUIDE_URL "
            f"string literal; found {len(matches)}"
        )
    return matches[0].group("url")


def validate_runtime_guide_url(source: str, mode: str, label: str) -> str:
    url = runtime_guide_url(source, label)
    allowed = {RELEASE_GUIDE_URL}
    if mode == "development":
        allowed.add(PRIVATE_GUIDE_URL)
    if url not in allowed:
        fail(f"{label} contains an unsupported {mode} Wear VPN guide URL: {url!r}")
    if mode == "release" and url != RELEASE_GUIDE_URL:
        fail(
            "release source still contains the private branch guide URL; "
            f"use {RELEASE_GUIDE_URL}"
        )
    return url


def run_url_literal_fixtures() -> None:
    def declaration(value: str) -> str:
        return f'private const val VPN_SETUP_GUIDE_URL =\n    "{value}"\n'

    if (
        validate_runtime_guide_url(
            declaration(RELEASE_GUIDE_URL), "release", "<canonical-release-fixture>"
        )
        != RELEASE_GUIDE_URL
    ):
        fail("canonical release URL fixture did not preserve the complete value")
    if (
        validate_runtime_guide_url(
            declaration(PRIVATE_GUIDE_URL), "development", "<development-fixture>"
        )
        != PRIVATE_GUIDE_URL
    ):
        fail("development URL fixture did not preserve the complete value")

    rejected = {
        "prefix": declaration("x" + RELEASE_GUIDE_URL),
        "suffix": declaration(RELEASE_GUIDE_URL + "/unexpected"),
        "duplicate": declaration(RELEASE_GUIDE_URL) + declaration(RELEASE_GUIDE_URL),
    }
    for fixture_name, source in rejected.items():
        try:
            validate_runtime_guide_url(
                source, "release", f"<{fixture_name}-release-fixture>"
            )
        except AssertionError:
            continue
        fail(f"malformed {fixture_name} release URL fixture was accepted")

    try:
        validate_runtime_guide_url(
            declaration(PRIVATE_GUIDE_URL), "release", "<private-release-fixture>"
        )
    except AssertionError:
        pass
    else:
        fail("private development URL fixture was accepted in release mode")


def validate_static_contracts(mode: str) -> None:
    ps_text = POWERSHELL_HELPER.read_text(encoding="utf-8")
    sh_text = POSIX_HELPER.read_text(encoding="utf-8")
    guide_text = GUIDE.read_text(encoding="utf-8")
    android_build_text = ANDROID_BUILD.read_text(encoding="utf-8")

    assert_contains(
        ps_text,
        [
            "[switch]$Revoke",
            "[switch]$AllowNonWearDevice",
            "More than one ADB device is visible",
            "Restore-AlwaysOnSettings",
            "Get-AlwaysOnSettingsSnapshot",
            "Assert-AlwaysOnOwnerMutable",
            "Assert-ActivateVpnAppOp",
            "ExpectedAllowlist",
            "$existingAlwaysOn -ceq $PackageId",
        ],
        "PowerShell helper",
    )
    assert_contains(
        sh_text,
        [
            "--revoke",
            "--allow-non-wear",
            "more than one ADB device is visible",
            "restore_always_on_settings",
            "read_always_on_settings_snapshot",
            "assert_always_on_owner_mutable",
            "assert_activate_vpn_appop",
            "assert_always_on_settings",
            '[ "$always_on" = "$package_id" ]',
        ],
        "POSIX helper",
    )
    assert_contains(
        guide_text,
        [
            RELEASE_GUIDE_URL,
            "validate-wear-vpn-bootstrap.py --mode release",
            "-AllowNonWearDevice",
            "--allow-non-wear",
            "-Revoke",
            "--revoke",
        ],
        "VPN setup guide",
    )
    assert_contains(
        android_build_text,
        [
            '"validateWearVpnBootstrapRelease"',
            'it.name == "preReleaseBuild"',
            "dependsOn(validateWearVpnBootstrapRelease)",
            '"--mode"',
            '"release"',
            "outputs.upToDateWhen { false }",
        ],
        "Gradle release bootstrap gate",
    )

    tailware_app_path = (
        ANDROID_MAIN
        / "java"
        / "com"
        / "tailscale"
        / "ipn"
        / "wear"
        / "ui"
        / "TailwareApp.kt"
    )
    tailware_app = tailware_app_path.read_text(encoding="utf-8")
    validate_runtime_guide_url(tailware_app, mode, str(tailware_app_path.relative_to(ROOT)))

    runtime_markers: list[Path] = []
    for path in ANDROID_MAIN.rglob("*"):
        if not path.is_file():
            continue
        # Scan every packaged production input, including assets and uncommon
        # resource formats. URL bytes are ASCII, so ignoring unrelated binary
        # bytes is safe and prevents a new file type from bypassing release mode.
        text = path.read_bytes().decode("utf-8", errors="ignore")
        runtime_markers.extend(path for _ in RUNTIME_GUIDE_MARKER_PATTERN.finditer(text))

    if runtime_markers != [tailware_app_path]:
        locations = ", ".join(str(path.relative_to(ROOT)) for path in runtime_markers)
        fail(
            "production Android source must contain exactly one Wear VPN guide "
            f"literal in TailwareApp.kt; found [{locations}]"
        )

    assert_contains(
        tailware_app,
        [
            "onOpenGuideOnPhone(VPN_SETUP_GUIDE_URL)",
            "rememberQrCode(VPN_SETUP_GUIDE_URL",
            "TailwareStrings.ltr(state.language, VPN_SETUP_GUIDE_URL)",
            "onRetry = { viewModel.checkVpnAuthorizationFromUserGesture(context) }",
        ],
        "VPN setup UI",
    )
    for forbidden in (
        "repeatOnLifecycle(Lifecycle.State.RESUMED)",
        "VPN_AUTHORIZATION_POLL_INTERVAL_MILLIS",
        "VpnService.prepare",
    ):
        if forbidden in tailware_app:
            fail(
                "VPN setup UI must remain passive until its explicit Retry/Connect "
                f"gesture; found {forbidden!r}"
            )
    if "VPN_SETUP_GUIDE_MANUAL_URL" in tailware_app:
        fail("VPN setup UI must not maintain a second manual guide URL")

    wear_view_model = (
        ANDROID_MAIN
        / "java"
        / "com"
        / "tailscale"
        / "ipn"
        / "wear"
        / "viewmodel"
        / "TailwareViewModel.kt"
    ).read_text(encoding="utf-8")
    assert_contains(
        wear_view_model,
        [
            "fun checkVpnAuthorizationFromUserGesture(context: Context)",
            "vpnAuthorizationFromPrepared(VpnService.prepare(context) == null)",
            "fun connect()",
            "TailwareActivationCapability.grantVpnFromUserGesture(app)",
            "if (checkVpnAuthorizationFromUserGesture(app) != VpnAuthorization.Granted)",
        ],
        "explicit VPN authorization boundary",
    )
    prepare_call = "VpnService.prepare(context)"
    if wear_view_model.count(prepare_call) != 1:
        fail(
            "Wear ViewModel must contain exactly one platform VPN preparation call, "
            "owned by the explicit user-gesture helper"
        )

    wear_activity = (
        ANDROID_MAIN
        / "java"
        / "com"
        / "tailscale"
        / "ipn"
        / "wear"
        / "WearMainActivity.kt"
    ).read_text(encoding="utf-8")
    assert_contains(
        wear_activity,
        [
            "private fun activateEngineFromUserGesture()",
            "TailwareActivationCapability.grantEngineFromUserGesture(this)",
            "Settings.ACTION_DEVICE_INFO_SETTINGS",
            "Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS",
            "intent.resolveActivity(packageManager) == null",
            "runCatching {",
            "startActivity(intent)",
        ],
        "Wear settings shortcuts",
    )
    passive_resume = wear_activity.split("override fun onResume()", 1)[1].split(
        "private fun setOptionalNotifications", 1
    )[0]
    if "App.get()" in passive_resume or "VpnService.prepare" in "\n".join(
        line.split("//", 1)[0] for line in passive_resume.splitlines()
    ):
        fail("Wear Activity resume must not initialize App or prepare the VPN")
    # Login QR codes and authentication keys are bearer credentials. Screenshot
    # protection must follow only those routes and must be cleared on exit; the
    # passive launch and ordinary dashboard remain unaffected.
    assert_contains(
        wear_activity,
        [
            "onSensitiveLoginContentVisible = ::setSensitiveLoginContentVisible",
            "window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)",
            "window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)",
        ],
        "route-sensitive Wear screenshot protection",
    )
    assert_contains(
        tailware_app,
        [
            "internal fun routeRequiresSecureWindow(route: String?): Boolean",
            "route == Route.LoginQr || route == Route.AuthKey",
            "SensitiveLoginWindowCoordinator",
            "sensitiveWindowCoordinator.close()",
        ],
        "sensitive login route policy",
    )


FAKE_ADB = r"""#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

state_path = Path(os.environ["TAILWARE_FAKE_ADB_STATE"])
log_path = Path(os.environ["TAILWARE_FAKE_ADB_LOG"])
state = json.loads(state_path.read_text(encoding="utf-8"))
args = sys.argv[1:]
with log_path.open("a", encoding="utf-8") as stream:
    stream.write(json.dumps(args) + "\n")

def save():
    state_path.write_text(json.dumps(state), encoding="utf-8")

def replace_settings_after_helper_mutation(settings):
    mutation_count = int(state.get("_settings_mutation_count", 0)) + 1
    state["_settings_mutation_count"] = mutation_count
    replacement = state.get("settings_replacement_after_mutations")
    if (
        isinstance(replacement, dict)
        and not state.get("_settings_replacement_after_mutations_applied", False)
        and replacement.get("count") == mutation_count
    ):
        settings.update(replacement.get("settings", {}))
        state["_settings_replacement_after_mutations_applied"] = True
        # The helper completed this write, then an independent provider took ownership before its
        # next ADB command. The external replacement is deliberately not a helper mutation.

if args == ["version"]:
    print("Android Debug Bridge version 1.0.41")
    print(f'Version {state.get("platform_tools_version", "35.0.2-12147458")}')
    raise SystemExit(0)
if args == ["devices", "-l"]:
    print("List of devices attached")
    for device in state["devices"]:
        print(f'{device["serial"]}\t{device["state"]} model:Fixture')
    raise SystemExit(0)
if len(args) == 2 and args[0] == "connect":
    print(f"already connected to {args[1]}")
    raise SystemExit(0)
if len(args) < 3 or args[0] != "-s":
    print(f"unsupported fake ADB command: {args}", file=sys.stderr)
    raise SystemExit(2)

command = args[2:]
if command[:2] == ["shell", "getprop"]:
    prop = command[2]
    values = {
        "ro.build.version.sdk": str(state.get("sdk", 34)),
        "ro.product.manufacturer": "Tailware",
        "ro.product.model": "Fixture Watch",
    }
    print(values.get(prop, ""))
elif command == ["shell", "pm", "list", "features"]:
    if state.get("wear", True):
        print("feature:android.hardware.type.watch")
    print("feature:android.hardware.wifi")
elif command in (
    ["shell", "am", "get-current-user"],
    ["shell", "cmd", "activity", "get-current-user"],
):
    if command == ["shell", "am", "get-current-user"] and state.get(
        "fail_am_current_user", False
    ):
        print("forced am get-current-user failure", file=sys.stderr)
        raise SystemExit(3)
    print(state.get("user_id", "0"))
elif command[:3] == ["shell", "pm", "path"]:
    if state.get("package_installed", True):
        print("package:/data/app/tailware/base.apk")
elif command[:3] == ["shell", "appops", "get"]:
    print(
        state.get(
            "appop_output",
            f'ACTIVATE_VPN: {state.get("appop", "default")}',
        )
    )
elif command[:3] == ["shell", "appops", "set"]:
    requested = command[-1]
    replacement = state.get("settings_replacement_on_appop_set")
    if (
        isinstance(replacement, dict)
        and not state.get("_settings_replacement_on_appop_set_applied", False)
        and replacement.get("mode") == requested
    ):
        state.setdefault("settings", {}).update(replacement.get("settings", {}))
        state["_settings_replacement_on_appop_set_applied"] = True
        # This models an independent provider, not a helper mutation.
        save()
    if requested == "allow" and state.get("fail_allow_set", False):
        print("forced ACTIVATE_VPN allow failure", file=sys.stderr)
        raise SystemExit(3)
    if not (
        (requested == "allow" and state.get("fail_allow_verify", False))
        or (requested == "default" and state.get("fail_default_verify", False))
    ):
        state["appop"] = requested
        state["_mutated"] = True
        save()
elif command[:3] == ["shell", "settings", "--user"]:
    operation = command[4]
    name = command[6]
    settings = state.setdefault("settings", {})
    if operation == "get":
        if state.get("fail_settings_get_once_after_mutation", False) and state.get(
            "_mutated", False
        ):
            state["fail_settings_get_once_after_mutation"] = False
            save()
            print("forced settings read failure", file=sys.stderr)
            raise SystemExit(3)
        value = settings.get(name)
        get_count = int(state.get("_settings_get_count", 0)) + 1
        state["_settings_get_count"] = get_count
        replacement = state.get("settings_replacement_after_gets")
        if (
            isinstance(replacement, dict)
            and not state.get("_settings_replacement_after_gets_applied", False)
            and replacement.get("count") == get_count
        ):
            settings.update(replacement.get("settings", {}))
            state["_settings_replacement_after_gets_applied"] = True
            # Return the value captured before the external transition. The next helper read sees
            # the new owner; no fake helper mutation marker is set.
        save()
        print("null" if value is None else value)
    elif operation == "put":
        value = command[7]
        if (
            name == "always_on_vpn_lockdown"
            and state.get("fail_lockdown_put", False)
        ):
            print("forced lockdown put failure", file=sys.stderr)
            raise SystemExit(3)
        if not (
            name == "always_on_vpn_app"
            and value == "io.github.vladbezm.tailware"
            and state.get("fail_always_on_verify", False)
        ):
            settings[name] = value
            state["_mutated"] = True
            replace_settings_after_helper_mutation(settings)
            save()
    elif operation == "delete":
        settings[name] = None
        state["_mutated"] = True
        replace_settings_after_helper_mutation(settings)
        save()
    else:
        raise SystemExit(2)
else:
    print(f"unsupported fake ADB command: {command}", file=sys.stderr)
    raise SystemExit(2)
"""


def default_state(**changes: object) -> dict[str, object]:
    state: dict[str, object] = {
        "devices": [{"serial": "fixture-watch", "state": "device"}],
        "wear": True,
        "sdk": 34,
        "appop": "ignore",
        "settings": {
            "always_on_vpn_app": None,
            "always_on_vpn_lockdown": None,
            "always_on_vpn_lockdown_whitelist": None,
        },
    }
    state.update(changes)
    return state


def read_log(path: Path) -> list[list[str]]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def command_ends_with(command: list[str], suffix: list[str]) -> bool:
    return len(command) >= len(suffix) and command[-len(suffix) :] == suffix


def is_watch_mutation(command: list[str]) -> bool:
    return (
        "appops" in command
        and "set" in command
        or "settings" in command
        and ("put" in command or "delete" in command)
    )


def exercise_helper(
    helper_kind: str,
    initial_state: dict[str, object],
    extra_args: list[str],
    expect_success: bool,
    *,
    explicit_serial: bool = True,
    serial_value: str = "fixture-watch",
) -> tuple[dict[str, object], list[list[str]], str]:
    with tempfile.TemporaryDirectory(prefix="tailware-vpn-fixture-") as temp_name:
        temp = Path(temp_name)
        fake_adb_program = temp / "fake_adb.py"
        fake_adb_program.write_text(FAKE_ADB, encoding="utf-8", newline="\n")
        fake_adb_program.chmod(fake_adb_program.stat().st_mode | stat.S_IXUSR)
        base_python = Path(getattr(sys, "_base_executable", sys.executable))
        if helper_kind == "sh":
            def sh_quote(value: str) -> str:
                return "'" + value.replace("'", "'\"'\"'") + "'"

            fake_adb = temp / "adb"
            fake_adb.write_text(
                "#!/bin/sh\n"
                f"exec {sh_quote(base_python.as_posix())} "
                '"${0%/*}/fake_adb.py" "$@"\n',
                encoding="utf-8",
                newline="\n",
            )
            fake_adb.chmod(fake_adb.stat().st_mode | stat.S_IXUSR)
        elif os.name == "nt":
            fake_adb = temp / "adb.cmd"
            fake_adb.write_text(
                f'@"{base_python}" "%~dp0fake_adb.py" %*\r\n',
                encoding="utf-8",
            )
        else:
            fake_adb = temp / "adb"
            fake_adb.write_text(FAKE_ADB, encoding="utf-8", newline="\n")
            fake_adb.chmod(fake_adb.stat().st_mode | stat.S_IXUSR)
        state_path = temp / "state.json"
        log_path = temp / "adb.log"
        state_path.write_text(json.dumps(initial_state), encoding="utf-8")
        log_path.write_text("", encoding="utf-8")
        env = os.environ.copy()
        env["TAILWARE_FAKE_ADB_STATE"] = str(state_path)
        env["TAILWARE_FAKE_ADB_LOG"] = str(log_path)
        if helper_kind == "sh" and os.name == "nt":
            env["PATH"] = str(Path(POSIX_SHELL).parent) + os.pathsep + env["PATH"]

        if helper_kind == "sh":
            command = [
                POSIX_SHELL,
                str(POSIX_HELPER),
                "--adb",
                fake_adb.as_posix(),
            ]
            if explicit_serial:
                command += ["--serial", serial_value]
            command += ["--yes", *extra_args]
        else:
            def ps_quote(value: str) -> str:
                return "'" + value.replace("'", "''") + "'"

            invocation = (
                "trap { [Console]::Out.WriteLine($_.Exception.Message); exit 1 }; "
                f"& {ps_quote(str(POWERSHELL_HELPER))} "
                f"-AdbPath {ps_quote(str(fake_adb))} "
            )
            if explicit_serial:
                invocation += f"-Serial {ps_quote(serial_value)} "
            invocation += " ".join(extra_args) + " -Confirm:$false"
            command = [
                POWERSHELL or "pwsh",
                "-NoProfile",
                "-Command",
                invocation,
            ]

        result = subprocess.run(
            command,
            cwd=ROOT,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        if (result.returncode == 0) != expect_success:
            fail(
                f"{helper_kind} fixture returned {result.returncode}; "
                f"expected success={expect_success}\n{result.stdout}\n"
                f"ADB log:\n{log_path.read_text(encoding='utf-8')}"
            )
        return (
            json.loads(state_path.read_text(encoding="utf-8")),
            read_log(log_path),
            result.stdout,
        )


def run_helper_fixtures(helper_kind: str) -> None:
    dry_run_arg = ["--dry-run"] if helper_kind == "sh" else ["-WhatIf"]
    enable_always_on_arg = (
        ["--enable-always-on"] if helper_kind == "sh" else ["-EnableAlwaysOn"]
    )
    revoke_arg = ["--revoke"] if helper_kind == "sh" else ["-Revoke"]

    state, log, output = exercise_helper(
        helper_kind,
        default_state(),
        [],
        False,
        serial_value="-ambiguous-option-like-serial",
    )
    if log or any(is_watch_mutation(command) for command in log):
        fail(f"{helper_kind}: invalid option-like serial reached ADB: {log}")
    if "invalid adb serial" not in output.lower():
        fail(f"{helper_kind}: invalid serial failure was not explicit:\n{output}")

    state, log, output = exercise_helper(
        helper_kind,
        default_state(),
        [],
        False,
        serial_value="fixture-watch;injected-command",
    )
    if log:
        fail(f"{helper_kind}: metacharacter serial reached ADB: {log}")
    if "invalid adb serial" not in output.lower():
        fail(f"{helper_kind}: metacharacter serial failure was not explicit:\n{output}")

    state, log, output = exercise_helper(
        helper_kind,
        default_state(
            devices=[
                {
                    "serial": "fixture-watch;injected-command",
                    "state": "device",
                }
            ]
        ),
        [],
        False,
        explicit_serial=False,
    )
    if any(command and command[0] == "-s" for command in log):
        fail(f"{helper_kind}: unsafe discovered serial reached device commands: {log}")
    if "invalid adb serial" not in output.lower():
        fail(f"{helper_kind}: unsafe discovered serial was not rejected:\n{output}")

    state, log, output = exercise_helper(
        helper_kind,
        default_state(
            devices=[{"serial": "Fixture-Watch", "state": "device"}]
        ),
        [],
        False,
    )
    if any(command[:2] == ["-s", "fixture-watch"] for command in log):
        fail(f"{helper_kind}: case-mismatched serial reached device commands: {log}")
    if "cannot find" not in output.lower():
        fail(f"{helper_kind}: serial matching was not case-exact:\n{output}")

    state, log, output = exercise_helper(
        helper_kind,
        default_state(
            devices=[
                {"serial": "fixture-watch", "state": "device"},
                {"serial": "fixture-watch", "state": "device"},
            ]
        ),
        [],
        False,
    )
    if any(command and command[0] == "-s" for command in log):
        fail(f"{helper_kind}: duplicate explicit serial reached device commands: {log}")
    if "more than once" not in output.lower() or "ambiguous target" not in output.lower():
        fail(f"{helper_kind}: duplicate explicit serial failure was unclear:\n{output}")

    conflicting_selector_args = (
        ["--connect", "other-watch:5555"]
        if helper_kind == "sh"
        else ["-ConnectAddress", "'other-watch:5555'"]
    )
    state, log, output = exercise_helper(
        helper_kind,
        default_state(),
        conflicting_selector_args,
        False,
    )
    if log:
        fail(f"{helper_kind}: conflicting selectors reached ADB: {log}")
    if "ambiguous targets" not in output.lower():
        fail(f"{helper_kind}: conflicting target error was not explicit:\n{output}")

    malformed_connect_args = (
        ["--connect", "-option-like-host:5555"]
        if helper_kind == "sh"
        else ["-ConnectAddress", "'-option-like-host:5555'"]
    )
    state, log, output = exercise_helper(
        helper_kind,
        default_state(),
        malformed_connect_args,
        False,
        explicit_serial=False,
    )
    if log:
        fail(f"{helper_kind}: malformed connect address reached ADB: {log}")
    if "invalid adb connection address" not in output.lower():
        fail(f"{helper_kind}: malformed connection error was not explicit:\n{output}")

    invalid_package_args = (
        ["--package", "bad..package"]
        if helper_kind == "sh"
        else ["-PackageId", "'bad..package'"]
    )
    state, log, output = exercise_helper(
        helper_kind,
        default_state(),
        invalid_package_args,
        False,
    )
    if log:
        fail(f"{helper_kind}: malformed package ID reached ADB: {log}")
    if "package" not in output.lower():
        fail(f"{helper_kind}: malformed package failure was not explicit:\n{output}")

    state, log, output = exercise_helper(
        helper_kind,
        default_state(platform_tools_version="35.0broken"),
        [],
        False,
    )
    if log != [["version"]]:
        fail(f"{helper_kind}: malformed Platform-Tools version was not fail-fast: {log}")
    if "platform-tools version" not in output.lower():
        fail(f"{helper_kind}: malformed Platform-Tools error was unclear:\n{output}")

    for unavailable_state in ("unauthorized", "offline"):
        state, log, output = exercise_helper(
            helper_kind,
            default_state(
                devices=[{"serial": "fixture-watch", "state": unavailable_state}]
            ),
            [],
            False,
        )
        if any(is_watch_mutation(command) for command in log):
            fail(
                f"{helper_kind}: {unavailable_state} device guard allowed a mutation"
            )
        expected_state_text = (
            ("unauthorized", "not authorized")
            if unavailable_state == "unauthorized"
            else ("offline",)
        )
        if not any(token in output.lower() for token in expected_state_text):
            fail(
                f"{helper_kind}: {unavailable_state} failure was not explicit:\n{output}"
            )

    state, log, output = exercise_helper(
        helper_kind,
        default_state(package_installed=False),
        [],
        False,
    )
    if any(is_watch_mutation(command) for command in log):
        fail(f"{helper_kind}: missing-package guard allowed a mutation")
    if "not installed" not in output.lower():
        fail(f"{helper_kind}: missing-package error was not explicit:\n{output}")

    state, log, _ = exercise_helper(
        helper_kind,
        default_state(fail_am_current_user=True, user_id="10"),
        dry_run_arg,
        True,
    )
    if not any(
        command_ends_with(
            command, ["shell", "cmd", "activity", "get-current-user"]
        )
        for command in log
    ):
        fail(f"{helper_kind}: current-user fallback command was not attempted")
    if not any(
        command_ends_with(
            command,
            [
                "shell",
                "pm",
                "path",
                "--user",
                "10",
                "io.github.vladbezm.tailware",
            ],
        )
        for command in log
    ):
        fail(f"{helper_kind}: active Android user was not used for package lookup")
    if any(is_watch_mutation(command) for command in log):
        fail(f"{helper_kind}: current-user dry run unexpectedly mutated the watch")

    preview_state = default_state()
    state, log, _ = exercise_helper(
        helper_kind,
        preview_state,
        [*enable_always_on_arg, *dry_run_arg],
        True,
    )
    if state["appop"] != preview_state["appop"] or state["settings"] != preview_state[
        "settings"
    ]:
        fail(f"{helper_kind}: preview changed fake watch state")
    if any(is_watch_mutation(command) for command in log):
        fail(f"{helper_kind}: preview issued a mutating ADB command")

    for appop_output in (
        "No operations.",
        "io.github.vladbezm.tailware:\n  android:activate_vpn: foreground; time=+1h",
    ):
        state, log, output = exercise_helper(
            helper_kind,
            default_state(appop_output=appop_output),
            dry_run_arg,
            True,
        )
        if any(is_watch_mutation(command) for command in log):
            fail(f"{helper_kind}: AppOp parser preview mutated the watch")
        if "could not determine" in output.lower():
            fail(
                f"{helper_kind}: valid AppOp output was rejected: {appop_output!r}"
            )

    ipv6_address = "[fe80::1%wlan0]:5555"
    ipv6_connect_args = (
        ["--connect", ipv6_address, "--dry-run"]
        if helper_kind == "sh"
        else ["-ConnectAddress", f"'{ipv6_address}'", "-WhatIf"]
    )
    state, log, _ = exercise_helper(
        helper_kind,
        default_state(devices=[{"serial": ipv6_address, "state": "device"}]),
        ipv6_connect_args,
        True,
        explicit_serial=False,
    )
    if ["connect", ipv6_address] not in log:
        fail(f"{helper_kind}: bracketed IPv6 ADB address did not reach connect")
    if any(is_watch_mutation(command) for command in log):
        fail(f"{helper_kind}: IPv6 connect preview mutated the watch")

    multi = default_state(
        devices=[
            {"serial": "fixture-watch", "state": "device"},
            {"serial": "offline-phone", "state": "offline"},
        ]
    )
    state, log, output = exercise_helper(
        helper_kind, multi, [], False, explicit_serial=False
    )
    if state["appop"] != "ignore" or any("appops" in command for command in log):
        fail(f"{helper_kind}: multiple-device guard allowed an AppOp mutation")
    if "more than one adb device is visible" not in output.lower():
        fail(
            f"{helper_kind}: multiple-device failure was not explicit; "
            f"output was:\n{output}"
        )

    non_wear = default_state(wear=False)
    state, log, _ = exercise_helper(helper_kind, non_wear, [], False)
    if state["appop"] != "ignore" or any(
        command_ends_with(command, ["ACTIVATE_VPN", "allow"]) for command in log
    ):
        fail(f"{helper_kind}: non-Wear default refusal mutated ACTIVATE_VPN")

    failed_grant = default_state(fail_allow_verify=True)
    state, log, output = exercise_helper(helper_kind, failed_grant, [], False)
    if state["appop"] != "ignore":
        fail(f"{helper_kind}: failed grant did not restore previous AppOp")
    appop_sets = [
        command[-1]
        for command in log
        if command_ends_with(command[:-1], ["ACTIVATE_VPN"])
        and "set" in command
    ]
    if appop_sets[-2:] != ["allow", "ignore"]:
        fail(f"{helper_kind}: grant rollback order is wrong: {appop_sets}")
    if "restored and verified" not in output:
        fail(f"{helper_kind}: failed grant did not report verified rollback")

    other_provider = default_state(
        appop="allow",
        settings={
            "always_on_vpn_app": "com.example.other",
            "always_on_vpn_lockdown": "1",
            "always_on_vpn_lockdown_whitelist": "com.example.allowed",
        },
    )
    state, log, output = exercise_helper(
        helper_kind,
        other_provider,
        enable_always_on_arg,
        False,
    )
    if state["appop"] != other_provider["appop"] or state["settings"] != other_provider[
        "settings"
    ]:
        fail(f"{helper_kind}: occupied always-on refusal changed watch state")
    if any(is_watch_mutation(command) for command in log):
        fail(f"{helper_kind}: occupied always-on owner was checked after mutation")
    if "refusing to replace existing always-on vpn" not in output.lower():
        fail(f"{helper_kind}: occupied always-on refusal was not explicit:\n{output}")

    state, log, _ = exercise_helper(
        helper_kind,
        other_provider,
        ["--revoke"] if helper_kind == "sh" else ["-Revoke"],
        True,
    )
    if state["settings"] != other_provider["settings"]:
        fail(f"{helper_kind}: revoke changed another provider's always-on settings")
    if state["appop"] != "default":
        fail(f"{helper_kind}: revoke did not restore AppOp default")
    if any("settings" in command and ("put" in command or "delete" in command) for command in log):
        fail(f"{helper_kind}: revoke wrote settings owned by another provider")

    case_distinct_provider = default_state(
        appop="allow",
        settings={
            "always_on_vpn_app": "IO.GITHUB.VLADBEZM.TAILWARE",
            "always_on_vpn_lockdown": "1",
            "always_on_vpn_lockdown_whitelist": "case.distinct.allow",
        },
    )
    state, log, _ = exercise_helper(
        helper_kind,
        case_distinct_provider,
        ["--revoke"] if helper_kind == "sh" else ["-Revoke"],
        True,
    )
    if state["settings"] != case_distinct_provider["settings"]:
        fail(
            f"{helper_kind}: case-distinct provider was mistaken for Tailware "
            "ownership and its settings were changed"
        )
    if any(
        "settings" in command and ("put" in command or "delete" in command)
        for command in log
    ):
        fail(f"{helper_kind}: case-distinct provider received settings writes")

    own_provider = default_state(
        appop="allow",
        settings={
            "always_on_vpn_app": "io.github.vladbezm.tailware",
            "always_on_vpn_lockdown": "1",
            "always_on_vpn_lockdown_whitelist": "fixture.allow",
        },
    )
    state, log, _ = exercise_helper(
        helper_kind,
        own_provider,
        ["--revoke"] if helper_kind == "sh" else ["-Revoke"],
        True,
    )
    if state["appop"] != "default" or any(state["settings"].values()):
        fail(f"{helper_kind}: Tailware-owned revoke did not clear its state")
    setting_writes = [
        command for command in log if "settings" in command and ("put" in command or "delete" in command)
    ]
    expected_prefix = [
        ["put", "secure", "always_on_vpn_lockdown", "0"],
        ["delete", "secure", "always_on_vpn_app"],
        ["delete", "secure", "always_on_vpn_lockdown_whitelist"],
        ["delete", "secure", "always_on_vpn_lockdown"],
    ]
    for command, suffix in zip(setting_writes[:4], expected_prefix, strict=True):
        if not command_ends_with(command, suffix):
            fail(f"{helper_kind}: unsafe Tailware revoke order: {setting_writes[:4]}")

    independent_revoke_rollback = default_state(
        appop="allow",
        fail_default_verify=True,
        fail_allow_set=True,
        settings={
            "always_on_vpn_app": "io.github.vladbezm.tailware",
            "always_on_vpn_lockdown": "1",
            "always_on_vpn_lockdown_whitelist": "fixture.allow",
        },
    )
    state, log, output = exercise_helper(
        helper_kind,
        independent_revoke_rollback,
        ["--revoke"] if helper_kind == "sh" else ["-Revoke"],
        False,
    )
    if state["settings"] != independent_revoke_rollback["settings"]:
        fail(
            f"{helper_kind}: AppOp rollback failure prevented independent "
            "always-on snapshot restoration"
        )
    if state["appop"] != "allow":
        fail(
            f"{helper_kind}: independent revoke fixture unexpectedly changed "
            f"the previous AppOp: {state['appop']}"
        )
    rollback_allow_index = next(
        (
            index
            for index, command in enumerate(log)
            if command_ends_with(command, ["ACTIVATE_VPN", "allow"])
        ),
        None,
    )
    restored_provider_index = next(
        (
            index
            for index, command in enumerate(log)
            if command_ends_with(
                command,
                [
                    "put",
                    "secure",
                    "always_on_vpn_app",
                    "io.github.vladbezm.tailware",
                ],
            )
        ),
        None,
    )
    if rollback_allow_index is None or restored_provider_index is None:
        fail(
            f"{helper_kind}: independent revoke rollback did not attempt both "
            f"branches; ADB log was {log}"
        )
    if restored_provider_index <= rollback_allow_index:
        fail(
            f"{helper_kind}: always-on snapshot was not restored after the "
            "AppOp rollback branch failed"
        )
    if "rollback could not be verified" not in output:
        fail(
            f"{helper_kind}: independent rollback failure was not reported; "
            f"output was:\n{output}"
        )
    if "always-on settings:" in output.lower():
        fail(
            f"{helper_kind}: output incorrectly reported the successful "
            f"always-on rollback branch as failed:\n{output}"
        )

    failed_always_on = default_state(fail_always_on_verify=True)
    state, _, output = exercise_helper(
        helper_kind,
        failed_always_on,
        ["--enable-always-on"] if helper_kind == "sh" else ["-EnableAlwaysOn"],
        False,
    )
    if state["appop"] != "ignore":
        fail(f"{helper_kind}: failed always-on setup did not restore previous AppOp")
    if any(state["settings"].values()):
        fail(f"{helper_kind}: failed always-on setup did not restore its snapshot")
    if "restored and verified" not in output:
        fail(f"{helper_kind}: failed always-on did not report verified rollback")

    foreign_takeover_settings = {
        "always_on_vpn_app": "com.example.takeover",
        "always_on_vpn_lockdown": "1",
        "always_on_vpn_lockdown_whitelist": "com.example.allowed",
    }
    prompt_enable_takeover = default_state(
        settings_replacement_after_gets={
            "count": 3,
            "settings": foreign_takeover_settings,
        }
    )
    state, log, output = exercise_helper(
        helper_kind,
        prompt_enable_takeover,
        enable_always_on_arg,
        False,
    )
    if state["appop"] != "ignore" or state["settings"] != foreign_takeover_settings:
        fail(f"{helper_kind}: prompt-time enable takeover was not preserved")
    if any(is_watch_mutation(command) for command in log):
        fail(f"{helper_kind}: prompt-time enable takeover was detected after mutation")
    if "ownership changed" not in output.lower():
        fail(f"{helper_kind}: prompt-time enable takeover was not reported:\n{output}")

    prompt_revoke_takeover = default_state(
        appop="allow",
        settings={
            "always_on_vpn_app": "io.github.vladbezm.tailware",
            "always_on_vpn_lockdown": "1",
            "always_on_vpn_lockdown_whitelist": "fixture.allow",
        },
        settings_replacement_after_gets={
            "count": 3,
            "settings": foreign_takeover_settings,
        },
    )
    state, log, _ = exercise_helper(
        helper_kind,
        prompt_revoke_takeover,
        revoke_arg,
        True,
    )
    if state["appop"] != "default" or state["settings"] != foreign_takeover_settings:
        fail(f"{helper_kind}: prompt-time revoke takeover was not preserved")
    if any(
        "settings" in command and ("put" in command or "delete" in command)
        for command in log
    ):
        fail(f"{helper_kind}: revoke wrote prompt-time foreign settings")

    appop_enable_takeover = default_state(
        settings_replacement_on_appop_set={
            "mode": "allow",
            "settings": foreign_takeover_settings,
        }
    )
    state, log, output = exercise_helper(
        helper_kind,
        appop_enable_takeover,
        enable_always_on_arg,
        False,
    )
    if state["appop"] != "ignore" or state["settings"] != foreign_takeover_settings:
        fail(f"{helper_kind}: post-AppOp foreign owner or AppOp rollback was lost")
    if any(
        "settings" in command and ("put" in command or "delete" in command)
        for command in log
    ):
        fail(f"{helper_kind}: post-AppOp foreign owner received settings writes")
    if "ownership changed" not in output.lower():
        fail(f"{helper_kind}: post-AppOp takeover was not reported:\n{output}")

    final_verification_takeover_settings = {
        "always_on_vpn_app": "com.example.takeover",
        "always_on_vpn_lockdown": "0",
        "always_on_vpn_lockdown_whitelist": None,
    }
    final_verification_takeover = default_state(
        settings_replacement_after_gets={
            # Gets 1-3 display state, 4-6 refresh after confirmation, 7-9 refresh the post-AppOp
            # baseline, 10-12 guard each settings write, and 13-15 perform final verification.
            "count": 13,
            "settings": final_verification_takeover_settings,
        }
    )
    state, _, output = exercise_helper(
        helper_kind,
        final_verification_takeover,
        enable_always_on_arg,
        False,
    )
    if state["appop"] != "ignore":
        fail(f"{helper_kind}: final-verification takeover did not restore the previous AppOp")
    if state["settings"] != final_verification_takeover_settings:
        fail(f"{helper_kind}: final-verification takeover was not preserved")
    if "verification failed" not in output.lower():
        fail(f"{helper_kind}: final-verification takeover was not reported:\n{output}")

    mid_sequence_enable_takeover = default_state(
        settings_replacement_after_mutations={
            "count": 1,
            "settings": foreign_takeover_settings,
        }
    )
    state, log, output = exercise_helper(
        helper_kind,
        mid_sequence_enable_takeover,
        enable_always_on_arg,
        False,
    )
    if state["appop"] != "ignore" or state["settings"] != foreign_takeover_settings:
        fail(f"{helper_kind}: mid-sequence enable takeover was not preserved")
    enable_setting_writes = [
        command
        for command in log
        if "settings" in command and ("put" in command or "delete" in command)
    ]
    if len(enable_setting_writes) != 1:
        fail(
            f"{helper_kind}: enable continued settings writes after observable takeover: "
            f"{enable_setting_writes}"
        )
    if "ownership changed" not in output.lower():
        fail(f"{helper_kind}: mid-sequence enable takeover was not reported:\n{output}")

    mid_sequence_revoke_takeover = default_state(
        appop="allow",
        settings={
            "always_on_vpn_app": "io.github.vladbezm.tailware",
            "always_on_vpn_lockdown": "1",
            "always_on_vpn_lockdown_whitelist": "fixture.allow",
        },
        settings_replacement_after_mutations={
            "count": 1,
            "settings": foreign_takeover_settings,
        },
    )
    state, log, output = exercise_helper(
        helper_kind,
        mid_sequence_revoke_takeover,
        revoke_arg,
        False,
    )
    if state["appop"] != "allow" or state["settings"] != foreign_takeover_settings:
        fail(f"{helper_kind}: mid-sequence revoke takeover was not preserved")
    revoke_setting_writes = [
        command
        for command in log
        if "settings" in command and ("put" in command or "delete" in command)
    ]
    if len(revoke_setting_writes) != 1:
        fail(
            f"{helper_kind}: revoke continued settings writes after observable takeover: "
            f"{revoke_setting_writes}"
        )
    if "ownership changed" not in output.lower():
        fail(f"{helper_kind}: mid-sequence revoke takeover was not reported:\n{output}")

    revoke_rollback_takeover = default_state(
        appop="allow",
        fail_default_verify=True,
        settings={
            "always_on_vpn_app": "io.github.vladbezm.tailware",
            "always_on_vpn_lockdown": "1",
            "always_on_vpn_lockdown_whitelist": "fixture.allow",
        },
        settings_replacement_on_appop_set={
            "mode": "default",
            "settings": foreign_takeover_settings,
        },
    )
    state, log, output = exercise_helper(
        helper_kind,
        revoke_rollback_takeover,
        revoke_arg,
        False,
    )
    if state["appop"] != "allow" or state["settings"] != foreign_takeover_settings:
        fail(f"{helper_kind}: revoke rollback overwrote a post-AppOp foreign owner")
    default_index = next(
        index
        for index, command in enumerate(log)
        if command_ends_with(command, ["ACTIVATE_VPN", "default"])
    )
    if any(
        "settings" in command and ("put" in command or "delete" in command)
        for command in log[default_index + 1 :]
    ):
        fail(f"{helper_kind}: rollback wrote settings after a foreign takeover")
    if "rollback could not be verified" not in output.lower():
        fail(f"{helper_kind}: guarded revoke rollback failure was not explicit:\n{output}")

    post_appop_baseline = {
        "always_on_vpn_app": None,
        "always_on_vpn_lockdown": "1",
        "always_on_vpn_lockdown_whitelist": "post.appop.allow",
    }
    post_appop_snapshot = default_state(
        fail_always_on_verify=True,
        settings_replacement_on_appop_set={
            "mode": "allow",
            "settings": post_appop_baseline,
        },
    )
    state, _, _ = exercise_helper(
        helper_kind,
        post_appop_snapshot,
        enable_always_on_arg,
        False,
    )
    if state["appop"] != "ignore" or state["settings"] != post_appop_baseline:
        fail(f"{helper_kind}: rollback did not use the post-AppOp settings snapshot")

    failed_rollback_command = default_state(fail_lockdown_put=True)
    state, _, output = exercise_helper(
        helper_kind,
        failed_rollback_command,
        ["--enable-always-on"] if helper_kind == "sh" else ["-EnableAlwaysOn"],
        False,
    )
    if state["appop"] != "ignore":
        fail(
            f"{helper_kind}: a settings rollback command failure prevented "
            "the previous AppOp from being restored"
        )
    expected_rollback_details = (
        ("could not release lockdown before restoring previous always-on settings",)
        if helper_kind == "sh"
        else ("always-on settings:", "forced lockdown put failure")
    )
    if not all(detail in output for detail in expected_rollback_details):
        fail(
            f"{helper_kind}: settings rollback command failure was not reported "
            f"precisely; output was:\n{output}"
        )
    if "rollback could not be verified" not in output:
        fail(
            f"{helper_kind}: settings rollback command failure was not surfaced "
            f"as an unverified rollback; output was:\n{output}"
        )

    transient_settings_read_failure = default_state(
        appop="allow",
        fail_settings_get_once_after_mutation=True,
        settings={
            "always_on_vpn_app": "io.github.vladbezm.tailware",
            "always_on_vpn_lockdown": "1",
            "always_on_vpn_lockdown_whitelist": "fixture.allow",
        },
    )
    state, _, output = exercise_helper(
        helper_kind,
        transient_settings_read_failure,
        ["--revoke"] if helper_kind == "sh" else ["-Revoke"],
        False,
    )
    if state["appop"] != "allow" or state["settings"] != transient_settings_read_failure[
        "settings"
    ]:
        fail(
            f"{helper_kind}: failed settings verification did not restore the "
            "exact AppOp/always-on snapshot"
        )
    if "restored and verified" not in output:
        fail(
            f"{helper_kind}: failed settings read was mistaken for successful "
            f"verification or rollback was unclear:\n{output}"
        )


def run_fixtures() -> None:
    run_url_literal_fixtures()
    missing: list[str] = []
    if not POSIX_SHELL:
        missing.append("a POSIX /bin/sh")
    if not POWERSHELL:
        missing.append("PowerShell (pwsh or powershell)")
    if missing:
        fail(
            "all helper fixtures were requested, but the following interpreters "
            f"are unavailable: {', '.join(missing)}"
        )
    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = {
            helper_kind: executor.submit(run_helper_fixtures, helper_kind)
            for helper_kind in ("sh", "pwsh")
        }
        for helper_kind in ("sh", "pwsh"):
            futures[helper_kind].result()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--mode", choices=("development", "release"), default="development"
    )
    parser.add_argument(
        "--run-fixtures",
        action="store_true",
        help="exercise helpers against a fake ADB (never a real device)",
    )
    args = parser.parse_args()

    try:
        validate_static_contracts(args.mode)
        if args.run_fixtures:
            run_fixtures()
    except AssertionError as error:
        print(f"Wear VPN bootstrap validation failed: {error}", file=sys.stderr)
        return 1

    print(f"Wear VPN bootstrap validation passed ({args.mode}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
