#!/usr/bin/env python3
"""Fail-closed checks against the actual signed Capsule universal release APK.

Only examines package metadata, declared permissions and signer provenance.
It is not a malware scan, DEX taint analysis, or TLS/network inspection.
Never prints user credentials.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

EXPECTED_PACKAGE = "com.nikhil.yt"
# Pinned to the previously verified GitHub-signed release certificate.
# If rotating signing keys, update only after an explicit owner decision.
EXPECTED_SIGNER_SHA256 = "c044579fd886ee7f6ae919d705fb3345f7417957d8cd49fd110d596f864ddd7d"
FORBIDDEN_PERMISSIONS = {
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.GET_ACCOUNTS",
    "android.permission.RECORD_AUDIO",
    "android.permission.CAMERA",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.READ_SMS",
    "android.permission.READ_CALL_LOG",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
}


def verify(apk: Path, apksigner: str, aapt: str):
    if not apk.is_file() or apk.stat().st_size == 0:
        raise ValueError("Release APK is missing or empty")
    sha256 = hashlib.file_digest(apk.open("rb"), "sha256").hexdigest()
    sig = subprocess.run([apksigner, "verify", "--verbose", "--print-certs", str(apk)],
                         text=True, capture_output=True, timeout=90, check=True).stdout
    m = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-f]{64})", sig, re.I)
    if not m:
        raise ValueError("Missing signer certificate digest")
    cert = m.group(1).lower()
    if cert != EXPECTED_SIGNER_SHA256:
        raise ValueError("Release certificate differs from trusted baseline")
    if re.search(r"Signer #2 certificate SHA-256 digest:", sig):
        raise ValueError("Unexpected extra APK signer")

    badging = subprocess.run([aapt, "dump", "badging", str(apk)],
                            text=True, capture_output=True, timeout=60, check=True).stdout
    pkg = re.search(r"^package: name='([^']+)'", badging, re.M)
    if not pkg or pkg.group(1) != EXPECTED_PACKAGE:
        raise ValueError("Unexpected APK package ID")
    if re.search(r"^application-debuggable\b", badging, re.M):
        raise ValueError("Release APK is debuggable")

    permissions = set(re.findall(r"^uses-permission: name='([^']+)'", badging, re.M))
    prohibited = sorted(permissions & FORBIDDEN_PERMISSIONS)
    if prohibited:
        raise ValueError("Forbidden sensitive runtime permissions: " + ", ".join(prohibited))

    with zipfile.ZipFile(apk) as z:
        entries = z.namelist()
        if len(entries) != len(set(entries)):
            raise ValueError("Duplicate ZIP entries in release APK")
        dex = [n for n in entries if re.fullmatch(r"classes(?:\d+)?\.dex", n)]
        if not dex:
            raise ValueError("Release APK is missing Android DEX bytecode")
    return dict(
        status="verified", package=EXPECTED_PACKAGE, sha256=sha256,
        signer_sha256=cert, dex_count=len(dex), entry_count=len(entries),
        permissions=sorted(permissions), prohibited_permissions=prohibited,
        caveat="This verifies signature, package, permission policy and ZIP structure; it cannot prove malware absence."
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--output", type=Path, default=Path("security-results/current-release-check.json"))
    args = parser.parse_args()
    try:
        info = verify(args.apk, args.apksigner, args.aapt)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n")
        print("Verified signed release package, pinned certificate and permission policy.")
        print("Release SHA-256:", info["sha256"])
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired, zipfile.BadZipFile) as exc:
        # Never print command output: APK metadata might contain user-controlled strings.
        print("Release provenance/security verification FAILED: " + type(exc).__name__ +
              " " + str(exc).split("\n", 1)[0][:260], file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
