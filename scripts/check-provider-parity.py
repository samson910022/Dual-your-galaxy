#!/usr/bin/env python3
# GuardXposedProvider provider-parity gate.
#
# Parses either a merged AndroidManifest.xml (--merged-xml) or the line output
# of `aapt2 dump xmltree <apk> --file AndroidManifest.xml` (--aapt2-xmltree)
# and asserts the packaging contract for the app-owned GuardXposedProvider:
#
#   * exactly one <provider> element whose android:name is EXACTLY
#     "com.galaxydual.plus.GuardXposedProvider" (case-sensitive) AND whose
#     android:authorities EXACTLY equals the expected --authority (name and
#     authority must live on the SAME element — counting occurrences anywhere
#     in the file is how the old grep gate was bypassed by an <activity> plus
#     an unrelated <provider>; an endswith-match likewise admits substituted
#     foreign providers such as com.example.FakeGuardXposedProvider carrying
#     the pinned authority);
#   * that element pins android:exported="true" (string compare);
#   * that element carries NO android:permission attribute;
#   * that element carries NO android:readPermission / android:writePermission
#     attributes;
#   * that element has android:enabled absent or EXACTLY "true" (string compare);
#   * NO <application> element carries android:permission (a component-level
#     default would be inherited by the provider as well);
#   * NO <provider> element other than the GuardXposedProvider itself carries
#     the --authority (foreign/impostor provider claiming the pinned authority
#     fails closed; the FQN pin alone cannot see a name that avoids the
#     GuardXposedProvider marker). android:authorities is ';'-separated (Android
#     tokenizes it), so the foreign rule compares per-TOKEN: any ';'-separated
#     token that strips to exactly --authority counts as a claim;
#   * <provider> elements nested under <queries> are NOT claimants (a <queries>
#     block declares package visibility, never claims an authority) and are
#     skipped by every rule above in both parser modes;
#   * zero elements of ANY tag whose android:name contains "XposedProvider"
#     other than the pinned GuardXposedProvider itself (covers the libxposed
#     service io.github.libxposed.service.XposedProvider);
#   * zero GuardXposedProvider-named elements that are NOT a <provider>
#     (wrong-element name occurrences fail closed);
#   * any android:name that contains "GuardXposedProvider" but is not EXACTLY
#     the pinned FQN fails closed as an impostor;
#   * total <provider> elements whose name is exactly the pinned FQN is 1
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
# --authority is matched two ways. On the pinned GuardXposedProvider element
# the comparison is an EXACT whole-string compare (Guard claims only the exact
# authority; a ';' in its value is the intentional multi-authority rejection).
# Everywhere else android:authorities is ';'-tokenized by Android, so the
# foreign-provider rule and the carrier lookup in the split-attribute arm
# compare per-TOKEN (split on ';', strip, token == --authority).
import argparse
import re
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"
EXPECTED_PROVIDER = "com.galaxydual.plus.GuardXposedProvider"

E_RE = re.compile(r"^(\s*)(?:\d+\s*)?E: ([^\s()]+)")
ELEMENT_TOKEN_RE = re.compile(r"[A-Za-z0-9_.\-]+")
A_RE = re.compile(
    r"^\s*A: http://schemas\.android\.com/apk/res/android:"
    r"([A-Za-z_][A-Za-z0-9_]*)\(0x[0-9a-fA-F]+\)=(.+)$"
)


def die(reason):
    print(f"::error::{reason}", file=sys.stderr)
    sys.exit(1)


def authority_tokens(value):
    """';'-split an android:authorities attribute into stripped tokens.

    Android tokenizes multi-authority providers on ';', so a substring compare
    would either miss a token (";com.x.Y") or false-positive on a suffix of a
    longer authority. Empty tokens are dropped (an empty segment can never
    equal a non-empty --authority).
    """
    if not value:
        return []
    return [token.strip() for token in value.split(";") if token.strip()]


def parse_merged_xml(path):
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        die(f"merged-xml parse error in {path}: {exc}")
    except OSError as exc:
        die(f"merged-xml unreadable file {path}: {exc}")
    elements = []

    def walk(el, in_queries):
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
            child_in_queries = in_queries or local == "queries"
            # Only attributes in the android namespace are honored; bare or
            # tools: attributes must NOT satisfy (or shadow) the android contract.
            attrs = {}
            for key, value in el.attrib.items():
                if not key.startswith("{"):
                    continue
                ns, _, ln = key[1:].partition("}")
                if ns == ANDROID_NS:
                    attrs[ln] = value
            elements.append({"tag": tag, "local": local, "attrs": attrs,
                             "in_queries": child_in_queries})
        else:
            child_in_queries = in_queries
        for child in el:
            walk(child, child_in_queries)

    walk(tree.getroot(), False)
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
            element = {"tag": token, "local": token, "attrs": {}, "line": lineno}
            while stack and stack[-1][0] >= indent:
                stack.pop()
            # Ancestor chain = remaining stack after closing siblings/deeper
            # levels; a <provider> under <queries> declares package visibility,
            # never claims the authority, so it is flagged here and skipped by
            # the provider rules downstream.
            element["in_queries"] = any(parent["local"] == "queries" for _, parent in stack)
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


def check_elements(elements, authority):
    errors = []

    # <provider> elements under <queries> declare package visibility only and
    # never claim the authority; they are not claimants anywhere in this gate.
    providers = [e for e in elements
                 if e["tag"] == "provider" and not e.get("in_queries")]
    pinned = [p for p in providers if element_name(p) == EXPECTED_PROVIDER]

    # Explicit node count: exactly one <provider> named exactly the pinned
    # FQN (case-sensitive). A foreign or fake provider with a similar name
    # never satisfies this rule.
    if len(pinned) != 1:
        errors.append(
            f"expected exactly 1 <provider> with android:name exactly "
            f"'{EXPECTED_PROVIDER}', found {len(pinned)}"
        )

    # Name AND authority on the SAME element, exactly once. Each failure shape
    # gets its own message so fixture pins can attribute the exact arm.
    matching = [p for p in pinned if p["attrs"].get("authorities") == authority]
    if len(matching) != 1:
        if not pinned:
            errors.append(
                f"expected exactly 1 GuardXposedProvider <provider> whose authorities exactly equal "
                f"'{authority}', found 0 (no GuardXposedProvider provider present)"
            )
        else:
            for p in pinned:
                a = p["attrs"].get("authorities")
                if a == authority:
                    continue
                carrier = next(
                    (q for q in providers
                     if q is not p and authority in authority_tokens(q["attrs"].get("authorities"))),
                    None,
                )
                if a is None:
                    errors.append(
                        f"GuardXposedProvider <provider> is missing android:authorities "
                        f"(expected '{authority}')"
                    )
                elif not a.strip():
                    errors.append(
                        f"GuardXposedProvider <provider> android:authorities is empty "
                        f"(expected '{authority}')"
                    )
                elif ";" in a:
                    errors.append(
                        f"GuardXposedProvider <provider> android:authorities carries multiple "
                        f"authorities '{a}' (expected exactly '{authority}')"
                    )
                elif carrier is not None:
                    errors.append(
                        f"GuardXposedProvider <provider> android:authorities '{a}' splits the "
                        f"expected '{authority}', which is carried by <{carrier['tag']}> "
                        f"'{element_name(carrier)}' (name and authority must live on the same element)"
                    )
                elif (".debug." in a) != (".debug." in authority):
                    errors.append(
                        f"GuardXposedProvider <provider> carries the cross-variant authority "
                        f"'{a}' (expected '{authority}')"
                    )
                else:
                    errors.append(
                        f"GuardXposedProvider <provider> android:authorities is '{a}', "
                        f"expected '{authority}'"
                    )

    # Foreign-provider rule: EVERY <provider> carrying the pinned authority
    # must BE the GuardXposedProvider. An impostor with a plain foreign name
    # would otherwise pass the FQN-based pinning above. Authorities are
    # ';'-separated by Android, so the comparison is per-TOKEN (split on ';',
    # strip): a foreign provider with authorities="x;<expected>" claims the
    # authority just as an exact match would.
    for provider in providers:
        if authority in authority_tokens(provider["attrs"].get("authorities")) and element_name(provider) != EXPECTED_PROVIDER:
            errors.append(
                f"unexpected provider '{element_name(provider) or '<absent>'}' claiming pinned "
                f"authority '{authority}' (only GuardXposedProvider may)"
            )

    # exported=true (string compare) on the pinned element.
    target = matching[0] if len(matching) == 1 else (pinned[0] if pinned else None)
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
        # android:readPermission / android:writePermission must be ABSENT too;
        # a read/write restriction is a permission split, not a permission pin.
        for attr in ("readPermission", "writePermission"):
            value = target["attrs"].get(attr)
            if value is not None:
                errors.append(
                    f"GuardXposedProvider <provider> must NOT carry android:{attr} "
                    f"(pinned: absent), found '{value}'"
                )
        # android:enabled must be ABSENT or exactly "true" (string compare);
        # a disabled provider silently voids the contract without breaking builds.
        enabled = target["attrs"].get("enabled")
        if enabled is not None and enabled != "true":
            errors.append(
                f'GuardXposedProvider <provider> android:enabled must be "true" '
                f"when present, found '{enabled}'"
            )

    # An <application>-level android:permission is inherited by EVERY component
    # of the app (including the provider); it pins nothing yet restricts all.
    # Namespace-qualified elements already died in parse_merged_xml /
    # parse_xmltree_text; the localName check stays as belt and braces.
    for element in elements:
        if element.get("local", element["tag"]) != "application":
            continue
        permission = element["attrs"].get("permission")
        if permission is not None:
            errors.append(
                f"<application> android:permission is inherited by all components "
                f"incl. GuardXposedProvider (pinned: absent), found '{permission}'"
            )

    # Impostor rule: any android:name that is not EXACTLY the pinned FQN but
    # carries the GuardXposedProvider marker (or the libxposed XposedProvider
    # marker) fails closed, regardless of tag or element shape.
    for element in elements:
        name = element_name(element)
        if not name:
            continue
        low = name.lower()
        if name == EXPECTED_PROVIDER:
            if element["tag"] != "provider":
                errors.append(
                    f"GuardXposedProvider-named element <{element['tag']}> is not a <provider> "
                    f"(name=\"{name}\")"
                )
            continue
        if "guardxposedprovider" in low:
            errors.append(
                f"<{element['tag']}> android:name unexpected GuardXposedProvider FQN '{name}' "
                f"(only exactly {EXPECTED_PROVIDER} is allowed)"
            )
        elif "xposedprovider" in low:
            errors.append(
                f"<{element['tag']}> android:name contains XposedProvider "
                f"(libxposed service provider must not ship): '{name}'"
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
