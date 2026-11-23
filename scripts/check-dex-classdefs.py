#!/usr/bin/env python3
# DEX class_def gate (issue #67).
#
# Why this exists: the historical release gate used
#   unzip -p "$apk" "$dex" | grep -aFc '<descriptor>'
# to decide that a class "survived into the DEX". A descriptor STRING in the
# DEX string table is also emitted for mere type references (fields, method
# signatures, annotations, manifests copied into code), so an APK with ZERO
# class_def_item entries for that class still passed the gate. This checker
# parses the DEX header/tables and counts actual class_def_item entries whose
# class_idx resolves to the exact descriptor — a string/type reference alone
# can never satisfy it.
#
# CLI:
#   python3 scripts/check-dex-classdefs.py <apk> <descriptor> [descriptor ...]
#
# Reads every classes*.dex entry from the APK zip, parses each DEX header
# (header_size/endian_tag/string_ids/type_ids/class_defs), resolves
# class_idx -> type_ids[class_idx].descriptor_idx -> MUTF-8 string (skipping
# the uleb128 length prefix), and prints
#   FOUND class_def <descriptor> in <dex> (count=<n>)
# for every descriptor present in every DEX. Every requested descriptor must
# have at least one class_def, otherwise the checker prints one
#   ::error::MISSING class_def <descriptor> ...
# per missing descriptor and exits 1.
#
# Fail closed (::error:: + exit 1) on: unreadable APK, no classes*.dex entry,
# truncated DEX, bad magic, wrong header_size or endian_tag, out-of-range
# offsets/tables/indices, and class_defs_size=0. There is deliberately NO
# grep/raw-byte fallback — that fallback is exactly the false positive above.
#
# Importable API (used by scripts/check-dex-classdefs.test.sh):
#   count_class_defs(dex_bytes, descriptor) -> int
#   parse_class_def_descriptors(dex_bytes) -> list[str]
# Both raise DexError on malformed input.
import re
import struct
import sys
import zipfile

HEADER_SIZE = 112          # 0x70; class_defs data lives past this header
ENDIAN_LITTLE = 0x12345678  # tag read little-endian -> little-endian tables
ENDIAN_BIG = 0x78563412     # tag read little-endian -> big-endian tables
DEX_NAME_RE = re.compile(r"classes[0-9]*\.dex")

# DEX header field offsets (byte positions).
OFF_FILE_SIZE = 0x20
OFF_HEADER_SIZE = 0x24
OFF_ENDIAN_TAG = 0x28
OFF_STRING_IDS_SIZE = 0x38
OFF_STRING_IDS_OFF = 0x3C
OFF_TYPE_IDS_SIZE = 0x40
OFF_TYPE_IDS_OFF = 0x44
OFF_CLASS_DEFS_SIZE = 0x60
OFF_CLASS_DEFS_OFF = 0x64
CLASS_DEF_ITEM_SIZE = 32   # EIGHT uint32 (class_idx, access_flags, ...)


class DexError(Exception):
    """Raised for any DEX/APK structural problem; callers must fail closed."""


def _u32(data, offset, endian):
    if offset < 0 or offset + 4 > len(data):
        raise DexError(f"uint32 at offset {offset} is outside DEX ({len(data)} bytes)")
    return struct.unpack_from(endian + "I", data, offset)[0]


def _uleb128(data, offset):
    """Decode a uleb128 at offset; return (value, offset_after)."""
    value = 0
    shift = 0
    for _ in range(5):  # 32-bit values: at most five 7-bit groups
        if offset >= len(data):
            raise DexError(f"truncated uleb128 at offset {offset}")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7
    raise DexError(f"malformed uleb128 at offset {offset} (more than 5 bytes)")


def _read_table(data, endian, size_off, off_off, entry_size, label):
    size = _u32(data, size_off, endian)
    offset = _u32(data, off_off, endian)
    if size == 0:
        return size, offset
    if offset < HEADER_SIZE or offset >= len(data):
        raise DexError(f"{label} offset {offset} outside DEX ({len(data)} bytes)")
    end = offset + size * entry_size
    if end > len(data):
        raise DexError(f"{label} table out of bounds: offset {offset} + "
                       f"{size} * {entry_size} > {len(data)}")
    return size, offset


def _string_at(data, endian, string_ids_size, string_ids_off, index):
    """Read string_ids[index] -> string_data_item, skipping its uleb128 size."""
    if index >= string_ids_size:
        raise DexError(f"string_idx {index} out of range (string_ids_size={string_ids_size})")
    string_off = _u32(data, string_ids_off + 4 * index, endian)
    if string_off < HEADER_SIZE or string_off >= len(data):
        raise DexError(f"string_data_off {string_off} for string_idx {index} outside DEX")
    utf16_size, payload_start = _uleb128(data, string_off)
    if utf16_size > len(data):
        raise DexError(f"string_idx {index} utf16_size {utf16_size} exceeds DEX length")
    # MUTF-8 has no embedded literal NUL (U+0000 is encoded C0 80), so the
    # first NUL after the uleb128 prefix terminates the string. ASCII
    # descriptors decode identically to UTF-8; be tolerant elsewhere.
    end = data.find(b"\x00", payload_start)
    if end < 0:
        raise DexError(f"unterminated string_data_item for string_idx {index}")
    return data[payload_start:end].decode("utf-8", errors="replace")


def parse_class_def_descriptors(dex_bytes):
    """Validate a DEX and return one descriptor per class_def_item.

    The result is ordered exactly like class_defs: repeated descriptors are
    kept, so callers can count class_defs for a given descriptor.
    """
    if not isinstance(dex_bytes, (bytes, bytearray)):
        raise DexError("DEX payload is not bytes")
    data = bytes(dex_bytes)
    if len(data) < HEADER_SIZE:
        raise DexError(f"truncated DEX: {len(data)} bytes < {HEADER_SIZE}-byte header")
    if data[:4] != b"dex\n" or data[7:8] != b"\x00":
        raise DexError(f"invalid DEX magic {data[:8]!r}")

    # The endian tag bytes are 78 56 34 12 in a little-endian DEX and
    # 12 34 56 78 in a big-endian one. Read it little-endian first:
    # 0x12345678 -> little tables, 0x78563412 -> big tables.
    tag_little = struct.unpack_from("<I", data, OFF_ENDIAN_TAG)[0]
    if tag_little == ENDIAN_LITTLE:
        endian = "<"
    elif tag_little == ENDIAN_BIG:
        endian = ">"
    else:
        raise DexError(f"invalid DEX endian_tag 0x{tag_little:08x} "
                       "(expected 0x12345678 little or 0x78563412 big)")

    header_size = _u32(data, OFF_HEADER_SIZE, endian)
    if header_size != HEADER_SIZE:
        raise DexError(f"unsupported DEX header_size 0x{header_size:x} (expected 0x70)")
    file_size = _u32(data, OFF_FILE_SIZE, endian)
    if file_size < HEADER_SIZE or file_size > len(data):
        raise DexError(f"DEX file_size {file_size} outside [{HEADER_SIZE}, {len(data)}]")

    string_size, string_off = _read_table(data, endian, OFF_STRING_IDS_SIZE,
                                          OFF_STRING_IDS_OFF, 4, "string_ids")
    type_size, type_off = _read_table(data, endian, OFF_TYPE_IDS_SIZE,
                                      OFF_TYPE_IDS_OFF, 4, "type_ids")
    class_size, class_off = _read_table(data, endian, OFF_CLASS_DEFS_SIZE,
                                        OFF_CLASS_DEFS_OFF, CLASS_DEF_ITEM_SIZE,
                                        "class_defs")
    # Issue #67: a descriptor string/type reference with no class_def is NOT
    # a class. Reject the whole DEX rather than reporting a count of 0.
    #
    # Deliberate fail-closed tradeoff: ANY dex with class_defs_size=0 fails the
    # gate — not only the dex that happens to hold the requested descriptor.
    # "Only inspect the target-bearing dex" was considered and rejected: it
    # would let an empty/malformed sibling dex slip through unchecked, which is
    # exactly the kind of silent degradation this gate exists to prevent.
    # Empirical baseline from review mutation testing: the debug APK's six
    # dexes carry 3629/122/89/49/571/1 class_defs and the release APK's single
    # dex carries 4094 — no empty dex today, so this rule has produced no false
    # negatives. If a future R8/D8 configuration emits a legitimately empty
    # classes*.dex, RE-EVALUATE this rule explicitly; do not silently narrow it
    # to the target-bearing dex.
    if class_size == 0:
        raise DexError("class_defs_size=0 (descriptor strings/type_ids are not class_defs)")

    descriptors = []
    type_cache = {}
    for i in range(class_size):
        base = class_off + CLASS_DEF_ITEM_SIZE * i
        class_idx = _u32(data, base, endian)  # first uint32 of class_def_item
        if class_idx >= type_size:
            raise DexError(f"class_def[{i}] class_idx {class_idx} out of range "
                           f"(type_ids_size={type_size})")
        if class_idx in type_cache:
            descriptor = type_cache[class_idx]
        else:
            descriptor_idx = _u32(data, type_off + 4 * class_idx, endian)
            descriptor = _string_at(data, endian, string_size, string_off, descriptor_idx)
            type_cache[class_idx] = descriptor
        descriptors.append(descriptor)
    return descriptors


def count_class_defs(dex_bytes, descriptor):
    """Number of class_def_item entries in one DEX with exactly this descriptor."""
    return parse_class_def_descriptors(dex_bytes).count(descriptor)


def iter_classes_dex(apk_path):
    """Yield (entry_name, dex_bytes) for each root-level classes*.dex in an APK."""
    try:
        with zipfile.ZipFile(apk_path) as zf:
            names = [info.filename for info in zf.infolist()
                     if DEX_NAME_RE.fullmatch(info.filename)]
            # Deterministic order: the primary DEX first, then classes2/3/...
            names.sort(key=lambda n: (n != "classes.dex", n))
            for name in names:
                try:
                    yield name, zf.read(name)
                except (OSError, RuntimeError, zipfile.BadZipFile) as exc:
                    raise DexError(f"unable to read {name} from {apk_path}: {exc}")
    except (OSError, zipfile.BadZipFile, zipfile.LargeZipFile) as exc:
        raise DexError(f"unable to open APK {apk_path}: {exc}")


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if len(argv) < 2:
        print("::error::usage: check-dex-classdefs.py <apk> <descriptor> [descriptor ...]",
              file=sys.stderr)
        return 1

    apk, descriptors = argv[0], argv[1:]
    try:
        dex_entries = list(iter_classes_dex(apk))
    except DexError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1
    if not dex_entries:
        print(f"::error::no classes*.dex entries in {apk}", file=sys.stderr)
        return 1

    counts = {descriptor: 0 for descriptor in descriptors}
    try:
        for name, payload in dex_entries:
            definitions = parse_class_def_descriptors(payload)
            for descriptor in descriptors:
                count = definitions.count(descriptor)
                if count:
                    counts[descriptor] += count
                    print(f"FOUND class_def {descriptor} in {name} (count={count})")
    except DexError as exc:
        print(f"::error::{exc} in {apk}", file=sys.stderr)
        return 1

    missing = [descriptor for descriptor in descriptors if counts[descriptor] == 0]
    if missing:
        for descriptor in missing:
            print(f"::error::MISSING class_def {descriptor} in {apk} "
                  "(no class_def in any classes*.dex)", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
