#!/usr/bin/env python3
# GuardXposedProvider provider-parity gate.
#
# Parses either a merged AndroidManifest.xml (--merged-xml) or the line output
# of `aapt2 dump xmltree <apk> --file AndroidManifest.xml` (--aapt2-xmltree)
# and asserts the packaging contract for the app-owned GuardXposedProvider:
#
#   * exactly one <provider> element whose android:name ends with
#     "GuardXposedProvider" AND whose android:authorities EXACTLY equals the
#     expected --authority (name and authority must live on the SAME element —
#     counting occurrences anywhere in the file is how the old grep gate was
#     bypassed by an <activity> plus an unrelated <provider>);
#   * that element pins android:exported="true" (string compare);
#   * that element carries NO android:permission attribute;
#   * zero elements of ANY tag whose android:name contains "XposedProvider"
#     other than the pinned GuardXposedProvider itself (covers the libxposed
#     service io.github.libxposed.service.XposedProvider);
#   * zero GuardXposedProvider-named elements that are NOT a <provider>
#     (wrong-element name occurrences fail closed);
#   * total <provider> elements whose name ends with GuardXposedProvider is 1
#     (explicit form of the first rule).
#
# ShizukuProvider is exempt by design (not this script's concern) and is never
# asserted on.
#
# Exit 0 prints one line "PASS: <mode> (<authority>)". Exit 1 prints one
# "::error::<reason>" line per failed assertion. Any unexpected internal
# error still exits non-zero (fail closed); parse errors report ::error::
# explicitly.
#
# --authority is an EXACT string compare against android:authorities (Android
# uses ';' as the separator for multi-authority providers; today this app's
# GuardXposedProvider pins a single authority — pass the full string if that
# ever changes).
import argparse
import re
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"

E_RE = re.compile(r"^(\s*)(?:\d+\s*)?E: ([A-Za-z0-9_.\-]+)")
A_RE = re.compile(
    r"^\s*A: http://schemas\.android\.com/apk/res/android:"
    r"([A-Za-z_][A-Za-z0-9_]*)\(0x[0-9a-fA-F]+\)=(.+)$"
)


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
    for el in tree.getroot().iter():
        # Framework elements in a merged manifest are UNnamespaced. A tag
        # carrying any namespace (evil:provider) must never satisfy the
        # contract, so it matches nothing (fail closed).
        tag = el.tag
        if not isinstance(tag, str) or "}" in tag:
            tag = ""
        # Only attributes in the android namespace are honored; bare or
        # tools: attributes must NOT satisfy (or shadow) the android contract.
        attrs = {}
        for key, value in el.attrib.items():
            if not key.startswith("{"):
                continue
            ns, _, ln = key[1:].partition("}")
            if ns == ANDROID_NS:
                attrs[ln] = value
        elements.append({"tag": tag, "attrs": attrs})
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
            element = {"tag": match_e.group(2), "attrs": {}, "line": lineno}
            while stack and stack[-1][0] >= indent:
                stack.pop()
            elements.append(element)
            stack.append((indent, element))
            continue
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


def check_elements(elements, authority):
    errors = []

    providers = [e for e in elements if e["tag"] == "provider"]
    guard_providers = [p for p in providers if element_name(p).endswith("GuardXposedProvider")]

    # Explicit node count: exactly one GuardXposedProvider <provider>.
    if len(guard_providers) != 1:
        errors.append(
            f"expected exactly 1 <provider> whose name ends with GuardXposedProvider, "
            f"found {len(guard_providers)}"
        )

    # Name AND authority on the SAME element, exactly once.
    matching = [p for p in guard_providers if p["attrs"].get("authorities") == authority]
    if len(matching) != 1:
        present = ", ".join(sorted({p["attrs"].get("authorities", "<absent>") for p in guard_providers})) or "none"
        errors.append(
            f"expected exactly 1 GuardXposedProvider <provider> whose authorities exactly equal "
            f"'{authority}', found {len(matching)} (GuardXposedProvider provider authorities present: {present})"
        )

    # exported=true (string compare) on the pinned element.
    target = matching[0] if len(matching) == 1 else (guard_providers[0] if guard_providers else None)
    if target is not None:
        exported = target["attrs"].get("exported")
        if exported != "true":
            if exported is None:
                errors.append(
                    'GuardXposedProvider <provider> is missing android:exported (must be "true")'
                )
            else:
                errors.append(
                    f'GuardXposedProvider <provider> android:exported must be "true", found {exported!r}'
                )
        # android:permission must be ABSENT (decision C: no permission pinned).
        permission = target["attrs"].get("permission")
        if permission is not None:
            errors.append(
                f"GuardXposedProvider <provider> must NOT carry android:permission "
                f"(pinned: absent), found '{permission}'"
            )

    # Zero libxposed (or any other) XposedProvider references anywhere.
    for element in elements:
        name = element_name(element)
        if name and "xposedprovider" in name.lower() and not name.lower().endswith("guardxposedprovider"):
            errors.append(
                f"<{element['tag']}> android:name contains XposedProvider "
                f"(libxposed service provider must not ship): '{name}'"
            )

    # Zero GuardXposedProvider-named elements that are NOT a <provider>.
    for element in elements:
        name = element_name(element)
        if name.endswith("GuardXposedProvider") and element["tag"] != "provider":
            errors.append(
                f"GuardXposedProvider-named element <{element['tag']}> is not a <provider> "
                f"(name=\"{name}\")"
            )

    return errors


def main():
    parser = argparse.ArgumentParser(
        description="Assert the GuardXposedProvider packaged-manifest contract.",
    )
    mode_group = parser.add_mutually_exclusive_group(required=True)
    mode_group.add_argument("--merged-xml", metavar="FILE",
                            help="merged AndroidManifest.xml to check")
    mode_group.add_argument("--aapt2-xmltree", metavar="FILE_OR_-",
                            help="`aapt2 dump xmltree` output to check ('-' for stdin)")
    parser.add_argument("--authority", required=True,
                        help="expected android:authorities value (exactly equal)")
    args = parser.parse_args()

    try:
        if args.merged_xml is not None:
            mode = "merged-xml"
            elements = parse_merged_xml(args.merged_xml)
        else:
            mode = "aapt2-xmltree"
            elements = parse_xmltree_file(args.aapt2_xmltree)

        errors = check_elements(elements, args.authority)
    except SystemExit:
        raise
    except Exception as exc:  # pragma: no cover - belt and braces
        print(f"::error::internal: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(f"::error::{error}")
        return 1
    print(f"PASS: {mode} ({args.authority})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
