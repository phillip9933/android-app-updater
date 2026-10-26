#!/usr/bin/env python3
"""Build and export a clean committed revision, never artifacts from a developer's cache."""
from pathlib import Path, PurePosixPath
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
VERSION = "0.2.1"
GROUP = "io.github.phillip9933"
ARTIFACT = "android-updater"
COORDINATE = Path(*GROUP.split(".")) / ARTIFACT / VERSION


def git(*arguments):
    return subprocess.run(["git", *arguments], cwd=ROOT, check=True, capture_output=True).stdout


def clean_revision():
    if git("status", "--porcelain=v1", "--untracked-files=all"):
        raise SystemExit("Export requires a clean committed checkout, including no untracked files")
    return git("rev-parse", "HEAD").decode().strip()


def committed_files(revision):
    snapshot = git("archive", "--format=tar", revision)
    files = {}
    with tarfile.open(fileobj=io.BytesIO(snapshot)) as archive:
        for member in archive:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise SystemExit("Unsafe path in source snapshot")
            if member.isdir():
                continue
            if not member.isfile():
                raise SystemExit("Source snapshot must contain only regular files")
            # Git tracks only the executable bit; archive umasks must not change artifact bytes.
            mode = 0o755 if member.mode & 0o111 else 0o644
            files[member.name] = (archive.extractfile(member).read(), mode)
    build_file = files.get("updater/build.gradle.kts", (b"", 0))[0].decode()
    if f'version = "{VERSION}"' not in build_file or f'group = "{GROUP}"' not in build_file:
        raise SystemExit("Exporter identity differs from committed library publication identity")
    if not {"LICENSE", "gradlew", "gradlew.bat"}.issubset(files):
        raise SystemExit("Source snapshot is incomplete")
    return files


def verify_artifacts(directory):
    names = [f"{ARTIFACT}-{VERSION}{suffix}" for suffix in (".aar", ".pom", ".module", "-sources.jar")]
    for name in names:
        path = directory / name
        if path.is_symlink() or not path.is_file():
            raise SystemExit(f"Fresh publication is missing a regular artifact: {name}")
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(directory / names[1]).getroot()
    identity = tuple(pom.findtext(f"m:{key}", namespaces=ns) for key in ("groupId", "artifactId", "version"))
    module = json.loads((directory / names[2]).read_text())["component"]
    if identity != (GROUP, ARTIFACT, VERSION) or tuple(module.get(k) for k in ("group", "module", "version")) != identity:
        raise SystemExit("Fresh publication has an unexpected Maven identity")
    for name in (names[0], names[3]):
        with zipfile.ZipFile(directory / name) as archive:
            if archive.testzip() is not None:
                raise SystemExit("Corrupt publication archive")
    return names


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: export_bundle.py DESTINATION")
    destination = Path(sys.argv[1]).resolve()
    if destination.is_relative_to(ROOT) and not destination.is_relative_to(ROOT / "build"):
        raise SystemExit("Export outside the source tree or under build/")
    if destination.exists() and (not destination.is_dir() or any(destination.iterdir())):
        raise SystemExit("Destination must be new or empty; do not overwrite a distributed pin")
    revision = clean_revision()
    files = committed_files(revision)
    (ROOT / "build").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="export-", dir=ROOT / "build") as temporary:
        checkout = Path(temporary) / "checkout"
        checkout.mkdir()
        for name, (data, mode) in files.items():
            path = checkout / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            path.chmod(mode)
        # This empty checkout has no old build outputs. Disable build-cache reuse too.
        wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        subprocess.run(
            [wrapper, "--no-daemon", "--no-build-cache", "--rerun-tasks",
             ":updater:publishReleasePublicationToBundleRepository"], cwd=checkout, check=True,
        )
        artifacts = checkout / "build/maven" / COORDINATE
        names = verify_artifacts(artifacts)
        if clean_revision() != revision:
            raise SystemExit("Checkout revision changed during export; retry from the reviewed revision")
        bundle = Path(temporary) / "bundle"
        target = bundle / "maven" / COORDINATE
        target.mkdir(parents=True)
        for name in names:
            shutil.copyfile(artifacts / name, target / name)
        (bundle / "LICENSE").write_bytes(files["LICENSE"][0])
        (bundle / "SOURCE_REVISION.txt").write_text(revision + "\n", encoding="utf-8")
        with zipfile.ZipFile(bundle / f"android-app-updater-{VERSION}-source.zip", "w") as archive:
            for name, (data, mode) in sorted(files.items()):
                info = zipfile.ZipInfo(f"android-app-updater-{VERSION}/{name}")
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = mode << 16
                archive.writestr(info, data)
        entries = [
            f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(bundle).as_posix()}\n"
            for path in sorted(bundle.rglob("*")) if path.is_file()
        ]
        (bundle / "SHA256SUMS").write_text("".join(entries), encoding="utf-8")
        # Stage on the destination filesystem so the final rename exposes one complete result.
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".updater-export-", dir=destination.parent) as staging:
            completed = Path(staging) / "bundle"
            shutil.copytree(bundle, completed)
            if destination.exists():
                destination.rmdir()
            completed.rename(destination)
    print(f"Exported {VERSION} from {revision}; {len(entries)} pinned files")


if __name__ == "__main__":
    main()
