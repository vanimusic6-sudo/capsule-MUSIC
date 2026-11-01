#!/usr/bin/env python3
"""Check install identity in compiled APK metadata, without launching the APK."""
import argparse
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SUFFIXES = {"release": "", "dev": ".dev", "debug": ".debug"}


def release_package():
    matches = re.findall(r"^capsuleApplicationId=([a-zA-Z0-9_.]+)$",
                         (ROOT / "gradle.properties").read_text(), re.M)
    if len(matches) != 1 or not matches[0].startswith("com.vanimusic."):
        raise ValueError("Missing or ambiguous Capsule install identity")
    return matches[0]


def parse_xmltree(output):
    """Read the element/string-attribute subset of Android aapt's XML dump."""
    stack = []
    root = None
    for line in output.splitlines():
        match = re.match(r"(\s*)E: ([\w.-]+)", line)
        if match:
            depth = len(match[1])
            while stack and stack[-1][0] >= depth:
                stack.pop()
            node = ET.Element(match[2])
            if stack:
                stack[-1][1].append(node)
            elif root is None:
                root = node
            stack.append((depth, node))
        else:
            match = re.match(r'\s*A: ([\w:.-]+)(?:\([^)]*\))?="([^"\n]*)"', line)
            if match and stack:
                stack[-1][1].set(match[1].removeprefix("android:"), match[2])
    if root is None or root.tag != "manifest":
        raise ValueError("No readable compiled Android manifest")
    return root


def validate_manifest(root, expected):
    if root.get("package") != expected:
        raise ValueError("Compiled manifest has a different install package")
    app = root.find("application")
    if app is None or app.get("name") != "com.nikhil.yt.App":
        raise ValueError("Application class was rebound to the install package")
    for kind, name in [("activity", "com.nikhil.yt.MainActivity"),
                       ("service", "com.nikhil.yt.playback.MusicService")]:
        if not any(n.get("name") == name for n in app.findall(kind)):
            raise ValueError("Missing fully qualified " + kind + " class")
    authorities = []
    for provider in app.findall("provider"):
        values = provider.get("authorities", "").split(";")
        if any(not a.startswith(expected + ".") for a in values):
            raise ValueError("Provider authority collides with another install")
        authorities.extend(values)
    if not {expected + ".FileProvider", expected + ".capsule.video.extractor"} <= set(authorities):
        raise ValueError("Required Capsule provider authority is missing")
    if any(n.get("scheme") == "velune" for n in app.findall(".//data")):
        raise ValueError("Capsule must not claim Velune's implicit link scheme")
    return authorities


def verify_apk_identity(apk, aapt, variant):
    expected = release_package() + SUFFIXES[variant]
    badging = subprocess.run([aapt, "dump", "badging", str(apk)], check=True,
                             capture_output=True, text=True, timeout=60).stdout
    match = re.search(r"^package: name='([^']+)'", badging, re.M)
    if not match or match[1] != expected:
        raise ValueError("APK install ID differs from the selected Capsule variant")
    manifest = subprocess.run([aapt, "dump", "xmltree", str(apk), "AndroidManifest.xml"],
                              check=True, capture_output=True, text=True, timeout=60).stdout
    authorities = validate_manifest(parse_xmltree(manifest), expected)
    return {"status": "verified", "variant": variant, "package": expected,
            "authorities": authorities, "source_namespace": "com.nikhil.yt"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=SUFFIXES, required=True)
    parser.add_argument("--print-package", action="store_true")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--aapt")
    args = parser.parse_args()
    if args.print_package:
        print(release_package() + SUFFIXES[args.variant])
        return
    if args.manifest:
        root = ET.parse(args.manifest).getroot()
        for node in root.iter():
            node.attrib = {k.removeprefix("{http://schemas.android.com/apk/res/android}"): v
                           for k, v in node.attrib.items()}
        expected = release_package() + SUFFIXES[args.variant]
        authorities = validate_manifest(root, expected)
        print(json.dumps({"status": "verified", "variant": args.variant,
                          "package": expected, "authorities": authorities}, sort_keys=True))
        return
    if not args.apk or not args.aapt:
        parser.error("--apk and --aapt are required for compiled verification")
    print(json.dumps(verify_apk_identity(args.apk, args.aapt, args.variant), sort_keys=True))


if __name__ == "__main__":
    main()
