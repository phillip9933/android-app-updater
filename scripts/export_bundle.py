#!/usr/bin/env python3
"""Export one locally built immutable-version candidate and its corresponding source."""
from pathlib import Path
import hashlib
import shutil
import sys
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
VERSION = "0.1.3"
COORDINATE = Path("dev/rogers/android-updater") / VERSION


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: export_bundle.py DESTINATION")
    if f'version = "{VERSION}"' not in (ROOT / "updater/build.gradle.kts").read_text():
        raise SystemExit("Exporter version differs from library publication version")
    destination = Path(sys.argv[1]).resolve()
    if destination.is_relative_to(ROOT) and not destination.is_relative_to(ROOT / "build"):
        raise SystemExit("Export outside the source tree or under build/ to avoid recursive source snapshots")
    if destination.exists() and any(destination.iterdir()):
        raise SystemExit("Destination must be new or empty; do not overwrite a distributed pin")
    artifacts = [f"android-updater-{VERSION}{suffix}" for suffix in (".aar", ".pom", ".module", "-sources.jar")]
    source = ROOT / "build/maven" / COORDINATE
    for name in artifacts:
        if not (source / name).is_file():
            raise SystemExit(f"Build publication before exporting: missing {name}")
    target = destination / "maven" / COORDINATE
    target.mkdir(parents=True)
    for name in artifacts:
        shutil.copyfile(source / name, target / name)
    shutil.copyfile(ROOT / "LICENSE", destination / "LICENSE")
    archive = destination / f"android-app-updater-{VERSION}-source.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
        paths = subprocess.run(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
            cwd=ROOT, check=True, capture_output=True,
        ).stdout.decode("utf-8").split("\0")
        for name in sorted(set(paths) - {""}):
            relative = Path(name)
            path = ROOT / relative
            if path.is_symlink():
                raise SystemExit("Source snapshot must not contain symbolic links")
            if not path.is_file():
                continue
            info = zipfile.ZipInfo(f"android-app-updater-{VERSION}/{relative.as_posix()}")
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o755 if path.name == "gradlew" else 0o644) << 16
            output.writestr(info, path.read_bytes())
    entries = []
    for path in sorted(destination.rglob("*")):
        if path.is_file():
            entries.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(destination).as_posix()}\n")
    (destination / "SHA256SUMS").write_text("".join(entries), encoding="utf-8")
    print(f"Exported version {VERSION}; {len(entries)} pinned files")


if __name__ == "__main__":
    main()
