#!/usr/bin/env python3
"""Capsule security inventory. Heuristic evidence, not a vulnerability-free certificate.

No third-party Python deps. Never prints secret values or network URLs with tokens.
"""
from __future__ import annotations
import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RULES = [
    ("NET-001", "HIGH", "app/src/main/AndroidManifest.xml", r'android:usesCleartextTraffic="true"',
     "HTTP is globally permitted; investigate LAN radio/WS users before changing globally."),
    ("DATA-001", "HIGH", "app/src/main/AndroidManifest.xml", r'android:allowBackup="true"',
     "Auto Backup is enabled. Verify credentials and WebView/session files are excluded."),
    ("DATA-002", "HIGH", "app/src/main/kotlin/com/nikhil/yt/viewmodels/BackupRestoreViewModel.kt",
     r'for\s*\(\(key, value\) in prefs\)', "Manual settings backup serializes all preferences, including auth cookies/tokens, without encryption."),
    ("DATA-003", "HIGH", "app/src/main/res/xml/data_extraction_rules.xml", r'<cloud-backup>',
     "Auto Backup rules do not explicitly exclude the datastore directory containing auth tokens."),
    ("FILE-001", "HIGH", "app/src/main/res/xml/provider_paths.xml", r'<external-path[^>]*',
     "FileProvider grants can cover all external storage; review every URI sharing path."),
    ("WEB-001", "HIGH", "app/src/main/kotlin/com/nikhil/yt/ui/screens/settings/DiscordLoginScreen.kt",
     r'addJavascriptInterface\(', "Discord login exposes JS-to-native bridge in a remote WebView."),
    ("WEB-002", "HIGH", "app/src/main/kotlin/com/nikhil/yt/ui/screens/LoginScreen.kt",
     r'addJavascriptInterface\(', "YouTube login exposes JS-to-native bridge in a remote WebView."),
    ("WEB-003", "HIGH", "app/src/main/kotlin/com/nikhil/yt/ui/screens/settings/PoTokenExtractionActivity.kt",
     r'addJavascriptInterface\(', "PoToken extraction exposes JS bridge to a navigable WebView."),
    ("WEB-004", "HIGH", "app/src/main/kotlin/com/nikhil/yt/ui/screens/settings/DiscordLoginScreen.kt",
     r'!url\.contains\("discord\.com"\)', "Substring host validation admits lookalike domains; exact HTTPS origin is required."),
    ("WEB-005", "HIGH", "app/src/main/kotlin/com/nikhil/yt/ui/screens/settings/DiscordLoginScreen.kt",
     r'completeLogin\(message\)', "A JavaScript alert message can be accepted as an account token."),
    ("DATA-004", "MEDIUM", "app/src/main/kotlin/com/nikhil/yt/viewmodels/BackupRestoreViewModel.kt",
     r'zip\.copyTo\(out\)', "Backup restoration has no explicit extracted-byte cap; test archive bombs."),
    ("INPUT-001", "MEDIUM", "app/src/main/kotlin/com/nikhil/yt/viewmodels/BackupRestoreViewModel.kt",
     r'bufferedReader\(\)\.readLines\(\)', "Unbounded M3U input is read fully into memory."),
    ("NET-002", "MEDIUM", "app/src/main/kotlin/com/nikhil/yt/together/TogetherServer.kt",
     r'host\s*=\s*"0\.0\.0\.0"', "Together WebSocket binds all interfaces. Check join throttling, frame limits, room-key exposure."),
    ("NET-003", "MEDIUM", "app/src/main/kotlin/com/nikhil/yt/together/TogetherLink.kt",
     r'"ws://\$host:\$port', "LAN Together room handshake travels over plaintext ws; attacker on LAN can potentially observe room key."),
    ("BUILD-001", "MEDIUM", "app/build.gradle.kts",
     r'buildConfigField\("String", "LASTFM_SECRET"', "BuildConfig ships a Last.fm secret in the APK if configured; APK constants are public."),
    ("BUILD-002", "MEDIUM", "app/build.gradle.kts",
     r'buildConfigField\("String", "TOGETHER_BEARER_TOKEN"', "BuildConfig bearer token cannot be a server-side authentication secret."),
    ("BUILD-003", "LOW", "gradle/wrapper/gradle-wrapper.properties",
     r'distributionUrl=', "Gradle distribution checksum is not pinned (distributionSha256Sum)."),
    ("BUILD-004", "MEDIUM", "settings.gradle.kts",
     r'https://jitpack\.io', "Build uses JitPack; audit upstream repositories and pin dependency commits."),
    ("REL-001", "MEDIUM", ".github/workflows/newpipe-extractor-update.yml",
     r'git push --force', "An automated workflow force-updates a tag; check branch protection and release provenance."),
]
BLOCKERS = [
    ("TOKEN_IN_LOG", "app/src/main/kotlin/com/nikhil/yt/ui/screens/settings/DiscordLoginScreen.kt",
     r'(?m)^\s*Log\.[dievw]\(\s*"DiscordWebView"\s*,\s*"Token:\s*\$token'),
    ("RELEASE_WEBVIEW_DEVTOOLS", "app/src/main/kotlin/com/nikhil/yt/ui/screens/settings/DiscordLoginScreen.kt",
     r'WebView\.setWebContentsDebuggingEnabled\(true\)'),
]
SECRETS = [
    ("private-key", re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')),
    ("github-token", re.compile(r'gh[pousr]_[A-Za-z0-9_]{20,}')),
    ("aws-access-key", re.compile(r'AKIA[0-9A-Z]{16}')),
    ("google-api-key", re.compile(r'AIza[0-9A-Za-z_-]{35}')),
]
SCAN_EXT = {".kt", ".java", ".kts", ".xml", ".toml", ".yml", ".yaml", ".json", ".properties", ".sh", ".py"}
SKIP_DIRS = {"build", ".git", ".gradle", ".idea", "node_modules", ".venv"}


def read(path: str) -> str:
    file = ROOT / path
    try:
        return file.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def source_findings():
    findings = []
    for ident, severity, path, pattern, description in RULES:
        data = read(path)
        match = re.search(pattern, data, re.IGNORECASE)
        if match:
            line = data.count("\n", 0, match.start()) + 1
            findings.append(dict(id=ident, severity=severity, path=path, line=line, evidence=description,
                                 status="requires-review"))
    blocking = []
    for ident, path, pattern in BLOCKERS:
        data = read(path)
        if re.search(pattern, data):
            blocking.append(dict(id=ident, path=path, status="regression", severity="CRITICAL"))
    for file in ROOT.rglob("*"):
        if not file.is_file() or file.suffix not in SCAN_EXT or SKIP_DIRS.intersection(file.parts):
            continue
        try:
            if file.stat().st_size > 2_000_000:
                continue
            data = file.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for kind, regex in SECRETS:
            for match in regex.finditer(data):
                blocking.append(dict(id="POSSIBLE_SECRET_" + kind.upper(), severity="CRITICAL",
                                    path=str(file.relative_to(ROOT)),
                                    line=data.count("\n", 0, match.start()) + 1,
                                    status="requires-human-triage"))
    return findings, blocking


def parse_gradle_dependencies(text: str):
    # Gradle writes an ASCII dependency tree. "group:name:old -> new"
    # means Gradle resolved to the NEW version; only inspect Maven coordinates.
    found = set()
    for line in text.splitlines():
        match = re.search(r'(?:(?:\+|\\)---|---)\s+([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([^\s()]+)(?:\s+->\s+([^\s()]+))?', line)
        if not match:
            continue
        group, artifact, old, resolved = match.groups()
        version = (resolved or old).strip("()")
        if version and not any(x in version for x in ("$", "{", "unspecified", "FAILED", "*", "+")):
            found.add((group + ":" + artifact, version))
    return sorted(found)


def scan_osv(packages):
    if not packages:
        return dict(status="incomplete", reason="No resolved Maven packages", queried=0, findings=[])
    findings = []
    for offset in range(0, len(packages), 90):
        batch = packages[offset:offset + 90]
        payload = json.dumps({"queries":[{"version":ver,"package":{"name":name,"ecosystem":"Maven"}}
                                        for name,ver in batch]}).encode()
        request = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=payload,
                                         headers={"Content-Type":"application/json", "User-Agent":"CapsuleAudit/1"})
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                result = json.load(response)
            rows = result["results"]
            if len(rows) != len(batch):
                raise ValueError("OSV result length mismatch")
        except (OSError, ValueError, KeyError, TimeoutError) as exc:
            return dict(status="incomplete", reason=type(exc).__name__,
                        queried=offset, total=len(packages), findings=findings)
        for (name,version), entry in zip(batch, rows):
            for vuln in entry.get("vulns", []):
                findings.append(dict(package=name, version=version, id=vuln.get("id", "UNKNOWN")))
    return dict(status="completed", queried=len(packages), findings=findings)


def main():
    args = argparse.ArgumentParser()
    args.add_argument("--dependencies", help="Gradle resolved runtime dependency tree")
    args.add_argument("--output", default="security-results")
    opt = args.parse_args()
    out = ROOT / opt.output
    out.mkdir(parents=True, exist_ok=True)
    findings, blockers = source_findings()
    packages = parse_gradle_dependencies(Path(opt.dependencies).read_text(errors="replace")) if opt.dependencies else []
    osv = scan_osv(packages) if opt.dependencies else {"status":"not-run", "reason":"no Gradle dependency report", "queried":0, "findings":[]}
    report = {"generated":datetime.now(timezone.utc).isoformat(), "type":"static-heuristic",
              "scope":"checked-out source and resolved Maven runtime dependencies",
              "caveats":["Not a malware verdict", "Not an APK or dynamic network inspection",
                         "Rules are evidence candidates, not exploited vulnerability proofs",
                         "OSV reports depend on successful public API access"],
              "findings":findings, "blockers":blockers,
              "runtime_dependencies":len(packages), "osv":osv}
    (out / "source-and-dependency.json").write_text(json.dumps(report, indent=2, ensure_ascii=False)+"\n")
    md = ["# Capsule Music automated security inventory", "",
          "**Heuristic static checks only. Not a security certification.**", "",
          f"Findings requiring manual review: {len(findings)}",
          f"Security regressions / candidate secrets: {len(blockers)}",
          f"Resolved Maven packages: {len(packages)}",
          f"OSV status: {osv['status']} ({osv.get('queried',0)} packages queried)",
          f"OSV advisories: {len(osv.get('findings',[]))}", ""]
    for f in findings:
        md.append(f"- **{f['severity']} {f['id']}** `{f['path']}:{f['line']}` — {f['evidence']}")
    if blockers:
        md.extend(["","## BLOCKERS"])
        md.extend(f"- {b['id']} in {b['path']} (no secret material printed)" for b in blockers)
    if osv.get("findings"):
        md.extend(["","## Public advisory matches (OSV; validate reachability)"])
        md.extend(f"- {v['package']} {v['version']} — {v['id']}" for v in osv["findings"])
    (out / "security-summary.md").write_text("\n".join(md)+"\n")
    print(f"Source findings={len(findings)} blockers={len(blockers)} "
          f"Maven packages={len(packages)} OSV={osv['status']} advisories={len(osv.get('findings', []))}")
    if blockers:
        print("Blocking finding IDs: " + ", ".join(x["id"] for x in blockers))
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
