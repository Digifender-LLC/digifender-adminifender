#!/usr/bin/env python3
"""Release preflight for a private Play build (tdd.md#preflight-and-release-gates, ops/release.md).

Checks what can be checked from the repository and the built bundle, then prints a release manifest. It never
reads keystores, passwords, Worker secrets or Firebase credentials, and it does not upload or deploy anything.

  python3 tools/release_preflight.py check --aab android/app/build/outputs/bundle/release/app-release.aab \
      --version-code 2 --env staging [--model-manifest models/x.json] [--out release-manifest.json]
  python3 tools/release_preflight.py record --manifest release-manifest.json   # after a successful upload
  python3 tools/release_preflight.py assetlinks --package com.digifender.adminifender --sha256 AB:CD:...
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RELEASES = ROOT / "ops" / "releases.json"
SECRET_PATTERNS = [
    r"(^|/)\.env($|\.)",
    r"(^|/)\.dev\.vars",
    r"(^|/)\.secrets/",
    r"\.(jks|keystore|p12|pem|key)$",
    r"(^|/)google-services\.json$",
    r"service[-_]?account.*\.json$",
    r"\.litertlm$|\.tflite$|\.task$",
]
MANIFEST_FIELDS = {"modelId", "artifactUrl", "sha256", "byteLength", "licenseUrl", "runtimeVersion", "contextTokens", "inputLimit", "outputLimit", "tokenizerDigest"}


class Report:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.notes: list[str] = []

    def check(self, ok: bool, message: str) -> bool:
        (self.notes if ok else self.failures).append(("ok   " if ok else "FAIL ") + message)
        return ok


def run(cmd: list[str], cwd: Path = ROOT) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def strip_jsonc(text: str) -> str:
    """Removes // comments outside strings, enough for wrangler.jsonc."""
    out, in_string, i = [], False, 0
    while i < len(text):
        c = text[i]
        if in_string:
            out.append(c)
            if c == "\\":
                out.append(text[i + 1])
                i += 1
            elif c == '"':
                in_string = False
        elif c == '"':
            in_string = True
            out.append(c)
        elif text.startswith("//", i):
            while i < len(text) and text[i] != "\n":
                i += 1
            continue
        else:
            out.append(c)
        i += 1
    return re.sub(r",(\s*[}\]])", r"\1", "".join(out))


def latest_migration(folder: Path) -> int:
    return max(int(p.name[:4]) for p in folder.glob("[0-9][0-9][0-9][0-9]_*.sql"))


def releases() -> list[dict]:
    return json.loads(RELEASES.read_text()) if RELEASES.exists() else []


def check(args: argparse.Namespace) -> int:
    report = Report()

    status = run(["git", "status", "--porcelain"])
    report.check(status.returncode == 0 and status.stdout.strip() == "", "working tree is clean (the manifest names one exact commit)")
    sha = run(["git", "rev-parse", "HEAD"]).stdout.strip()

    tracked = run(["git", "ls-files"]).stdout.splitlines()
    leaked = [f for f in tracked if any(re.search(p, f) for p in SECRET_PATTERNS)]
    report.check(not leaked, "no keystores, secret files, Firebase credentials or model bytes are tracked" + (f": {leaked}" if leaked else ""))

    previous = max((r["versionCode"] for r in releases()), default=0)
    report.check(args.version_code > previous, f"versionCode {args.version_code} is above the last recorded release ({previous})")

    aab = Path(args.aab)
    signer = None
    if report.check(aab.is_file(), f"bundle exists: {aab}"):
        verify = run(["jarsigner", "-verify", str(aab)])
        report.check(verify.returncode == 0 and "jar verified" in verify.stdout, "bundle is signed (jarsigner -verify)")
        cert = run(["keytool", "-printcert", "-jarfile", str(aab)])
        match = re.search(r"SHA256:\s*([0-9A-F:]{95})", cert.stdout)
        signer = match.group(1) if match else None
        report.check(signer is not None, "upload certificate fingerprint readable")
        report.check("Android Debug" not in cert.stdout, "bundle is not signed with a debug certificate")

    wrangler = json.loads(strip_jsonc((ROOT / "backend" / "wrangler.jsonc").read_text()))
    target = wrangler.get("env", {}).get(args.env)
    if report.check(target is not None, f"backend environment '{args.env}' is configured"):
        ids = [d["database_id"] for d in target.get("d1_databases", [])]
        report.check(bool(ids) and all(re.fullmatch(r"[0-9a-f-]{36}", i) for i in ids), "its D1 bindings are provisioned IDs, not local identifiers")
        buckets = [b["bucket_name"] for b in target.get("r2_buckets", [])] + [d["database_name"] for d in target.get("d1_databases", [])]
        report.check(all(args.env in name for name in buckets), f"every bucket and database name belongs to '{args.env}'")
        origins = target.get("vars", {}).get("EXPECTED_ORIGINS", "")
        has_android = "android:apk-key-hash:" in origins
        report.check(has_android or args.allow_missing_android_origin, "passkey origins include the Play signing android:apk-key-hash" if has_android else "passkey origins lack the android:apk-key-hash (pass --allow-missing-android-origin only before Play signing exists)")

    contract = (ROOT / "backend" / "src" / "contracts.ts").read_text()
    control = latest_migration(ROOT / "backend" / "migrations" / "control")
    archive = latest_migration(ROOT / "backend" / "migrations" / "archive")
    report.check(f"controlMigration: {control}," in contract and f"archiveMigration: {archive}," in contract, f"release contract names migrations {control}/{archive}")

    model_hash = None
    if args.model_manifest:
        text = Path(args.model_manifest).read_text()
        manifest = json.loads(text)
        report.check(set(manifest) == MANIFEST_FIELDS, "model manifest has exactly the documented fields")
        report.check(bool(re.fullmatch(r"[0-9a-f]{64}", str(manifest.get("sha256", "")))) and int(manifest.get("byteLength", 0)) > 0, "model manifest pins a SHA-256 and size")
        report.check(manifest.get("inputLimit", 0) + manifest.get("outputLimit", 0) <= manifest.get("contextTokens", 0), "model limits fit the context")
        model_hash = hashlib.sha256(text.encode()).hexdigest()
    else:
        report.notes.append("note no model manifest: local inference stays inactive in this build")

    for line in report.notes + report.failures:
        print(line)
    if report.failures:
        print(f"\npreflight failed: {len(report.failures)} check(s)", file=sys.stderr)
        return 1

    release = {
        "versionCode": args.version_code,
        "gitSha": sha,
        "aabSha256": sha256_file(aab),
        "uploadCertSha256": signer,
        "backendEnv": args.env,
        "controlMigration": control,
        "archiveMigration": archive,
        "modelManifestFileSha256": model_hash,
    }
    text = json.dumps(release, indent=2)
    if args.out:
        Path(args.out).write_text(text + "\n")
    print("\n" + text)
    print("\nRemaining gates are evidence only you can record: Play internal-track install on the S24+, passkey and push on that build, and the recovery drill (ops/recovery-drill.md).")
    return 0


def record(args: argparse.Namespace) -> int:
    release = json.loads(Path(args.manifest).read_text())
    history = releases()
    if any(r["versionCode"] >= release["versionCode"] for r in history):
        print("refusing: versionCode is not above every recorded release", file=sys.stderr)
        return 1
    history.append(release)
    RELEASES.write_text(json.dumps(history, indent=2) + "\n")
    print(f"recorded versionCode {release['versionCode']} in {RELEASES.relative_to(ROOT)}")
    return 0


def assetlinks(args: argparse.Namespace) -> int:
    fingerprints = [f.upper() for f in args.sha256]
    if not all(re.fullmatch(r"([0-9A-F]{2}:){31}[0-9A-F]{2}", f) for f in fingerprints):
        print("each --sha256 must be a colon-separated SHA-256 certificate fingerprint", file=sys.stderr)
        return 1
    statement = [
        {
            "relation": ["delegate_permission/common.handle_all_urls", "delegate_permission/common.get_login_creds"],
            "target": {"namespace": "android_app", "package_name": package, "sha256_cert_fingerprints": fingerprints},
        }
        for package in args.package
    ]
    print(json.dumps(statement, indent=2))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser("check", help="check a built bundle and print its release manifest")
    c.add_argument("--aab", required=True)
    c.add_argument("--version-code", type=int, required=True)
    c.add_argument("--env", required=True, help="backend environment in backend/wrangler.jsonc, e.g. staging")
    c.add_argument("--model-manifest")
    c.add_argument("--out")
    c.add_argument("--allow-missing-android-origin", action="store_true")
    c.set_defaults(func=check)
    r = sub.add_parser("record", help="append an uploaded release to ops/releases.json")
    r.add_argument("--manifest", required=True)
    r.set_defaults(func=record)
    a = sub.add_parser("assetlinks", help="print Digital Asset Links JSON for the passkey domain")
    a.add_argument("--package", action="append", required=True)
    a.add_argument("--sha256", action="append", required=True, help="Play app-signing certificate fingerprint")
    a.set_defaults(func=assetlinks)
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
