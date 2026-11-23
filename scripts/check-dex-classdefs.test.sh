#!/usr/bin/env bash
# Self-test for scripts/check-dex-classdefs.py (issue #67).
#
# Everything is generated offline from a synthetic minimal DEX builder embedded
# below (Python 3 stdlib only) — no SDK, no network, no checked-in binary
# fixture. The builder emits real DEX headers (112-byte header, little-endian
# tag 0x12345678, 32-byte class_def_item entries) and wraps them in fake APKs
# via zipfile so the checker's CLI path (classes*.dex inside an APK zip) is
# exercised, not just the importable parsing function.
#
# Key regression fixture: negative.apk keeps the target descriptor STRING and
# its type_ids reference but has class_defs_size=0. The old `grep -aFc
# <descriptor>` gate reported a hit on that APK (RED evidence assertion below);
# the class_def parser must reject it with ::error:: (GREEN). A second key
# fixture, inner-only.apk, has class_defs ONLY for "$Inner;"/"$Companion;"
# inner classes; it pins exact-descriptor matching against a lenient
# prefix/startswith mutation that would inflate one target into many.
#
# Intentionally `set -u` (not -e): pass and fail exit codes are both asserted.
set -u

cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(pwd)"
CHECKER="$REPO_ROOT/scripts/check-dex-classdefs.py"

TARGET='Lrikka/shizuku/ShizukuProvider;'
OTHER='Lcom/galaxydual/hook/DualHook;'
MISSING='Lcom/example/NotPresent;'

SUITE_TMP="$(mktemp -d "${TMPDIR:-/tmp}/check-dex-classdefs-test.XXXXXX")" || {
  echo "::error::mktemp -d failed" >&2
  exit 1
}
trap 'rm -rf "$SUITE_TMP"' EXIT

# NOTE: no early "checker exists" guard — a missing/unreadable checker must
# surface as assertion MISMATCHES (RED), not as a silent early exit.

# --- synthetic minimal DEX generator (Python 3 stdlib, fully offline) --------
cat > "$SUITE_TMP/gen_fixtures.py" <<'PYGEN'
import os
import struct
import sys
import zipfile

OUT = sys.argv[1]
HEADER_SIZE = 112
ENDIAN_LITTLE = 0x12345678


def uleb128(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def build_dex(descriptors, class_idxs, endian_tag=ENDIAN_LITTLE, big=False):
    """Build a minimal but structurally valid DEX.

    descriptors: strings in string_ids/type_ids (index i maps to type i).
    class_idxs:  type_ids index for each 32-byte class_def_item.
    big:         emit fields big-endian with the reversed endian tag.
    """
    endian = ">" if big else "<"
    n_str = len(descriptors)
    n_types = len(descriptors)
    n_defs = len(class_idxs)

    str_ids_off = HEADER_SIZE
    type_ids_off = str_ids_off + 4 * n_str
    defs_off = type_ids_off + 4 * n_types
    data_off = (defs_off + 32 * n_defs + 3) & ~3

    data = bytearray()
    string_offs = []
    for desc in descriptors:
        string_offs.append(data_off + len(data))
        raw = desc.encode("utf-8")
        # DEX string_data_item = uleb128(utf16_size) + MUTF-8 bytes + NUL.
        data += uleb128(len(raw)) + raw + b"\x00"
    while len(data) % 4:
        data += b"\x00"
    file_size = data_off + len(data)

    hdr = bytearray(HEADER_SIZE)
    hdr[0:8] = b"dex\n035\x00"
    struct.pack_into(endian + "I", hdr, 0x20, file_size)
    struct.pack_into(endian + "I", hdr, 0x24, HEADER_SIZE)
    # The tag bytes are fixed by the format: 78 56 34 12 for a normal DEX,
    # 12 34 56 78 for a byte-swapped one. Emit those raw bytes little-endian;
    # the reader interprets them as 0x12345678 / 0x78563412 and picks the
    # byte order for the remaining tables.
    packed_tag = (0x78563412 if big else ENDIAN_LITTLE)
    struct.pack_into("<I", hdr, 0x28, packed_tag)
    struct.pack_into(endian + "II", hdr, 0x38, n_str, str_ids_off)
    struct.pack_into(endian + "II", hdr, 0x40, n_types, type_ids_off)
    struct.pack_into(endian + "II", hdr, 0x60, n_defs, defs_off)
    struct.pack_into(endian + "II", hdr, 0x68, len(data), data_off)

    out = bytearray(hdr)
    for off in string_offs:
        out += struct.pack(endian + "I", off)
    assert len(out) == type_ids_off
    for i in range(n_types):
        out += struct.pack(endian + "I", i)
    assert len(out) == defs_off
    for idx in class_idxs:
        # class_def_item is EIGHT uint32 (32 bytes), not 20.
        out += struct.pack(endian + "8I", idx, 0, 0, 0, 0, 0, 0, 0)
    assert len(out) == data_off, (len(out), data_off)
    out += data
    assert len(out) == file_size
    return bytes(out)


def write_apk(name, members):
    with zipfile.ZipFile(os.path.join(OUT, name), "w", zipfile.ZIP_DEFLATED) as zf:
        for member, payload in members.items():
            if isinstance(payload, str):
                payload = payload.encode("utf-8")
            zf.writestr(member, payload)


target = "Lrikka/shizuku/ShizukuProvider;"
other = "Lcom/galaxydual/hook/DualHook;"

positive = build_dex([target], [0])
# #67 false-positive fixture: string table + type_ids kept, ZERO class_defs.
negative = build_dex([target], [])
missing = build_dex([other], [0])
# Mutation guard: class_defs exist ONLY for inner/synthetic classes. The plain
# target descriptors are still present in string_ids/type_ids (as a type
# reference would be), but there is NO class_def_item for either target.
# Precise `.count(descriptor)` -> MISSING; a lenient
# `startswith(descriptor[:-1])` would match "$Inner;"/"$Companion;" and pass.
inner_only = build_dex(
    [target, target + "$Inner;", other, other + "$Companion;"],
    [1, 3],  # class_def for the inner classes only
)
count2 = build_dex([target, other], [0, 0])
both = build_dex([target, other], [0, 1])
bad_index = build_dex([target], [999])
big_endian = build_dex([target], [0], big=True)
bad_endian = bytearray(positive)
struct.pack_into("<I", bad_endian, 0x28, 0xDEADBEEF)
truncated = positive[:50]

for name, payload in [
    ("positive.dex", positive),
    ("negative.dex", negative),
    ("count2.dex", count2),
]:
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(payload)

write_apk("positive.apk", {"classes.dex": positive})
write_apk("negative.apk", {"classes.dex": negative})
write_apk("missing.apk", {"classes.dex": missing})
write_apk("inner-only.apk", {"classes.dex": inner_only})
write_apk("count2.apk", {"classes.dex": count2})
write_apk("both.apk", {"classes.dex": both})
write_apk("multi.apk", {"classes.dex": positive, "classes2.dex": count2})
write_apk("big-endian.apk", {"classes.dex": big_endian})
write_apk("truncated.apk", {"classes.dex": truncated})
write_apk("bad-endian.apk", {"classes.dex": bytes(bad_endian)})
write_apk("bad-index.apk", {"classes.dex": bad_index})
write_apk("no-dex.apk", {"META-INF/xposed/module.prop": "id=io.github.samson910022.galaxydualplus\n"})
with open(os.path.join(OUT, "not-a-zip.apk"), "wb") as f:
    f.write(b"this is not a zip archive")
print("generated fixtures in", OUT)
PYGEN

python3 "$SUITE_TMP/gen_fixtures.py" "$SUITE_TMP" || {
  echo "::error::synthetic DEX fixture generation failed" >&2
  exit 1
}

total=0
matched=0
mismatched=0

pass_case() {
  total=$((total + 1))
  matched=$((matched + 1))
  printf 'PASS %s\n' "$1"
}

bad_case() {
  total=$((total + 1))
  mismatched=$((mismatched + 1))
  printf '::error::FAIL %s\n' "$1"
  if [ -n "${2:-}" ]; then
    printf '%s\n' "$2" | sed 's/^/     /'
  fi
}

# Run the checker; assert both exit code and a required output substring.
assert_case() {
  local name="$1" apk="$2" expected="$3" needle="$4"
  shift 4
  local output rc=0
  output="$(python3 "$CHECKER" "$apk" "$@" 2>&1)" || rc=$?
  if [ "$rc" -eq "$expected" ] && printf '%s\n' "$output" | grep -qF -- "$needle"; then
    pass_case "$name (exit $rc, pinned '$needle')"
  else
    bad_case "$name (expected exit $expected + pinned '$needle', got exit $rc)" "$output"
  fi
}

# 1. positive: one real class_def -> FOUND with count=1, exit 0.
assert_case "positive single class_def" "$SUITE_TMP/positive.apk" 0 \
  "FOUND class_def $TARGET in classes.dex (count=1)" "$TARGET"

# 1b. byte-swapped (big-endian) DEX with the same class_def must also pass.
assert_case "big-endian DEX accepted" "$SUITE_TMP/big-endian.apk" 0 \
  "FOUND class_def $TARGET in classes.dex (count=1)" "$TARGET"

# 2. #67 negative: descriptor string + type_ids present, class_defs_size=0.
assert_case "negative zero class_defs (issue #67)" "$SUITE_TMP/negative.apk" 1 \
  "class_defs_size=0" "$TARGET"

# 2b. RED evidence: the OLD grep gate saw the descriptor string in that same
#     APK, proving the false positive is real and not hypothetical.
old_hits="$(unzip -p "$SUITE_TMP/negative.apk" classes.dex | grep -aFc "$TARGET" || true)"
if [ "${old_hits:-0}" -gt 0 ]; then
  pass_case "RED evidence: old grep -aFc counts $old_hits descriptor hit(s) in zero-class_def negative.apk"
else
  bad_case "RED evidence missing: old grep found no descriptor string in negative.apk (cannot demonstrate #67)"
fi

# 3. class_defs exist but for a different class -> MISSING, exit 1.
assert_case "missing descriptor with non-empty class_defs" "$SUITE_TMP/missing.apk" 1 \
  "::error::MISSING class_def $TARGET" "$TARGET"

# 3b. Mutation guard (review finding): inner/synthetic classes only, the two
#     target descriptors themselves have NO class_def. Exact matching must
#     report MISSING. The review's `startswith(descriptor[:-1])` mutation made
#     these inner classes count as the target (real APK: DualHook 1 -> 33) and
#     turned this into a false pass — this case pins that regression.
assert_case "inner-class-only class_defs (ShizukuProvider target absent)" \
  "$SUITE_TMP/inner-only.apk" 1 \
  "::error::MISSING class_def $TARGET" "$TARGET"
assert_case "companion-only class_defs (DualHook target absent)" \
  "$SUITE_TMP/inner-only.apk" 1 \
  "::error::MISSING class_def $OTHER" "$OTHER"

# 4. two class_defs for the same descriptor -> count=2, exit 0.
assert_case "count=2 for repeated class_def" "$SUITE_TMP/count2.apk" 0 \
  "FOUND class_def $TARGET in classes.dex (count=2)" "$TARGET"

# 5. multi-DEX: counts across classes.dex and classes2.dex, exit 0.
assert_case "multi-dex both entries" "$SUITE_TMP/multi.apk" 0 \
  "FOUND class_def $TARGET in classes2.dex (count=2)" "$TARGET"

# 6. CLI accepts multiple descriptors in one invocation, exit 0.
assert_case "multiple descriptors CLI" "$SUITE_TMP/both.apk" 0 \
  "FOUND class_def $OTHER in classes.dex (count=1)" "$TARGET" "$OTHER"

# 7. one of multiple descriptors missing -> ::error::MISSING, exit 1.
assert_case "one missing among multiple descriptors" "$SUITE_TMP/both.apk" 1 \
  "::error::MISSING class_def $MISSING" "$TARGET" "$MISSING"

# 8. truncated DEX -> fail closed with ::error::.
assert_case "truncated DEX header" "$SUITE_TMP/truncated.apk" 1 \
  "::error::" "$TARGET"

# 9. invalid endian tag -> fail closed with ::error:: mentioning endian.
assert_case "invalid endian tag" "$SUITE_TMP/bad-endian.apk" 1 \
  "endian" "$TARGET"

# 10. class_idx out of range -> fail closed with ::error::.
assert_case "out-of-range class_idx" "$SUITE_TMP/bad-index.apk" 1 \
  "::error::" "$TARGET"

# 11. APK with no classes*.dex -> fail closed.
assert_case "no DEX entries in APK" "$SUITE_TMP/no-dex.apk" 1 \
  "::error::" "$TARGET"

# 12. unreadable/non-zip APK -> fail closed.
assert_case "non-zip APK" "$SUITE_TMP/not-a-zip.apk" 1 \
  "::error::" "$TARGET"

# 13. importable count_class_defs() unit check (not just the CLI).
# The checker filename is hyphenated (matching the other scripts/ names), so
# load it via importlib under a valid module name rather than a plain import.
unit_output="$(python3 -c "
import importlib.util
spec = importlib.util.spec_from_file_location('check_dex_classdefs', '$CHECKER')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
dex = open('$SUITE_TMP/positive.dex', 'rb').read()
print(m.count_class_defs(dex, '$TARGET'), m.count_class_defs(dex, '$OTHER'))
" 2>&1)" || true
if [ "$unit_output" = "1 0" ]; then
  pass_case "count_class_defs() importable unit check (1, 0)"
else
  bad_case "count_class_defs() must return 1 for target and 0 for other" "$unit_output"
fi

echo "----"
printf 'summary: %d assertions, %d as expected, %d mismatched\n' "$total" "$matched" "$mismatched"
if [ "$mismatched" -ne 0 ]; then
  exit 1
fi
exit 0
