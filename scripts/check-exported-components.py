#!/usr/bin/env python3
# Exported-components allowlist inventory gate.
#
# Parses either a merged AndroidManifest.xml (--merged-xml) or the line output
# of `aapt2 dump xmltree <apk> --file AndroidManifest.xml` (--aapt2-xmltree)
# and enforces an ALLOWLIST of exactly which components the app may export.
#
#   * anything exported that is NOT one of the allowlisted (kind, name) pairs
#     fails, loudly — this is the gate's teeth: a hostile or compromised PR
#     cannot ship an additional exported <provider>, <service>, <receiver>,
#     <activity> or <activity-alias> and pass the manifest gates;
#   * each allowlisted component must carry the expected shape pinned below
#     (permission present/absent, exported exactly "true");
#   * NO <application> element may carry android:permission (a component-level
#     default inherited by EVERY component);
#   * NO <provider> outside ROOT <queries> may carry android:grantUriPermissions
#     (none today; URI-grant egress from an exported provider is a data footgun);
#     likewise an EXPORTED <provider> may never carry a <path-permission>
#     child — path-scoped URI grants are the same egress class, rejected
#     for the same reason (with or without android:permission on the child);
#   * merged manifests must be EXPLICIT: a component with an <intent-filter>
#     child and no android:exported fails (implicit-export is how accidental
#     exports happen; the merged manifest must pin the value);
#   * android:readPermission / android:writePermission are provider-only
#     attributes; a non-provider carrying either fails closed.
#
# What this gate CANNOT do — read this before weakening it:
#
#   This is an INVENTORY gate, not a logic gate. It checks WHICH components a
#   merged manifest exports and how they are pinned (kind/name/exported/
#   enabled/permission). It CANNOT detect weakening of component LOGIC —
#   e.g. GuardXposedProvider.kt dropping its UID gate, ShizukuProvider
#   changing its authorization story, or a permission check removed from an
#   allowlisted component's code path. Logic weakening of GuardXposedProvider
#   remains with human review + CODEOWNERS on GuardXposedProvider.kt, and the
#   provider-parity gate (scripts/check-provider-parity.py) for that one
#   provider's manifest contract. A weakened GuardXposedProvider.kt PASSES
#   this gate.
#
#   It also does NOT validate android:authorities (the parity gate owns
#   authorities), does NOT require any allowlisted component to be present
#   (the parity gate owns GuardXposedProvider's presence; losing MainActivity
#   fails other gates), and does NOT treat enabled="false" or
#   exported="false" as exported — inert components are ignored entirely.
#   It does not scan DEX, resources, or code.
#
# ROOT-level <queries> blocks (children of <manifest>) declare package
# visibility, not exported components: elements nested under a ROOT
# <queries> are counted as non-exported and are NOT shape-checked (like the
# parity gate's <queries> exemption, narrowed to ROOT-level — a root
# <queries> block can never ship an exported surface). A <queries> element
# nested anywhere else
# — e.g. inside <application> — is NOT exempt; elements inside it are
# enumerated normally (exported ⇒ must satisfy the allowlist like any
# other element).
#
# Parse discipline mirrors the parity checker (fail closed): namespace-
# qualified prefixed element tokens die; in aapt2-xmltree mode an android
# attribute line containing the canonical http://schemas.android.com/apk/res/android
# URI that fails the attribute regex dies; other attribute lines (no android
# namespace marker) are ignored — unchanged from the parity gate's discipline;
# an attribute line before any element dies; a parsed-empty dump dies.
# Merged-xml mode relies on the XML parser, but namespace-qualified elements
# (evil:provider) die before they can satisfy anything.
# (analyze-time errors accumulate all offenders; fail-closed die paths exit
# on the first — a crafted dump aborts early, no secondary analysis)
#
# Updating the allowlist: adding an exported component to the shipped app is
# a deliberate one-list change in this file, reviewed like the source itself.
# Edit the ALLOWLIST literal below (one list, one file — deliberately no
# separate JSON file, to keep the review surface minimal), update the comment
# block that documents it, extend the fixtures
# (scripts/fixtures/exported-components/), and route the change through
# CODEOWNERS review of this file.
#
# Exit 0 prints one line "PASS: exported components limited to the allowlist
# (N exported)". Exit 1 prints one "::error::<reason>" line per failed
# assertion (analyze-time errors list ALL offenders; fail-closed die paths
# exit on the first). Any unexpected internal error still exits
# non-zero (fail closed); parse errors report ::error:: explicitly.
import argparse
import re
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"

E_RE = re.compile(r"^(\s*)(?:\d+\s*)?E: ([^\s()]+)")
ELEMENT_TOKEN_RE = re.compile(r"[A-Za-z0-9_.\-]+")
A_RE = re.compile(
    r"^\s*A: http://schemas\.android\.com/apk/res/android:"
    r"([A-Za-z_][A-Za-z0-9_]*)\(0x[0-9a-fA-F]+\)=(.+)$"
)

COMPONENT_KINDS = ("activity", "activity-alias", "service", "receiver", "provider")

# ---------------------------------------------------------------------------
# EXPORTED-COMPONENTS ALLOWLIST — the complete set of components this app
# ships exported, verified against the merged DEBUG and merged RELEASE
# manifests (:app:processDebugMainManifest / :app:processReleaseMainManifest).
# One list, one file; deliberately no separate JSON so the diff of an
# allowlist change is small and reviewable in review.
#
# Component names must be merged-manifest ABSOLUTE (flat) fully-qualified
# names — a relative name (".MainActivity") never matches and fails closed.
#
#   1. activity  com.galaxydual.plus.MainActivity             exported, permission ABSENT
#      (launcher entry point; exported so the launcher can reach it)
#   2. receiver  androidx.profileinstaller.ProfileInstallReceiver
#      exported, android:permission="android.permission.DUMP"
#      (androidx.profileinstaller; writes the cloud profile via the
#      DUMP-protected install-profile broadcast)
#   3. provider  rikka.shizuku.ShizukuProvider                 exported,
#      android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"
#      (Shizuku API V2 binder provider; authority <applicationId>.shizuku)
#   4. provider  com.galaxydual.plus.GuardXposedProvider       exported, permission ABSENT
#      (app-owned LSPosed policy provider; authority <applicationId>.XposedService;
#      the UID gate lives in GuardXposedProvider.kt — NOT this gate)
#
# Anything outside this list that is exported is, by definition, unexpected
# new attack surface and fails the gate.
# ---------------------------------------------------------------------------
ALLOWLIST = frozenset([
    ("activity", "com.galaxydual.plus.MainActivity"),
    ("receiver", "androidx.profileinstaller.ProfileInstallReceiver"),
    ("provider", "rikka.shizuku.ShizukuProvider"),
    ("provider", "com.galaxydual.plus.GuardXposedProvider"),
])

# Per-entry shape pins for the allowlisted components. Permission values are
# exact strings; ABSENT means the attribute must not appear at all.
MAIN_ACTIVITY = ("activity", "com.galaxydual.plus.MainActivity")
PROFILE_INSTALL_RECEIVER = ("receiver", "androidx.profileinstaller.ProfileInstallReceiver")
SHIZUKU_PROVIDER = ("provider", "rikka.shizuku.ShizukuProvider")
GUARD_PROVIDER = ("provider", "com.galaxydual.plus.GuardXposedProvider")

REQUIRED_PERMISSION = {
    PROFILE_INSTALL_RECEIVER: "android.permission.DUMP",
    SHIZUKU_PROVIDER: "android.permission.INTERACT_ACROSS_USERS_FULL",
}
FORBIDDEN_PERMISSION_ATTRS = {
    MAIN_ACTIVITY: ("permission", "readPermission", "writePermission"),
    GUARD_PROVIDER: ("permission", "readPermission", "writePermission"),
    PROFILE_INSTALL_RECEIVER: ("readPermission", "writePermission"),
    SHIZUKU_PROVIDER: ("readPermission", "writePermission"),
}


def nearest_queries_parent_local(local_names):
    """Local name of the parent of the NEAREST <queries> element at-or-above
    the innermost position, or None if no <queries> is present.

    `local_names` is the enclosing stack's local names with the current
    element's own local appended innermost (so for a <queries> element the
    nearest queries at-or-above it is itself). An element is a visibility
    declaration — and therefore exempt from enumeration — iff this returns
    "manifest", i.e. only under a ROOT-level <queries> (a <manifest> child).
    A <queries> nested anywhere else (e.g. inside <application>) exempts
    nothing; its elements are enumerated normally."""
    for idx in range(len(local_names) - 1, -1, -1):
        if local_names[idx] == "queries":
            return local_names[idx - 1] if idx >= 1 else None
    return None


def die(reason):
    print(f"::error::{reason}", file=sys.stderr)
    sys.exit(1)


def parse_merged_xml(path):
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        die(f"merged-xml parse error in {path}: {exc}")
    except OSError as exc:
        die(f"merged-xml unreadable file {path}: {exc}")
    elements = []

    def walk(el, stack):
        # Framework elements in a merged manifest are UNnamespaced. A tag
        # carrying any namespace (evil:provider) must never satisfy the
        # contract; die fail-closed (mirroring aapt2-xmltree mode, which dies
        # on namespace-qualified element tokens). Non-string tags (comments,
        # PIs) iterate as non-str and are ignored below.
        raw_tag = el.tag
        if isinstance(raw_tag, str):
            if "}" in raw_tag:
                die(f"{path}: namespace-qualified element {raw_tag} (refusing to parse)")
            tag = raw_tag
            local = raw_tag
            if ":" in local:
                local = local.rsplit(":", 1)[-1]
            # Only attributes in the android namespace are honored; bare or
            # tools: attributes must NOT satisfy (or shadow) the android contract.
            attrs = {}
            for key, value in el.attrib.items():
                if not key.startswith("{"):
                    continue
                ns, _, ln = key[1:].partition("}")
                if ns == ANDROID_NS:
                    attrs[ln] = value
            element = {"tag": tag, "local": local, "attrs": attrs,
                       "in_queries": nearest_queries_parent_local(
                           [e["local"] for e in stack] + [local]) == "manifest",
                       "has_intent_filter": False,
                       "parent_ref": stack[-1] if stack else None}
            # An <intent-filter> child belongs to the nearest enclosing
            # component; it flips the "must be explicit about exported" rule.
            if local == "intent-filter" and stack and stack[-1]["local"] in COMPONENT_KINDS:
                stack[-1]["has_intent_filter"] = True
            elements.append(element)
            stack.append(element)
            for child in el:
                walk(child, stack)
            stack.pop()
        else:
            for child in el:
                walk(child, stack)

    walk(tree.getroot(), [])
    if not elements:
        die(f"merged-xml parse produced no elements: {path}")
    return elements


def parse_value(rest):
    """Value part of an aapt2 attribute dump line, after the '='.

    Quoted form: "...value..." (Raw: "...value...") — take up to the closing
    quote (the value itself never contains a double quote). Unquoted form:
    the token before any whitespace (e.g. true, 34, 0x00000002, @0x7f080000),
    which also discards an optional ` (Raw: ...)` suffix.
    """
    rest = rest.strip()
    if rest.startswith('"'):
        end = rest.find('"', 1)
        if end == -1:
            return rest[1:]
        return rest[1:end]
    return rest.split(None, 1)[0]


def parse_xmltree_text(text, source):
    """aapt2 xmltree is perfectly nested: attributes always belong to the
    nearest preceding element (top of the element stack). On an `E:` line, all
    stack entries at the same or deeper indent are closed (siblings replace
    the open element at that level) and the new element is pushed."""
    elements = []
    stack = []  # list of (indent, element dict)
    for lineno, raw in enumerate(text.splitlines(), start=1):
        match_e = E_RE.match(raw)
        if match_e:
            indent = len(match_e.group(1))
            token = match_e.group(2)
            # Fail closed on a namespace-qualified element token (e.g.
            # `E: urn:evil:provider`): legitimate manifests never carry
            # namespaced elements in aapt2 output (tools: metadata is
            # stripped by the merger before packaging). Die BEFORE the token
            # could be silently truncated or mistaken for a framework element.
            if ":" in token:
                die(f"{source}:{lineno}: namespace-qualified element token '{token}' (refusing to parse)")
            if not ELEMENT_TOKEN_RE.fullmatch(token):
                die(f"{source}:{lineno}: unrecognized element token '{token}' (refusing to parse)")
            element = {"tag": token, "local": token, "attrs": {}, "line": lineno,
                       "has_intent_filter": False}
            while stack and stack[-1][0] >= indent:
                stack.pop()
            # Ancestor chain = remaining stack after closing siblings/deeper
            # levels. Visibility declarations live ONLY under a ROOT-level
            # <queries> (a <manifest> child): such elements are flagged here
            # and skipped by the inventory rules downstream. A <queries>
            # nested anywhere else exempts nothing.
            element["in_queries"] = nearest_queries_parent_local(
                [parent["local"] for _, parent in stack] + [token]) == "manifest"
            element["parent_ref"] = stack[-1][1] if stack else None
            # An <intent-filter> element belongs to the nearest enclosing
            # component; it flips the "must be explicit about exported" rule.
            if token == "intent-filter" and stack and stack[-1][1]["local"] in COMPONENT_KINDS:
                stack[-1][1]["has_intent_filter"] = True
            elements.append(element)
            stack.append((indent, element))
            continue
        if re.match(r"^\s*(?:\d+\s*)?E:", raw):
            die(f"{source}:{lineno}: unparseable element line: {raw.strip()}")
        match_a = A_RE.match(raw)
        if match_a is None and raw.lstrip().startswith("A:"):
            if "schemas.android.com/apk/res/android" in raw:
                die(f"{source}:{lineno}: unparseable android attribute line: {raw.strip()}")
        if match_a:
            if not stack:
                die(f"{source}:{lineno}: attribute line before any element: {raw.strip()}")
            name, value = match_a.group(1), parse_value(match_a.group(2))
            stack[-1][1]["attrs"][name] = value
    if not elements:
        die(f"{source}: no elements parsed (empty or unrecognized xmltree dump)")
    return elements


def parse_xmltree_file(path):
    if path == "-":
        return parse_xmltree_text(sys.stdin.read(), "<stdin>")
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except OSError as exc:
        die(f"aapt2-xmltree unreadable file {path}: {exc}")
    return parse_xmltree_text(text, path)


def element_name(element):
    return element["attrs"].get("name", "")


def analyze(elements):
    """Return (errors, exported_components). ALL offenders are reported."""
    errors = []

    # An <application>-level android:permission is inherited by EVERY component
    # of the app; it pins nothing yet restricts all. The parity gate rejects it
    # too; rejected here as well (cheap).
    for element in elements:
        if element.get("local", element["tag"]) != "application":
            continue
        permission = element["attrs"].get("permission")
        if permission is not None:
            errors.append(
                f"<application> android:permission is inherited by all components "
                f"(pinned: absent), found '{permission}'"
            )

    # grantUriPermissions on ANY provider outside ROOT <queries> is rejected (none
    # today): a URI grant on an exported provider is a data-egress footgun
    # this app never pins. Root-queries-nested providers are visibility
    # declarations; skipped.
    for element in elements:
        if element["tag"] != "provider" or element.get("in_queries"):
            continue
        grant = element["attrs"].get("grantUriPermissions")
        if grant is not None:
            errors.append(
                f"<provider> '{element_name(element) or '<absent>'}' carries "
                f"android:grantUriPermissions (pinned: absent), found '{grant}'"
            )

    # readPermission/writePermission are provider-only. A non-provider
    # carrying either fails closed (merged-manifest config drift).
    for element in elements:
        if element.get("in_queries"):
            continue
        if element["tag"] not in COMPONENT_KINDS:
            continue
        if element["tag"] == "provider":
            continue
        for attr in ("readPermission", "writePermission"):
            value = element["attrs"].get(attr)
            if value is not None:
                errors.append(
                    f"<{element['tag']}> '{element_name(element) or '<absent>'}' "
                    f"carries android:{attr} (only valid on a <provider>), found '{value}'"
                )

    exported = []
    for element in elements:
        if element["tag"] not in COMPONENT_KINDS:
            continue
        if element.get("in_queries"):
            # A ROOT <queries> declares package visibility, never an exported
            # surface. Not counted, not shape-checked.
            continue
        attrs = element["attrs"]
        kind, name = element["tag"], element_name(element)
        label = f"<{kind}> '{name or '<absent>'}'"

        enabled = attrs.get("enabled")
        if enabled is not None and enabled not in ("true", "false"):
            errors.append(
                f"{label} android:enabled must be \"true\" or \"false\" when present, "
                f"found '{enabled}'"
            )
            continue
        if enabled == "false":
            # enabled="false" is inert: the component never registers with
            # the system and exports nothing. Ignored entirely (documented).
            continue

        exported_attr = attrs.get("exported")
        has_intent_filter = element.get("has_intent_filter", False)
        if exported_attr is None:
            if has_intent_filter:
                errors.append(
                    f"{label} has an <intent-filter> but is missing android:exported — "
                    f"merged manifests must be explicit about android:exported"
                )
            # A component without android:exported and without an intent-filter
            # is not exported (Android defaults to false pre-S, and merged
            # manifests must carry the attribute for anything with a filter).
            continue
        if exported_attr not in ("true", "false"):
            errors.append(
                f"{label} android:exported must be \"true\" or \"false\", "
                f"found '{exported_attr}'"
            )
            continue
        if exported_attr == "false":
            continue
        exported.append(element)

    # (a) The primary tripwire: anything exported that is not allowlisted.
    for element in exported:
        key = (element["tag"], element_name(element))
        if key not in ALLOWLIST:
            errors.append(
                f"exported <{element['tag']}> '{element_name(element) or '<absent>'}' "
                f"is not in the allowlist (see the ALLOWLIST literal in "
                f"scripts/check-exported-components.py)"
            )

    # (b) Shape pins for allowlisted components that ARE exported here.
    for element in exported:
        key = (element["tag"], element_name(element))
        if key not in ALLOWLIST:
            continue
        attrs = element["attrs"]
        name = element_name(element)
        label = f"<{element['tag']}> '{name}'"
        required = REQUIRED_PERMISSION.get(key)
        if required is not None:
            permission = attrs.get("permission")
            if permission is None:
                errors.append(
                    f"{label} is missing android:permission (expected '{required}')"
                )
            elif permission != required:
                errors.append(
                    f"{label} android:permission is '{permission}', expected '{required}'"
                )
        for attr in FORBIDDEN_PERMISSION_ATTRS.get(key, ()):
            value = attrs.get(attr)
            if value is not None:
                errors.append(
                    f"{label} must NOT carry android:{attr} (pinned: absent), found '{value}'"
                )

    # (c) <path-permission> under an EXPORTED provider grants path-scoped URI
    # access; an exported provider carrying ANY <path-permission> child (with
    # or without android:permission on the child) is the same data-egress
    # class as grantUriPermissions and fails closed. Inert providers
    # (enabled="false" / exported="false") never register, so they are not
    # checked here. Root-queries-nested providers are not enumerated as
    # exported, so their subtrees are skipped too.
    exported_providers = {id(element): element for element in exported
                          if element["tag"] == "provider"}
    for element in elements:
        if element["tag"] != "path-permission":
            continue
        parent = element.get("parent_ref")
        if parent is not None and id(parent) in exported_providers:
            provider_name = element_name(parent) or "<absent>"
            errors.append(
                f"<provider> '{provider_name}' carries <path-permission> "
                f"(pinned: none) — an exported provider must not scope "
                f"path-level URI grants"
            )

    return errors, exported


def main():
    parser = argparse.ArgumentParser(
        description="Assert the merged manifest exports only allowlisted components.",
    )
    mode_group = parser.add_mutually_exclusive_group(required=True)
    mode_group.add_argument("--merged-xml", metavar="FILE",
                            help="merged AndroidManifest.xml to check")
    mode_group.add_argument("--aapt2-xmltree", metavar="FILE_OR_-",
                            help="`aapt2 dump xmltree` output to check ('-' for stdin)")
    args = parser.parse_args()

    try:
        if args.merged_xml is not None:
            mode = "merged-xml"
            elements = parse_merged_xml(args.merged_xml)
        else:
            mode = "aapt2-xmltree"
            elements = parse_xmltree_file(args.aapt2_xmltree)

        errors, exported = analyze(elements)
    except SystemExit:
        raise
    except Exception as exc:  # pragma: no cover - belt and braces
        print(f"::error::internal: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(f"::error::{error}")
        return 1
    print(f"PASS: exported components limited to the allowlist ({len(exported)} exported)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
