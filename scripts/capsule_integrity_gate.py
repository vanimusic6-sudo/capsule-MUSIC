#!/usr/bin/env python3
"""Fail closed on code-integrity trust-boundary regressions.

This is a *targeted invariant gate*, not a comprehensive malware scan.
It checks explicitly supported Capsule security architecture; do not
mistake a passing result for RCE/persistence/sandbox proof.
"""
from __future__ import annotations
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
app = ROOT / "app/src/main"
problems: list[str] = []

def require(condition: bool, message: str) -> None:
    if not condition:
        problems.append(message)

manifest = ET.parse(app / "AndroidManifest.xml").getroot()
ns = "{http://schemas.android.com/apk/res/android}"
application = manifest.find("application")
require(application is not None, "Missing application manifest element")
if application is not None:
    require(application.get(ns + "allowBackup") == "false", "Cloud backup must remain disabled")
    providers = {
        item.get(ns + "name"): item
        for item in application.findall("provider")
    }
    video = providers.get(".playback.video.CapsuleVideoExtractorProvider")
    require(video is not None, "Missing video parser boundary")
    if video is not None:
        require(video.get(ns + "exported") == "false", "Video provider must not be externally exported")
        require(video.get(ns + "process") == ":capsule_video", "Video parser process boundary removed")
    file_provider = providers.get("androidx.core.content.FileProvider")
    require(file_provider is not None, "Missing share FileProvider")
    if file_provider is not None:
        require(file_provider.get(ns + "exported") == "false", "FileProvider may expose private files")

file_paths = (app / "res/xml/provider_paths.xml").read_text()
require("<external-path" not in file_paths and "<root-path" not in file_paths,
        "FileProvider must not allow unrestricted filesystem roots")

# Future dynamic code loading must be reviewed explicitly, never enabled
# silently by a dependency/module upgrade.
dangerous_loader = re.compile(
    r"\\b(?:DexClassLoader|InMemoryDexClassLoader|PathClassLoader)\\s*\\("
)
for root in (app / "kotlin", ROOT / "innertube/src/main/kotlin"):
    for file in root.rglob("*.kt"):
        code = file.read_text(encoding="utf-8")
        if dangerous_loader.search(code):
            problems.append(f"Dynamic code loader in {file.relative_to(ROOT)}")

viewmodel = (app / "kotlin/com/nikhil/yt/viewmodels/BackupRestoreViewModel.kt").read_text()
require("BackupRestoreStaging.stage(" in viewmodel,
        "Archive staging-before-database-change security boundary removed")
require("BackupRestoreStaging.installDatabase(" in viewmodel,
        "Crash-recoverable database import removed")

login = (app / "kotlin/com/nikhil/yt/ui/screens/LoginScreen.kt").read_text()
require("addJavascriptInterface(" not in login,
        "Remote account login must not expose an unrestricted JS->native bridge")

if problems:
    for item in problems:
        print("BLOCK:", item)
    raise SystemExit(f"Source integrity invariant failure: {len(problems)}")

print("Targeted code-integrity gate: PASS; no claim of exploit resistance.")
print("OUTSTANDING: :capsule_video shares application UID and InnertubeX is in-process.")
print("OUTSTANDING: protect signing secrets with an owner-approved GitHub Environment.")
