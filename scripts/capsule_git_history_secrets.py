#!/usr/bin/env python3
"""Audit ADDED lines in git history for credential patterns, without printing secrets.

This is targeted pattern detection, not proof of complete secret discovery.
Do NOT upload git patches or the raw matches to CI artifacts.
"""
import argparse
import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

PATTERNS = {
    "PRIVATE_KEY": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "GITHUB_TOKEN": re.compile(r"gh[pousr]_[A-Za-z0-9_]{30,}"),
    "GITHUB_FINE_GRAINED": re.compile(r"github_pat_[A-Za-z0-9_]{40,}"),
    "AWS_ACCESS_KEY": re.compile(r"AKIA[0-9A-Z]{16}"),
    "OPENAI_API_KEY": re.compile(r"sk-(?:proj-)?[A-Za-z0-9_-]{30,}"),
    "SLACK_TOKEN": re.compile(r"xox[baprs]-[0-9A-Za-z-]{20,}"),
    "DISCORD_WEBHOOK": re.compile(r"https://(?:discord(?:app)?\.com)/api/webhooks/\d+/[A-Za-z0-9_-]{20,}"),
    "GOOGLE_API_KEY": re.compile(r"AIza[0-9A-Za-z_-]{35}"),
}
MAX_REPORT = 200


def scan() -> tuple[dict, int]:
    proc = subprocess.Popen(
        ["git", "log", "--all", "--no-ext-diff", "--no-textconv",
         "--format=CAPSULE_COMMIT:%H", "--patch", "--no-renames", "--"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        errors="replace", bufsize=1,
    )
    commit = "unknown"
    path = "unknown"
    lineno = 0
    findings = []
    count = Counter()
    scanned_added = 0
    seen = set()
    assert proc.stdout is not None
    for raw in proc.stdout:
        line = raw.rstrip("\n")
        if line.startswith("CAPSULE_COMMIT:"):
            commit = line.partition(":")[2].strip()
            continue
        if line.startswith("+++ b/"):
            path = line[6:]
            continue
        if line.startswith("@@ "):
            match = re.search(r"\+(\d+)", line)
            if match:
                lineno = int(match.group(1)) - 1
            continue
        if not line.startswith("+") or line.startswith("+++"):
            if line.startswith(" "):
                lineno += 1
            continue
        lineno += 1
        scanned_added += 1
        content = line[1:]
        for kind, regex in PATTERNS.items():
            for _ in regex.finditer(content):
                # Public client API key used by upstream PoToken extraction,
                # not an account credential. Scope exception to one declaration
                # and path, never the whole source file.
                if (kind == "GOOGLE_API_KEY"
                    and path.endswith("/playback/audio/potoken/PoTokenWebView.kt")
                    and re.search(r"\b(?:private\s+)?const\s+val\s+GOOGLE_API_KEY\b", content)):
                    count["KNOWN_UPSTREAM_PUBLIC_CLIENT_KEY"] += 1
                    continue
                key = (commit, path, lineno, kind)
                if key in seen:
                    continue
                seen.add(key)
                count[kind] += 1
                if len(findings) < MAX_REPORT:
                    findings.append({
                        "rule": kind, "commit": commit[:12],
                        "path": path, "line": lineno,
                    })
    proc.stdout.close()
    proc.wait(timeout=60)
    if proc.returncode:
        # stderr never included in artifact, to avoid exposing git command output.
        return {"status":"INCOMPLETE","reason":"git log command failed","hits":[]}, 2
    result = {
        "status":"review-required" if findings else "no-pattern-matches",
        "scope":"added lines in --all reachable git history; 8 targeted patterns",
        "added_lines_scanned":scanned_added,
        "counts":dict(count),
        "hits":findings,
        "report_truncated":len(seen)>MAX_REPORT,
        "caveat":"No actual secret values collected. Missing a match does not prove no credentials in history.",
    }
    return result, int(bool(seen))


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--output",default="security-results/git-history-credential-patterns.json")
    a=parser.parse_args()
    result, code=scan()
    destination=Path(a.output)
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
    print("Historical added-line secret patterns:",
          result["status"], result.get("counts",{}),
          "scanned added lines:", result.get("added_lines_scanned",0))
    return code


if __name__ == "__main__":
    sys.exit(main())
