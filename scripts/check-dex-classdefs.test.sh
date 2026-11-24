#!/usr/bin/env bash
# Self-test for scripts/check-dex-classdefs.py (issue #67).
#
# All fixtures are generated offline by the shared, parser-specific builder
# scripts/fixtures/dex_builder.py (Python 3 stdlib only) — no SDK, no network,
# no checked-in binary fixture. The builder emits minimal DEX headers/tables
# and wraps them in fake APK ZIPs so the checker's CLI path (canonical
# classes*.dex inside an APK) is exercised, not just the importable parser.
#
# Key regression fixture: negative.apk keeps the target descriptor STRING and
# its type_ids reference but has class_defs_size=0. The old `grep -aFc
# <descriptor>` gate reported a hit on that APK (RED evidence assertion below);
# the class_def parser must reject it with ::error:: (GREEN). A second key
# fixture, inner-only.apk, has class_defs ONLY for "$Inner;"/"$Companion;"
# inner classes; it pins exact-descriptor matching against a lenient
# prefix/startswith mutation that would inflate one target into many.
#
# Multi-dex / structural mutations pin the review findings: non-canonical or
# missing/split DEX member names, file_size/magic lies, bad tables, malformed
# MUTF-8 and duplicate class_defs must all fail closed with SPECIFIC messages.
#
# Intentionally `set -u` (not -e): pass and fail exit codes are both asserted.
set -u

cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(pwd)"
CHECKER="$REPO_ROOT/scripts/check-dex-classdefs.py"
BUILDER="$REPO_ROOT/scripts/fixtures/dex_builder.py"

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

fixtures_output="$(python3 "$BUILDER" "$SUITE_TMP" 2>&1)" || {
  echo "::error::shared DEX fixture generation failed: $fixtures_output" >&2
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
# An optional `--no-found` after the needle also requires no FOUND class_def
# line (used for the NUL-member false-pass fixtures).
assert_case() {
  local name="$1" apk="$2" expected="$3" needle="$4"
  shift 4
  local no_found=0
  if [ "${1:-}" = "--no-found" ]; then
    no_found=1
    shift
  fi
  local output rc=0 ok=1
  output="$(python3 "$CHECKER" "$apk" "$@" 2>&1)" || rc=$?
  if [ "$rc" -ne "$expected" ]; then ok=0; fi
  if ! printf '%s\n' "$output" | grep -qF -- "$needle"; then ok=0; fi
  if [ "$no_found" -eq 1 ] && printf '%s\n' "$output" | grep -qF -- "FOUND class_def"; then
    ok=0
  fi
  if [ "$ok" -eq 1 ]; then
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

# --- R3: positive parser coverage (index mapping / ULEB128 / MUTF-8) --------
# Genuinely non-identity mapping: class_idx 0 -> type_ids[0].descriptor_idx 1
# -> strings[1] == TARGET. strings[0] (empty) and strings[2] (type-looking but
# with NO class_def) are non-type strings that must not satisfy the gate.
assert_case "non-identity class_idx -> type_ids -> descriptor_idx mapping" \
  "$SUITE_TMP/nonidentity-type-index.apk" 0 \
  "FOUND class_def $TARGET in classes.dex (count=1)" "$TARGET"

# 300 ASCII code units force a two-byte ULEB128 utf16_size prefix whose first
# byte is not the full value. Build the expected descriptor in shell so no
# 300-literal-character string is needed.
long_units="$(printf 'a%.0s' $(seq 1 300))"
long_descriptor="Lcom/example/${long_units}/LongTarget;"
assert_case "multi-byte ULEB128 utf16_size (300 UTF-16 code units)" \
  "$SUITE_TMP/long-uleb128.apk" 0 \
  "FOUND class_def $long_descriptor in classes.dex (count=1)" "$long_descriptor"

# Canonical MUTF-8 with 2-byte (Latin-1: Ü, ï, ä) and 3-byte (U+20AC) sequences.
nonascii_descriptor='Lcom/example/Ünïcode€/Tärget;'
assert_case "canonical non-ASCII MUTF-8 descriptor" \
  "$SUITE_TMP/nonascii-mutf8.apk" 0 \
  "FOUND class_def $nonascii_descriptor in classes.dex (count=1)" "$nonascii_descriptor"

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

# 3b. Mutation guard: inner/synthetic classes only, the two target
#     descriptors themselves have NO class_def. Exact matching must report
#     MISSING. The review's `startswith(descriptor[:-1])` mutation made these
#     inner classes count as the target and turned this into a false pass.
assert_case "inner-class-only class_defs (ShizukuProvider target absent)" \
  "$SUITE_TMP/inner-only.apk" 1 \
  "::error::MISSING class_def $TARGET" "$TARGET"
assert_case "companion-only class_defs (DualHook target absent)" \
  "$SUITE_TMP/inner-only.apk" 1 \
  "::error::MISSING class_def $OTHER" "$OTHER"

# 4. (replaces the old count2.apk false pass) Duplicate class_idx in one DEX
#    is illegal per the DEX spec and must fail closed, not yield count=2.
assert_case "duplicate class_idx rejected" "$SUITE_TMP/dup-classidx.apk" 1 \
  "duplicate class_def class_idx 0" "$TARGET"

# 4b. Two type_ids entries with the same descriptor also produce two
#     class_defs for one class — reject the duplicate descriptor itself.
assert_case "duplicate descriptor within one DEX rejected" \
  "$SUITE_TMP/dup-descriptor-within.apk" 1 \
  "duplicate class_def descriptor $TARGET" "$TARGET"

# 5. Multi-DEX: target ONLY in the secondary entry; both descriptors found.
assert_case "multi-dex target only in classes2.dex" "$SUITE_TMP/multi.apk" 0 \
  "FOUND class_def $TARGET in classes2.dex (count=1)" "$TARGET" "$OTHER"
assert_case "multi-dex primary entry still reported" "$SUITE_TMP/multi.apk" 0 \
  "FOUND class_def $OTHER in classes.dex (count=1)" "$TARGET" "$OTHER"

# 5b. Two-digit secondary indices (classes10.dex / classes11.dex) are canonical
# Android multi-dex names: an 11-DEX APK with the target only in classes11.dex
# must pass, with the FOUND line pinned to that exact member.
assert_case "multi-dex target only in classes11.dex (11 members)" \
  "$SUITE_TMP/eleven-dex.apk" 0 \
  "FOUND class_def $TARGET in classes11.dex" "$TARGET"

# 6. CLI accepts multiple descriptors in one invocation, exit 0.
assert_case "multiple descriptors CLI" "$SUITE_TMP/both.apk" 0 \
  "FOUND class_def $OTHER in classes.dex (count=1)" "$TARGET" "$OTHER"

# 7. one of multiple descriptors missing -> ::error::MISSING, exit 1.
assert_case "one missing among multiple descriptors" "$SUITE_TMP/both.apk" 1 \
  "::error::MISSING class_def $MISSING" "$TARGET" "$MISSING"

# 8. truncated DEX -> fail closed with a specific message.
assert_case "truncated DEX header" "$SUITE_TMP/truncated.apk" 1 \
  "truncated DEX: 50 bytes < 112-byte header" "$TARGET"

# 9. invalid endian tag -> fail closed with ::error:: mentioning endian.
assert_case "invalid endian tag" "$SUITE_TMP/bad-endian.apk" 1 \
  "invalid DEX endian_tag" "$TARGET"

# 10. class_idx out of range -> fail closed.
assert_case "out-of-range class_idx" "$SUITE_TMP/bad-index.apk" 1 \
  "class_idx 999 out of range" "$TARGET"

# 11. APK with no classes*.dex -> fail closed (missing primary).
assert_case "no DEX entries in APK" "$SUITE_TMP/no-dex.apk" 1 \
  "missing primary classes.dex" "$TARGET"

# 12. unreadable/non-zip APK -> fail closed.
assert_case "non-zip APK" "$SUITE_TMP/not-a-zip.apk" 1 \
  "unable to open APK" "$TARGET"

# --- canonical multi-dex enumeration ---------------------------------------
assert_case "missing primary (only classes2.dex)" "$SUITE_TMP/no-primary.apk" 1 \
  "missing primary classes.dex" "$TARGET"

assert_case "secondary numbering gap" "$SUITE_TMP/numbering-gap.apk" 1 \
  "non-contiguous secondary DEX numbering" "$TARGET"

assert_case "non-canonical classes0.dex" "$SUITE_TMP/noncanonical-classes0.apk" 1 \
  "non-canonical DEX entry name 'classes0.dex'" "$TARGET"

assert_case "non-canonical classes1.dex" "$SUITE_TMP/noncanonical-classes1.apk" 1 \
  "non-canonical DEX entry name 'classes1.dex'" "$TARGET"

assert_case "non-canonical classes01.dex" "$SUITE_TMP/noncanonical-classes01.apk" 1 \
  "non-canonical DEX entry name 'classes01.dex'" "$TARGET"

# A valid primary sibling must not mask a non-canonical secondary name.
assert_case "non-canonical classes1.dex with valid primary" \
  "$SUITE_TMP/noncanonical-with-primary.apk" 1 \
  "non-canonical DEX entry name 'classes1.dex'" "$TARGET"

assert_case "duplicate ZIP member classes.dex" "$SUITE_TMP/duplicate-member.apk" 1 \
  "duplicate ZIP member 'classes.dex'" "$TARGET"

# R1: ZipInfo.filename truncates at the first NUL, so a raw name
# `classes.dex\x00tail` looked canonical and a valid positive DEX payload
# false-passed. Pin both the generic message and the raw repr (four literal
# chars backslash-x-0-0) and require no FOUND line at all.
assert_case "NUL byte in primary ZIP member name" \
  "$SUITE_TMP/nul-member-name.apk" 1 \
  "NUL byte in ZIP member name 'classes.dex\\x00tail'" --no-found "$TARGET"

# Same guard must fire for a NUL-bearing secondary name even when a clean,
# canonical classes.dex is present.
assert_case "NUL byte in secondary ZIP member name" \
  "$SUITE_TMP/nul-secondary-with-primary.apk" 1 \
  "NUL byte in ZIP member name" --no-found "$TARGET"

# --- strict DEX structural validation --------------------------------------
assert_case "bad magic version xyz" "$SUITE_TMP/bad-magic.apk" 1 \
  "invalid DEX magic version b'xyz'" "$TARGET"

assert_case "unsupported magic version 041" "$SUITE_TMP/bad-version.apk" 1 \
  "invalid DEX magic version b'041'" "$TARGET"

# ART skips 036; a contiguous range(35,41) allowlist used to accept it.
assert_case "unsupported magic version 036 (ART skips it)" \
  "$SUITE_TMP/bad-version-036.apk" 1 \
  "invalid DEX magic version b'036'" "$TARGET"

# One compact unit assertion for the full ACCEPTED set: 035, 037, 038, 039 and
# 040 must each resolve the target class_def. (Rejection of 036, 041 and xyz is
# pinned by the dedicated APK cases; 034 is checked in separate verification.)
cat > "$SUITE_TMP/version_matrix.py" <<'PYVER'
import importlib.util
import sys

checker, builder_dir, target = sys.argv[1:4]
sys.path.insert(0, builder_dir)
import dex_builder as builder

spec = importlib.util.spec_from_file_location("check_dex_classdefs_versions", checker)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

problems = []
for version in (b"035", b"037", b"038", b"039", b"040"):
    dex = builder.build_dex([target], version=version)
    try:
        descriptors = module.parse_class_def_descriptors(dex)
    except module.DexError as exc:
        problems.append(f"{version!r} rejected: {exc}")
    else:
        if descriptors != [target]:
            problems.append(f"{version!r} resolved to {descriptors!r}")
print("OK" if not problems else "PROBLEMS: " + " | ".join(problems))
PYVER
version_rc=0
version_output="$(python3 "$SUITE_TMP/version_matrix.py" \
  "$CHECKER" "$REPO_ROOT/scripts/fixtures" "$TARGET" 2>&1)" || version_rc=$?
if [ "$version_rc" -eq 0 ] && [ "$version_output" = "OK" ]; then
  pass_case "DEX version allowlist 035/037/038/039/040 all resolve the target (exit 0)"
else
  bad_case "every allowlisted version must resolve the target (got exit $version_rc)" "$version_output"
fi

assert_case "file_size too small" "$SUITE_TMP/file-size-small.apk" 1 \
  "DEX file_size 187 != DEX member byte length 188" "$TARGET"

assert_case "file_size too large" "$SUITE_TMP/file-size-large.apk" 1 \
  "DEX file_size 189 != DEX member byte length 188" "$TARGET"

assert_case "wrong header_size" "$SUITE_TMP/bad-header-size.apk" 1 \
  "unsupported DEX header_size 0x71" "$TARGET"

assert_case "out-of-range table offset" "$SUITE_TMP/bad-string-ids-offset.apk" 1 \
  "string_ids offset" "$TARGET"

assert_case "misaligned table offset" "$SUITE_TMP/misaligned-offset.apk" 1 \
  "misaligned string_ids offset" "$TARGET"

assert_case "malformed uleb128" "$SUITE_TMP/malformed-uleb.apk" 1 \
  "malformed uleb128" "$TARGET"

assert_case "truncated uleb128" "$SUITE_TMP/truncated-uleb.apk" 1 \
  "truncated uleb128" "$TARGET"

assert_case "string_data_off outside data section" "$SUITE_TMP/string-off-outside-data.apk" 1 \
  "string_data_off 112" "$TARGET"

assert_case "unterminated string_data_item" "$SUITE_TMP/unterminated-string.apk" 1 \
  "unterminated string_data_item" "$TARGET"

assert_case "utf16_size zero" "$SUITE_TMP/utf16-size-zero.apk" 1 \
  "utf16_size 0 for string_idx 0" "$TARGET"

assert_case "utf16_size larger than actual" "$SUITE_TMP/utf16-size-large.apk" 1 \
  "utf16_size 39 for string_idx 0" "$TARGET"

# --- MUTF-8 decoding guards -------------------------------------------------
# Each payload's utf16_sizes is the would-be decoded code-unit count, so the
# decoder error (not the utf16_size equality check) is the expected failure.
assert_case "invalid MUTF-8 leading byte 0x80" "$SUITE_TMP/mutf8-leading.apk" 1 \
  "invalid MUTF-8 leading byte 0x80 for string_idx 0" "$TARGET"

assert_case "overlong 2-byte MUTF-8 sequence" "$SUITE_TMP/mutf8-overlong.apk" 1 \
  "overlong MUTF-8 sequence for string_idx 0" "$TARGET"

assert_case "truncated 3-byte MUTF-8 sequence" "$SUITE_TMP/mutf8-truncated.apk" 1 \
  "truncated MUTF-8 sequence for string_idx 0" "$TARGET"

# --- more structural DEX guards --------------------------------------------
assert_case "bad map_off" "$SUITE_TMP/bad-map-off.apk" 1 \
  "map_off 8 outside DEX" "$TARGET"

assert_case "data_size exceeds file_size" "$SUITE_TMP/bad-data-size.apk" 1 \
  "data_size 188 at data_off" "$TARGET"

assert_case "overlapping DEX tables" "$SUITE_TMP/overlapping-tables.apk" 1 \
  "overlapping DEX tables" "$TARGET"

assert_case "table out of bounds" "$SUITE_TMP/table-out-of-bounds.apk" 1 \
  "class_defs table out of bounds" "$TARGET"

# --- R2b: table/data overlap and map_off data-section bounds -----------------
# All four are HEADER-ONLY mutations of the valid positive DEX; no payload or
# table bytes move. The old checker accepted all of them (false passes).
assert_case "class_defs table crossing into data section" \
  "$SUITE_TMP/data-overlaps-classdefs.apk" 1 \
  "class_defs table [120, 152) must end at or before data_off 120" "$TARGET"

assert_case "map_off inside file but outside data section" \
  "$SUITE_TMP/map-off-outside-data.apk" 1 \
  "map_off 120 outside data section [152, 188)" "$TARGET"

assert_case "map_off with fewer than 4 bytes left in data section" \
  "$SUITE_TMP/map-off-crosses-data-end.apk" 1 \
  "map_off 184 outside data section [152, 187)" "$TARGET"

# Positive control: a nonzero map_off at data_off is legitimate (real DEXes
# carry one), so the new bounds check must not reject it.
assert_case "nonzero map_off at data_off accepted" \
  "$SUITE_TMP/nonzero-map-off-valid.apk" 0 \
  "FOUND class_def $TARGET in classes.dex (count=1)" "$TARGET"

assert_case "duplicate descriptor across DEXes" "$SUITE_TMP/dup-descriptor-across.apk" 1 \
  "defined in multiple DEX entries" "$TARGET"

assert_case "empty sibling DEX fails closed" "$SUITE_TMP/empty-sibling.apk" 1 \
  "class_defs_size=0" "$TARGET"

# 13. importable count_class_defs() unit check (not just the CLI). The checker
# filename is hyphenated, so load it via importlib under a valid module name.
# Values are passed strictly via sys.argv — never interpolated into Python
# source — and the helper's exit code is asserted alongside its output.
cat > "$SUITE_TMP/unit_check.py" <<'PYUNIT'
import importlib.util
import sys

checker, dex_path, target, other = sys.argv[1:5]
spec = importlib.util.spec_from_file_location("check_dex_classdefs", checker)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
with open(dex_path, "rb") as handle:
    dex = handle.read()
print(module.count_class_defs(dex, target), module.count_class_defs(dex, other))
PYUNIT
unit_rc=0
unit_output="$(python3 "$SUITE_TMP/unit_check.py" \
  "$CHECKER" "$SUITE_TMP/positive.dex" "$TARGET" "$OTHER" 2>&1)" || unit_rc=$?
if [ "$unit_rc" -eq 0 ] && [ "$unit_output" = "1 0" ]; then
  pass_case "count_class_defs() importable unit check (1, 0; exit 0)"
else
  bad_case "count_class_defs() must return '1 0' with exit 0 (got exit $unit_rc)" "$unit_output"
fi

# 14. Defense-in-depth: pin the MUTF-8 canonical re-encode guard directly.
#     The strict decoder already rejects every non-canonical byte sequence, so
#     a data-only fixture cannot isolate this guard. Patch in a deliberately
#     regressed decoder (NUL scan + errors="replace") on the invalid-leading-
#     byte fixture; _string_at must still reject the resulting bytes.
cat > "$SUITE_TMP/unit_canonical.py" <<'PYUNIT'
import importlib.util
import sys

checker, dex_path = sys.argv[1:3]
spec = importlib.util.spec_from_file_location("check_dex_classdefs_canonical", checker)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def regressed_decode(data, start, limit, index):
    nul = data.find(b"\x00", start, limit)
    if nul < 0:
        raise module.DexError(f"unterminated string_data_item for string_idx {index}")
    raw = bytes(data[start:nul])
    return raw.decode("utf-8", errors="replace"), raw, nul + 1


module._decode_mutf8 = regressed_decode
with open(dex_path, "rb") as handle:
    dex = handle.read()
try:
    module.parse_class_def_descriptors(dex)
except module.DexError as exc:
    print(f"DexError: {exc}")
else:
    print("NO ERROR: canonical MUTF-8 guard did not fire")
PYUNIT
canon_rc=0
canon_output="$(python3 "$SUITE_TMP/unit_canonical.py" \
  "$CHECKER" "$SUITE_TMP/mutf8-leading.dex" 2>&1)" || canon_rc=$?
canon_needle="DexError: non-canonical MUTF-8 bytes for string_idx 0"
if [ "$canon_rc" -eq 0 ] && printf '%s\n' "$canon_output" | grep -qF -- "$canon_needle"; then
  pass_case "canonical MUTF-8 re-encode guard rejects a decoder regression (exit 0)"
else
  bad_case "canonical MUTF-8 guard must reject regressed decoder with '$canon_needle' (got exit $canon_rc)" "$canon_output"
fi

# 15. R3 importable-API checks: (a) the long descriptor really has a multi-byte
#     utf16_size prefix in the emitted DEX (first byte carries the continuation
#     bit and is not the full 325-code-unit value), and (b) an astral character spelled
#     as an explicit UTF-16 surrogate pair round-trips as two MUTF-8 code units.
#     Values come strictly from sys.argv — lone surrogates cannot travel in
#     argv/locale, so they are passed as hex code-point strings and rebuilt.
cat > "$SUITE_TMP/unit_r3.py" <<'PYR3'
import importlib.util
import struct
import sys
import zipfile

checker, builder_dir, apk_path, long_descriptor, hi, lo = sys.argv[1:7]
sys.path.insert(0, builder_dir)
import dex_builder as builder

spec = importlib.util.spec_from_file_location("check_dex_classdefs_r3", checker)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

problems = []

# (a) Inspect the actual emitted DEX: the first byte at string_data_off[0]
# must carry the ULEB128 continuation bit and differ from the full value
# (325 UTF-16 code units here, so a single-byte decode would be wrong).
with zipfile.ZipFile(apk_path) as zf:
    dex = zf.read("classes.dex")
string_ids_off = struct.unpack_from("<I", dex, 0x3C)[0]
string_off = struct.unpack_from("<I", dex, string_ids_off)[0]
prefix = dex[string_off]
encoded = builder.uleb128(len(long_descriptor))
if not (prefix & 0x80) or len(encoded) < 2 or prefix == len(long_descriptor):
    problems.append(f"utf16_size prefix 0x{prefix:02x} is not a non-trivial multi-byte prefix")

# (b) Surrogate pair (U+1F600): two canonical 3-byte MUTF-8 sequences, never a
# 4-byte UTF-8 sequence. The builder is used via the importable API only.
pair = chr(int(hi, 16)) + chr(int(lo, 16))
surrogate_descriptor = "Lcom/example/" + pair + "/EmojiTarget;"
try:
    surrogate_dex = builder.build_dex([surrogate_descriptor])
    descriptors = module.parse_class_def_descriptors(surrogate_dex)
except module.DexError as exc:
    problems.append(f"surrogate-pair descriptor rejected: {exc}")
else:
    if descriptors != [surrogate_descriptor]:
        problems.append(f"surrogate-pair resolved to {descriptors!r}")

print("OK" if not problems else "PROBLEMS: " + " | ".join(problems))
PYR3
r3_rc=0
r3_output="$(python3 "$SUITE_TMP/unit_r3.py" \
  "$CHECKER" "$REPO_ROOT/scripts/fixtures" "$SUITE_TMP/long-uleb128.apk" \
  "$long_descriptor" d83d de00 2>&1)" || r3_rc=$?
if [ "$r3_rc" -eq 0 ] && [ "$r3_output" = "OK" ]; then
  pass_case "R3 importable API: multi-byte ULEB128 prefix + surrogate-pair MUTF-8 (exit 0)"
else
  bad_case "R3 importable API checks must pass (got exit $r3_rc)" "$r3_output"
fi

echo "----"
printf 'summary: %d assertions, %d as expected, %d mismatched\n' "$total" "$matched" "$mismatched"
if [ "$mismatched" -ne 0 ]; then
  exit 1
fi
exit 0
