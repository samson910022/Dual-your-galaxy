#!/usr/bin/env python3
# ShizukuProvider element-scoped manifest gate.
#
# Parses the line output of `aapt2 dump xmltree <apk> --file AndroidManifest.xml`
# (--aapt2-xmltree) and asserts the packaging contract for the ShizukuProvider
# on the PACKAGED manifest:
#
#   android:name EXACTLY "rikka.shizuku.ShizukuProvider"
#   android:authorities EXACTLY the --authority passed by the caller
#     (derived from the APK's ACTUAL applicationId via `aapt2 dump packagename`
#     by the caller — never from the filename and never re-derived here;
#     debug: com.galaxydual.plus.debug.shizuku; release:
#     com.galaxydual.plus.shizuku; the mapping lives in the caller)
#   android:exported="true"
#   android:multiprocess="false"
#   android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"
#
# all five on the SAME element. Parsing discipline matches
# scripts/check-provider-parity.py (an `E:` line sets the current element;
# `A: .../android:<name>(0x<hex>)=<value>` lines set fields on it). An
# unanchored substring match would also admit the class name inside an
# unrelated attribute value, and attribute lines alone say nothing about
# WHICH element carries them. Fails closed (::error:: + exit 1) on: a missing
# provider, a wrong authority/exported/multiprocess/permission, or any parse
# oddity. Division of labor: this gate pins the ShizukuProvider
# element-scoped (name+authority+exported+multiprocess+permission) on the
# packaged manifest; scripts/check-provider-parity.py pins the
# GuardXposedProvider (name+authority parity); the caller pins the
# ShizukuProvider class in the DEX. ShizukuProvider is not a trust boundary
# for GalaxyExecService.
#
# Extracted verbatim (module-level die() semantics and messages unchanged)
# from the inline python3 block that used to live in
# scripts/verify-release-artifacts.sh step 1; only the CLI plumbing
# (--aapt2-xmltree/--authority argparse, --authority instead of a positional
# argument) differs. The --authority value must be derived from the APK
# itself (aapt2 dump packagename), never from hardcoded variant logic in a
# caller — an APK whose package is neither com.galaxydual.plus.debug nor
# com.galaxydual.plus is rejected by the caller before this script ever
# runs.
#
# Exit 0 prints one line "FOUND ShizukuProvider in packaged manifest ...".
# Exit 1 prints one "::error::<reason>" line. Any unexpected internal error
# still exits non-zero (fail closed); parse errors report ::error::
# explicitly.
import argparse
import re
import sys

EXPECTED_PROVIDER = "rikka.shizuku.ShizukuProvider"
EXPECTED_PERMISSION = "android.permission.INTERACT_ACROSS_USERS_FULL"
E_RE = re.compile(r"^(\s*)(?:\d+\s*)?E: ([^\s()]+)")
ELEMENT_TOKEN_RE = re.compile(r"[A-Za-z0-9_.\-]+")
A_RE = re.compile(
    r"^\s*A: http://schemas\.android\.com/apk/res/android:"
    r"([A-Za-z_][A-Za-z0-9_]*)\(0x[0-9a-fA-F]+\)=(.+)$"
)


def die(reason):
    print(f"::error::{reason}", file=sys.stderr)
    sys.exit(1)


def parse_value(rest):
    # Quoted form: "...value..." (Raw: "...value...") — take up to the closing
    # quote. Unquoted form: the token before any whitespace (e.g. true/false),
    # which also discards an optional ` (Raw: ...)` suffix. Same as the parity
    # checker's parse_value.
    rest = rest.strip()
    if rest.startswith('"'):
        end = rest.find('"', 1)
        if end == -1:
            return rest[1:]
        return rest[1:end]
    return rest.split(None, 1)[0]


def main():
    parser = argparse.ArgumentParser(
        description="element-scoped ShizukuProvider manifest gate (packaged manifest)",
    )
    parser.add_argument("--aapt2-xmltree", required=True, metavar="FILE",
                        help="line output of `aapt2 dump xmltree <apk> --file AndroidManifest.xml`")
    parser.add_argument("--authority", required=True, metavar="AUTHORITY",
                        help="expected android:authorities (derived by the caller from the packaged applicationId)")
    args = parser.parse_args()

    path, expected_authority = args.aapt2_xmltree, args.authority

    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except OSError as exc:
        die(f"aapt2-xmltree unreadable file {path}: {exc}")

    # aapt2 xmltree is perfectly nested: attributes always belong to the nearest
    # preceding element (same stack discipline as the parity checker).
    elements = []
    stack = []
    for lineno, raw in enumerate(text.splitlines(), start=1):
        match_e = E_RE.match(raw)
        if match_e:
            indent = len(match_e.group(1))
            token = match_e.group(2)
            if ":" in token:
                die(f"{path}:{lineno}: namespace-qualified element token '{token}' (refusing to parse)")
            if not ELEMENT_TOKEN_RE.fullmatch(token):
                die(f"{path}:{lineno}: unrecognized element token '{token}' (refusing to parse)")
            element = {"tag": token, "attrs": {}, "line": lineno}
            while stack and stack[-1][0] >= indent:
                stack.pop()
            elements.append(element)
            stack.append((indent, element))
            continue
        if re.match(r"^\s*(?:\d+\s*)?E:", raw):
            die(f"{path}:{lineno}: unparseable element line: {raw.strip()}")
        match_a = A_RE.match(raw)
        if match_a is None and raw.lstrip().startswith("A:"):
            if "schemas.android.com/apk/res/android" in raw:
                die(f"{path}:{lineno}: unparseable android attribute line: {raw.strip()}")
        if match_a:
            if not stack:
                die(f"{path}:{lineno}: attribute line before any element: {raw.strip()}")
            name, value = match_a.group(1), parse_value(match_a.group(2))
            stack[-1][1]["attrs"][name] = value

    if not elements:
        die(f"{path}: no elements parsed (empty or unrecognized xmltree dump)")

    providers = [e for e in elements if e["tag"] == "provider"]
    pinned = [p for p in providers if p["attrs"].get("name") == EXPECTED_PROVIDER]
    if len(pinned) != 1:
        die(f"expected exactly 1 <provider> with android:name exactly "
            f"'{EXPECTED_PROVIDER}', found {len(pinned)}")

    attrs = pinned[0]["attrs"]
    authority = attrs.get("authorities")
    if authority is None:
        die(f"ShizukuProvider <provider> is missing android:authorities (expected '{expected_authority}')")
    if authority != expected_authority:
        die(f"ShizukuProvider <provider> android:authorities is '{authority}', "
            f"expected '{expected_authority}'")
    exported = attrs.get("exported")
    if exported != "true":
        die(f"ShizukuProvider <provider> android:exported must be \"true\", found {exported!r}")
    multiprocess = attrs.get("multiprocess")
    if multiprocess != "false":
        die(f"ShizukuProvider <provider> android:multiprocess must be \"false\", found {multiprocess!r}")
    permission = attrs.get("permission")
    if permission is None:
        die(f"ShizukuProvider <provider> is missing android:permission (expected '{EXPECTED_PERMISSION}')")
    if permission != EXPECTED_PERMISSION:
        die(f"ShizukuProvider <provider> android:permission is '{permission}', "
            f"expected '{EXPECTED_PERMISSION}'")
    print(f"FOUND ShizukuProvider in packaged manifest (android:authorities {authority}; "
          f"exported=true, multiprocess=false, permission={EXPECTED_PERMISSION})")


if __name__ == "__main__":
    main()
