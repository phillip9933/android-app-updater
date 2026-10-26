"""Narrow, owner-authorized synthetic Pixel acceptance controls; never a general device reset tool."""
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "io.github.phillip9933.updateracceptance.install20261026"
OUTPUT = ROOT.parent / "outputs/physical-acceptance-2026-10-26"
ALLOWED = {PACKAGE, "com.android.settings", "com.google.android.packageinstaller",
           "com.android.packageinstaller", "com.google.android.permissioncontroller",
           "com.android.permissioncontroller", "android", "com.android.systemui"}


def adb(*args):
    serial = (ROOT / "build/pixel-serial.txt").read_text()
    result = subprocess.run(["/home/phil/Android/Sdk/platform-tools/adb", "-s", serial, *args],
                            capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def snapshot(label):
    adb("shell", "uiautomator", "dump", "/data/local/tmp/updater-acceptance-ui.xml")
    text = adb("shell", "cat", "/data/local/tmp/updater-acceptance-ui.xml")
    tree = ET.fromstring(text)
    packages = {n.get("package") for n in tree.iter("node") if n.get("package")}
    if not packages.issubset(ALLOWED) or not packages.intersection(ALLOWED - {"android", "com.android.systemui"}):
        raise RuntimeError("Unexpected foreground UI; refusing to capture or interact with another app")
    (OUTPUT / (label + ".xml")).write_text(text)
    print(" | ".join(n.get("text") for n in tree.iter("node")
                     if n.get("text") and n.get("package") != "com.android.systemui"), flush=True)
    return tree


def tap(text, label):
    tree = snapshot(label + "-before")
    nodes = [n for n in tree.iter("node") if n.get("text") == text or n.get("content-desc") == text]
    if len(nodes) != 1:
        raise RuntimeError("Expected exactly one visible synthetic/system control: " + text)
    parent = {child: node for node in tree.iter() for child in node}
    node = nodes[0]
    while node.get("clickable") != "true" and node in parent:
        node = parent[node]
    if node.get("clickable") != "true" or node.get("enabled") != "true":
        raise RuntimeError("Requested control is not clickable/enabled: " + text)
    x1, y1, x2, y2 = map(int, re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds")).groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def launch():
    adb("shell", "am", "start", "-n", PACKAGE + "/example.installhost.HostActivity")


def stop():
    adb("shell", "am", "force-stop", PACKAGE)


def back():
    snapshot("before-back")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
