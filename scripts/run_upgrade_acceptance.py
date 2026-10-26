#!/usr/bin/env python3
"""Run signed synthetic host replacement on an updater-owned disposable emulator only."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "io.github.phillip9933.updateracceptance.migration"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--project", type=Path, default=ROOT / "build/upgrade-acceptance")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise SystemExit("This runner only accepts an updater-owned disposable emulator, never a physical device")
    if args.output.exists():
        raise SystemExit("Use a new output directory; retain previous evidence")
    args.output.mkdir(parents=True)

    def adb(*command):
        result = subprocess.run([str(args.adb), "-s", args.serial, *command],
                                capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    name = adb("emu", "avd", "name").splitlines()[0]
    if not name.startswith("updater-acceptance-"):
        raise SystemExit("Refusing to mutate another project's emulator")
    if adb("shell", "pm", "list", "packages", PACKAGE).strip():
        raise SystemExit("Synthetic host already exists; preserve evidence and use a fresh updater emulator")
    project = args.project.resolve()
    if not project.is_relative_to(ROOT / "build"):
        raise SystemExit("Expected a generated acceptance project under repo/build")
    output = project / "app/build/outputs/apk"
    apks = {f"{flavor}-{kind}": next(output.glob(
        f"{'androidTest/' if kind == 'test' else ''}{flavor}/debug/*.apk"))
        for flavor in ("legacy", "current") for kind in ("app", "test")}
    evidence = {"avd": name, "api": adb("shell", "getprop", "ro.build.version.sdk").strip(),
                "sdk_inputs": json.loads((project / "SDK_INPUTS.json").read_text()),
                "apks": {key: {"name": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                         for key, path in apks.items()}}
    (args.output / "environment.json").write_text(json.dumps(evidence, indent=2) + "\n")

    def instrument(method, scenario):
        text = adb("shell", "am", "instrument", "-w", "-r", "-e", "automatic", scenario,
                   "-e", "class", f"example.upgrade.UpgradeTest#{method}",
                   PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
        (args.output / f"{scenario}-{method}.txt").write_text(text)
        if "OK (1 test)" not in text:
            raise RuntimeError(text)
        print(f"PASS automatic={scenario} {method}", flush=True)

    for scenario in ("true", "false"):
        if scenario == "false":
            adb("uninstall", PACKAGE + ".test")
            adb("uninstall", PACKAGE)
        for kind in ("app", "test"):
            adb("install", str(apks[f"legacy-{kind}"]))
        instrument("seedLegacy", scenario)
        adb("shell", "am", "force-stop", PACKAGE)
        for kind in ("app", "test"):
            adb("install", "-r", str(apks[f"current-{kind}"]))
        instrument("verifyUpgrade", scenario)
        adb("shell", "am", "force-stop", PACKAGE)
        instrument("verifyWorkerOnlyRecreation", scenario)
    print("Both synthetic host upgrade scenarios passed. No uninstall occurred during either upgrade.")


if __name__ == "__main__":
    main()
