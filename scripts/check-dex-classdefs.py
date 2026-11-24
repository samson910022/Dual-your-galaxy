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
# Reads every CANONICAL classes*.dex entry from the APK zip: exactly one
# classes.dex plus contiguous classes2.dex, classes3.dex, ... Non-canonical
# variants (classes0.dex, classes1.dex, classes01.dex, classes002.dex, ...) are
# rejected instead of being counted or silently ignored — an attacker must not
# be able to hide a DEX from the gate by renaming it. Tables are read via the
# ZipInfo object, never by member-name string, so duplicate ZIP members fail
# closed too. parse_class_def_descriptors resolves
# class_idx -> type_ids[class_idx].descriptor_idx -> MUTF-8 string and prints
#   FOUND class_def <descriptor> in <dex> (count=<n>)
# for every descriptor present in every DEX. Every requested descriptor must
# have at least one class_def, otherwise the checker prints one
#   ::error::MISSING class_def <descriptor> ...
# per missing descriptor and exits 1.
#
# Fail closed (::error:: + exit 1) on: unreadable/corrupt APK, missing or
# duplicated primary DEX, non-canonical or duplicated DEX member names, a gap
# in secondary DEX numbering, unreadable members, truncated DEX, bad magic or
# unsupported version, wrong file_size/header_size/endian_tag, out-of-range or
# misaligned/overlapping tables, malformed data section/MUTF-8, duplicate
# class_idx or descriptor, and class_defs_size=0. There is deliberately NO
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
PRIMARY_DEX = "classes.dex"
SECONDARY_DEX_RE = re.compile(r"classes(1[0-9]+|[2-9][0-9]*)\.dex")
ANY_CLASSES_DEX_RE = re.compile(r"classes[0-9]*\.dex")
DEX_VERSIONS = frozenset(f"{version:03d}".encode("ascii") for version in range(35, 41))

# DEX header field offsets (byte positions).
OFF_FILE_SIZE = 0x20
OFF_HEADER_SIZE = 0x24
OFF_ENDIAN_TAG = 0x28
OFF_MAP_OFF = 0x34
OFF_STRING_IDS_SIZE = 0x38
OFF_STRING_IDS_OFF = 0x3C
OFF_TYPE_IDS_SIZE = 0x40
OFF_TYPE_IDS_OFF = 0x44
OFF_CLASS_DEFS_SIZE = 0x60
OFF_CLASS_DEFS_OFF = 0x64
OFF_DATA_SIZE = 0x68
OFF_DATA_OFF = 0x6C
CLASS_DEF_ITEM_SIZE = 32   # EIGHT uint32 (class_idx, access_flags, ...)


class DexError(Exception):
    """Raised for any DEX/APK structural problem; callers must fail closed."""


def _u32(data, offset, endian, file_size):
    if offset < 0 or offset + 4 > file_size:
        raise DexError(f"uint32 at offset {offset} is outside DEX file_size {file_size}")
    return struct.unpack_from(endian + "I", data, offset)[0]


def _uleb128(data, offset, limit, label):
    """Decode a bounded uleb128 at offset; return (value, offset_after)."""
    value = 0
    shift = 0
    for _ in range(5):  # 32-bit values: at most five 7-bit groups
        if offset >= limit:
            raise DexError(f"truncated uleb128 for {label} at offset {offset} "
                           f"(limit {limit})")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7
    raise DexError(f"malformed uleb128 for {label} at offset {offset} "
                   "(more than 5 bytes)")


def _read_table(data, file_size, endian, size_off, off_off, entry_size, label, regions):
    """Validate one DEX table header; record its byte range for overlap checks."""
    size = _u32(data, size_off, endian, file_size)
    offset = _u32(data, off_off, endian, file_size)
    if size == 0:
        return size, offset
    if offset < HEADER_SIZE or offset >= file_size:
        raise DexError(f"{label} offset {offset} outside DEX "
                       f"[{HEADER_SIZE}, {file_size})")
    if offset % 4:
        raise DexError(f"misaligned {label} offset {offset} (must be 4-byte aligned)")
    end = offset + size * entry_size
    if end > file_size:
        raise DexError(f"{label} table out of bounds: offset {offset} + "
                       f"{size} * {entry_size} > file_size {file_size}")
    for other_label, other_start, other_end in regions:
        if offset < other_end and other_start < end:
            raise DexError(f"overlapping DEX tables: {label} [{offset}, {end}) "
                           f"overlaps {other_label} [{other_start}, {other_end})")
    regions.append((label, offset, end))
    return size, offset


def _mutf8_encode(text):
    """Re-encode a decoded MUTF-8 string; used to reject non-canonical encodings."""
    out = bytearray()
    for char in text:
        value = ord(char)
        if value == 0:
            out += b"\xc0\x80"
        elif value < 0x80:
            out.append(value)
        elif value < 0x800:
            out += bytes([0xC0 | (value >> 6), 0x80 | (value & 0x3F)])
        else:
            out += bytes([0xE0 | (value >> 12),
                          0x80 | ((value >> 6) & 0x3F),
                          0x80 | (value & 0x3F)])
    return bytes(out)


def _decode_mutf8(data, start, limit, index):
    """Decode a MUTF-8 string_data_item payload up to its NUL terminator.

    Returns (text, raw_payload, offset_after_terminator). Every decoded 3-byte
    sequence stays one UTF-16 code unit (including surrogate pairs), so
    len(text) is exactly the UTF-16 code-unit count required by the header's
    utf16_size. Invalid, overlong, 4-byte and truncated sequences raise.
    """
    units = []
    offset = start
    while True:
        if offset >= limit:
            raise DexError(f"unterminated string_data_item for string_idx {index} "
                           f"(no NUL before data section end {limit})")
        first = data[offset]
        if first == 0x00:
            raw = bytes(data[start:offset])
            return "".join(units), raw, offset + 1
        if 0x01 <= first <= 0x7F:
            units.append(chr(first))
            offset += 1
            continue
        if 0xC0 <= first <= 0xDF:
            if offset + 1 >= limit:
                raise DexError(f"truncated MUTF-8 sequence for string_idx {index} "
                               f"at offset {offset}")
            second = data[offset + 1]
            if second & 0xC0 != 0x80:
                raise DexError(f"invalid MUTF-8 continuation byte for string_idx {index} "
                               f"at offset {offset + 1}")
            value = ((first & 0x1F) << 6) | (second & 0x3F)
            # C0 80 is MUTF-8's unique encoding of U+0000; any other value
            # below 0x80 in two bytes is an illegal overlong encoding.
            if value < 0x80 and (value != 0 or (first, second) != (0xC0, 0x80)):
                raise DexError(f"overlong MUTF-8 sequence for string_idx {index} "
                               f"at offset {offset}")
            units.append(chr(value))
            offset += 2
            continue
        if 0xE0 <= first <= 0xEF:
            if offset + 2 >= limit:
                raise DexError(f"truncated MUTF-8 sequence for string_idx {index} "
                               f"at offset {offset}")
            second, third = data[offset + 1], data[offset + 2]
            if second & 0xC0 != 0x80 or third & 0xC0 != 0x80:
                raise DexError(f"invalid MUTF-8 continuation byte for string_idx {index} "
                               f"at offset {offset}")
            value = ((first & 0x0F) << 12) | ((second & 0x3F) << 6) | (third & 0x3F)
            if value < 0x800:
                raise DexError(f"overlong MUTF-8 sequence for string_idx {index} "
                               f"at offset {offset}")
            # Surrogate code points are kept as separate code units, exactly as
            # MUTF-8 encodes them; they must not be collapsed into one char.
            units.append(chr(value))
            offset += 3
            continue
        raise DexError(f"invalid MUTF-8 leading byte 0x{first:02x} for string_idx {index} "
                       f"at offset {offset}")


def _string_at(data, file_size, endian, data_off, data_end,
               string_ids_size, string_ids_off, index):
    """Resolve string_ids[index] to a fully validated MUTF-8 string."""
    if index >= string_ids_size:
        raise DexError(f"string_idx {index} out of range (string_ids_size={string_ids_size})")
    string_off = _u32(data, string_ids_off + 4 * index, endian, file_size)
    if not data_off <= string_off < data_end:
        raise DexError(f"string_data_off {string_off} for string_idx {index} "
                       f"outside data section [{data_off}, {data_end})")
    utf16_size, payload_start = _uleb128(data, string_off, data_end,
                                         f"string_idx {index}")
    text, raw, _ = _decode_mutf8(data, payload_start, data_end, index)
    if len(text) != utf16_size:
        raise DexError(f"utf16_size {utf16_size} for string_idx {index} != "
                       f"{len(text)} actual UTF-16 code units")
    if _mutf8_encode(text) != raw:
        raise DexError(f"non-canonical MUTF-8 bytes for string_idx {index}")
    return text


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
    version = data[4:7]
    if version not in DEX_VERSIONS:
        raise DexError(f"invalid DEX magic version {version!r} "
                       "(expected 035..040, NUL-terminated)")

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

    header_size = _u32(data, OFF_HEADER_SIZE, endian, len(data))
    if header_size != HEADER_SIZE:
        raise DexError(f"unsupported DEX header_size 0x{header_size:x} (expected 0x70)")
    # The header's file_size must describe exactly this member: a short value
    # could hide trailing tables from every bounds check, a long one could make
    # a truncated payload look complete.
    file_size = _u32(data, OFF_FILE_SIZE, endian, len(data))
    if file_size != len(data):
        raise DexError(f"DEX file_size {file_size} != DEX member byte length {len(data)}")

    data_size = _u32(data, OFF_DATA_SIZE, endian, file_size)
    data_off = _u32(data, OFF_DATA_OFF, endian, file_size)
    if data_off < HEADER_SIZE or data_off > file_size:
        raise DexError(f"data_off {data_off} outside DEX [{HEADER_SIZE}, {file_size}]")
    if data_size > file_size - data_off:
        raise DexError(f"data_size {data_size} at data_off {data_off} exceeds "
                       f"file_size {file_size}")
    if data_off % 4:
        raise DexError(f"misaligned data_off {data_off} (must be 4-byte aligned)")
    data_end = data_off + data_size
    map_off = _u32(data, OFF_MAP_OFF, endian, file_size)
    if map_off:
        if map_off < HEADER_SIZE or map_off >= file_size:
            raise DexError(f"map_off {map_off} outside DEX [{HEADER_SIZE}, {file_size})")
        if map_off % 4:
            raise DexError(f"misaligned map_off {map_off} (must be 4-byte aligned)")

    regions = []
    string_size, string_off = _read_table(data, file_size, endian, OFF_STRING_IDS_SIZE,
                                          OFF_STRING_IDS_OFF, 4, "string_ids", regions)
    type_size, type_off = _read_table(data, file_size, endian, OFF_TYPE_IDS_SIZE,
                                      OFF_TYPE_IDS_OFF, 4, "type_ids", regions)
    class_size, class_off = _read_table(data, file_size, endian, OFF_CLASS_DEFS_SIZE,
                                        OFF_CLASS_DEFS_OFF, CLASS_DEF_ITEM_SIZE,
                                        "class_defs", regions)
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
    seen_idx = set()
    seen_descriptor = {}
    for i in range(class_size):
        base = class_off + CLASS_DEF_ITEM_SIZE * i
        class_idx = _u32(data, base, endian, file_size)  # first uint32 of class_def_item
        if class_idx in seen_idx:
            raise DexError(f"duplicate class_def class_idx {class_idx} at class_def[{i}]")
        seen_idx.add(class_idx)
        if class_idx >= type_size:
            raise DexError(f"class_def[{i}] class_idx {class_idx} out of range "
                           f"(type_ids_size={type_size})")
        descriptor_idx = _u32(data, type_off + 4 * class_idx, endian, file_size)
        descriptor = _string_at(data, file_size, endian, data_off, data_end,
                                string_size, string_off, descriptor_idx)
        if descriptor in seen_descriptor:
            raise DexError(f"duplicate class_def descriptor {descriptor} at class_def[{i}] "
                           f"(also class_def[{seen_descriptor[descriptor]}])")
        seen_descriptor[descriptor] = i
        descriptors.append(descriptor)
    return descriptors


def count_class_defs(dex_bytes, descriptor):
    """Number of class_def_item entries in one DEX with exactly this descriptor."""
    return parse_class_def_descriptors(dex_bytes).count(descriptor)


def iter_classes_dex(apk_path):
    """Yield (entry_name, dex_bytes) for canonical root-level classes*.dex entries.

    Only `classes.dex` (exactly one) and contiguous `classes2.dex`,
    `classes3.dex`, ... are accepted. Any other `classes[0-9]*.dex` name raises
    DexError instead of being silently skipped or counted. Reads go through
    the ZipInfo object so duplicate member names cannot alias each other.
    """
    try:
        with zipfile.ZipFile(apk_path) as zf:
            primaries = []
            secondaries = {}
            seen_names = set()
            for info in zf.infolist():
                name = info.filename
                if not ANY_CLASSES_DEX_RE.fullmatch(name):
                    continue
                if name in seen_names:
                    raise DexError(f"duplicate ZIP member {name!r} in {apk_path}")
                seen_names.add(name)
                if name == PRIMARY_DEX:
                    primaries.append(info)
                    continue
                match = SECONDARY_DEX_RE.fullmatch(name)
                if not match:
                    raise DexError(
                        f"non-canonical DEX entry name {name!r} in {apk_path} "
                        "(expected classes.dex or classes2.dex, classes3.dex, ...)")
                secondaries[int(match.group(1))] = info

            if not primaries:
                raise DexError(f"missing primary {PRIMARY_DEX} in {apk_path}")
            # No len(primaries) > 1 guard: a second exact `classes.dex` name is
            # already rejected by the seen_names duplicate check in the loop
            # above, so primaries can never hold more than one entry here.
            expected = 2
            for number in sorted(secondaries):
                if number != expected:
                    raise DexError(f"non-contiguous secondary DEX numbering in {apk_path}: "
                                   f"missing classes{expected}.dex before classes{number}.dex")
                expected += 1

            ordered = [primaries[0]] + [secondaries[number] for number in sorted(secondaries)]
            for info in ordered:
                try:
                    yield info.filename, zf.read(info)
                except DexError:
                    raise
                except Exception as exc:
                    raise DexError(f"unable to read {info.filename} from {apk_path}: {exc}") from exc
    except DexError:
        raise
    except Exception as exc:
        raise DexError(f"unable to open APK {apk_path}: {exc}") from exc


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

    counts = {descriptor: 0 for descriptor in descriptors}
    defined_in = {}  # descriptor -> DEX entry name (cross-DEX uniqueness)
    try:
        for name, payload in dex_entries:
            definitions = parse_class_def_descriptors(payload)
            for descriptor in definitions:
                previous = defined_in.get(descriptor)
                if previous is not None and previous != name:
                    print(f"::error::duplicate class_def descriptor {descriptor} "
                          f"defined in multiple DEX entries: {previous} and {name}",
                          file=sys.stderr)
                    return 1
                defined_in[descriptor] = name
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
