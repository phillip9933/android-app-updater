#!/usr/bin/env python3
"""Exercise Gradle's actual rejection paths using only an isolated synthetic Maven repo."""
from pathlib import Path
import hashlib
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
NS = "https://schema.gradle.org/dependency-verification"
ET.register_namespace("", NS)


def main():
    (ROOT / "build").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="dependency-rejection-", dir=ROOT / "build") as scratch:
        project = Path(scratch)
        module = project / "maven/synthetic/updater/fixture/1.0"
        module.mkdir(parents=True)
        jar = module / "fixture-1.0.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("synthetic.txt", "original fixture\n")
        original = jar.read_bytes()
        pom = module / "fixture-1.0.pom"
        pom.write_text("<project><modelVersion>4.0.0</modelVersion><groupId>synthetic.updater</groupId>"
                       "<artifactId>fixture</artifactId><version>1.0</version></project>")
        (project / "settings.gradle").write_text("rootProject.name = 'synthetic-verification'\n")
        build = """repositories { maven { url = uri('maven') } }
configurations { probe }
dependencyLocking { lockAllConfigurations() }
dependencies { probe 'synthetic.updater:fixture:1.0' }
tasks.register('probe') { doLast { configurations.probe.files.each { println it.name } } }
"""
        (project / "build.gradle").write_text(build)
        metadata = ET.parse(ROOT / "gradle/verification-metadata.xml")
        configuration = metadata.getroot().find(f"{{{NS}}}configuration")
        assert configuration.find(f"{{{NS}}}verify-metadata").text == "true"
        assert configuration.find(f"{{{NS}}}trusted-artifacts") is None
        components = metadata.getroot().find(f"{{{NS}}}components")
        fixture = ET.SubElement(components, f"{{{NS}}}component",
                                group="synthetic.updater", name="fixture", version="1.0")
        for artifact in (jar, pom):
            element = ET.SubElement(fixture, f"{{{NS}}}artifact", name=artifact.name)
            ET.SubElement(element, f"{{{NS}}}sha256", value=hashlib.sha256(artifact.read_bytes()).hexdigest())
        destination = project / "gradle/verification-metadata.xml"
        destination.parent.mkdir()
        metadata.write(destination, encoding="UTF-8", xml_declaration=True)

        def run(name, expected=None, extra=()):
            result = subprocess.run([str(ROOT / "gradlew"), "-p", str(project), "--no-daemon",
                                     "--offline", "--dependency-verification", "strict",
                                     "--console=plain", "probe", *extra],
                                    cwd=ROOT, capture_output=True, text=True, timeout=120)
            (project / f"{name}.log").write_text(result.stdout + result.stderr)
            output = result.stdout + result.stderr
            if expected is None:
                assert result.returncode == 0, output
            else:
                assert result.returncode != 0 and expected in output, output
            print(f"PASS {name}", flush=True)

        run("baseline", extra=("--write-locks",))
        jar.write_bytes(original + b"altered synthetic payload")
        run("altered-artifact", "Dependency verification failed")
        jar.write_bytes(original)
        fixture.remove(fixture.find(f"{{{NS}}}artifact"))
        metadata.write(destination, encoding="UTF-8", xml_declaration=True)
        run("unlisted-artifact", "Dependency verification failed")
        (project / "build.gradle").write_text(build.replace("fixture:1.0", "fixture:2.0"))
        run("version-drift", "dependency lock state")
    print("All four isolated dependency verification checks passed.")


if __name__ == "__main__":
    main()
