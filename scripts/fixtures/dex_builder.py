#!/usr/bin/env python3
"""Minimal, parser-specific synthetic DEX/ZIP fixture builder.

This is NOT an ART-verifiable DEX. It emits only the header fields and tables
that scripts/check-dex-classdefs.py reads (magic, file_size, header_size,
endian_tag, string_ids, type_ids, class_defs, data_size/data_off, plus
string_data_item blobs). There are deliberately no checksums, no map/list,
no code, and no other sections. It exists so the class_def gate can be tested
offline with pure Python 3 stdlib — no Android SDK, no network, no checked-in
binary fixtures.

Importable API:
  build_dex(strings, types=None, class_idxs=None, **mutations) -> bytes
  write_apk(path, members) -> None            # members: [(name, bytes|str), ...]
  xposed_members(...) -> list[(name, bytes)]   # integration APK metadata
  build_all(outdir) -> list[str]               # the standard fixture set
  uleb128(value) -> bytes
  mutf8_encode(text) -> bytes

CLI:
  python3 scripts/fixtures/dex_builder.py <outdir>

The CLI writes both bare .dex files and fake .apk ZIPs into <outdir> (the
latter with canonical classes.dex / classes2.dex names except in the dedicated
non-canonical negative fixtures). Integration APKs carry the xposed metadata
members expected by scripts/verify-release-artifacts.sh.

Mutation knobs (all keyword-only) let callers produce structurally invalid
DEXes used by the fail-closed tests:
  version / magic        magic bytes (default b"035" / b"dex\\n035\\0")
  big / endian_tag       byte-swapped tables / raw tag override
  header_size            override header_size field
  file_size              override file_size field (NOT the real byte length)
  string_ids_off, type_ids_off, class_defs_off, data_off, data_size, map_off
  class_defs_size        override class_defs_size field
  utf16_sizes            {string_idx: declared utf16_size}
  uleb_bytes             {string_idx: raw bytes replacing the uleb128 prefix}
  string_payloads        {string_idx: raw MUTF-8 bytes replacing the payload}
  string_data_offs       {string_idx: absolute offset, or "data_end[-N]"}
  omit_terminators       {string_idx, ...} drop the NUL byte
  trailer / pad_data     append raw bytes to the data section / skip 4-byte pad
  extra_strings, extra_types
"""
import os
import re
import struct
import sys
import warnings
import zipfile

HEADER_SIZE = 112
ENDIAN_LITTLE = 0x12345678
ENDIAN_BIG = 0x78563412
CLASS_DEF_ITEM_SIZE = 32

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

# Descriptors used across the dex-classdef and release-artifact self-tests.
TARGET = "Lrikka/shizuku/ShizukuProvider;"
OTHER = "Lcom/galaxydual/hook/DualHook;"
MISSING = "Lcom/example/NotPresent;"

# Keep in sync with the versionName stubbed by the integration tests.
VERSION_NAME = "0.1.0-beta.3"

MODULE_ID = "io.github.samson910022.galaxydualplus"
MODULE_PROP = (
    f"id={MODULE_ID}\n"
    f"version={VERSION_NAME}\n"
    "exceptionMode=protective\n"
).encode("utf-8")
JAVA_INIT_LIST = b"com.galaxydual.hook.DualHook\n"
SCOPE_LIST = b"system\ncom.samsung.android.da.daagent\n"
# Placeholder only: the integration tests stub aapt2, so real binary manifest
# bytes are not required.
ANDROID_MANIFEST = b"placeholder AndroidManifest.xml (aapt2 is stubbed in tests)\n"

_DATA_END_RE = re.compile(r"data_end(?:-(\d+))?\Z")


def uleb128(value):
    """Encode an unsigned LEB128 integer."""
    if value < 0:
        raise ValueError("uleb128 needs a non-negative integer")
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def mutf8_encode(text):
    """Encode a Python str as MUTF-8, counting surrogate code units separately."""
    out = bytearray()
    for ch in text:
        value = ord(ch)
        if value == 0:
            out += b"\xc0\x80"
        elif value < 0x80:
            out.append(value)
        elif value < 0x800:
            out += bytes([0xC0 | (value >> 6), 0x80 | (value & 0x3F)])
        else:
            out += bytes([
                0xE0 | (value >> 12),
                0x80 | ((value >> 6) & 0x3F),
                0x80 | (value & 0x3F),
            ])
    return bytes(out)


def _align4(value):
    return (value + 3) & ~3


def _as_dict(override):
    return {} if override is None else dict(override)


def layout_offsets(dex_bytes):
    """Return (string_ids_off, type_ids_off, class_defs_off, data_off) from a DEX."""
    return tuple(struct.unpack_from("<I", dex_bytes, off)[0] for off in (
        OFF_STRING_IDS_OFF, OFF_TYPE_IDS_OFF, OFF_CLASS_DEFS_OFF, OFF_DATA_OFF))


def build_dex(strings, types=None, class_idxs=None, *, version=b"035", magic=None,
              big=False, endian_tag=None, header_size=HEADER_SIZE, file_size=None,
              string_ids_off=None, type_ids_off=None, class_defs_off=None,
              data_off=None, data_size=None, map_off=0, class_defs_size=None,
              utf16_sizes=None, uleb_bytes=None, string_payloads=None,
              string_data_offs=None, omit_terminators=None, trailer=b"",
              pad_data=True, extra_strings=(), extra_types=()):
    """Build a minimal DEX byte string with optional structural mutations.

    strings:    descriptor strings in string_ids (one string_data_item each).
    types:      string indices for type_ids (default: identity over strings).
    class_idxs: type indices for the class_def_items (default: identity).
    """
    strings = list(strings) + list(extra_strings)
    types = list(types) if types is not None else list(range(len(strings)))
    types += list(extra_types)
    class_idxs = list(class_idxs) if class_idxs is not None else list(range(len(types)))

    n_str, n_types, n_defs = len(strings), len(types), len(class_idxs)
    endian = ">" if big else "<"

    str_off = HEADER_SIZE if string_ids_off is None else string_ids_off
    type_off = (_align4(str_off + 4 * n_str) if type_ids_off is None
                else type_ids_off)
    defs_off = (_align4(type_off + 4 * n_types) if class_defs_off is None
                else class_defs_off)
    d_off = (_align4(defs_off + CLASS_DEF_ITEM_SIZE * n_defs) if data_off is None
             else data_off)

    utf16_overrides = _as_dict(utf16_sizes)
    uleb_overrides = _as_dict(uleb_bytes)
    payload_overrides = _as_dict(string_payloads)
    off_overrides = _as_dict(string_data_offs)
    no_terminators = set(omit_terminators or ())

    data = bytearray()
    computed_offs = []
    for index, text in enumerate(strings):
        computed_offs.append(d_off + len(data))
        payload = payload_overrides.get(index)
        if payload is None:
            payload = mutf8_encode(text)
        elif isinstance(payload, str):
            payload = payload.encode("utf-8")
        declared = utf16_overrides.get(index)
        if declared is None:
            declared = len(text)  # descriptors are ASCII: 1 code unit per char
        prefix = uleb_overrides.get(index)
        if prefix is None:
            prefix = uleb128(declared)
        elif isinstance(prefix, str):
            prefix = prefix.encode("latin-1")
        data += prefix + payload
        if index not in no_terminators:
            data += b"\x00"
    data += trailer
    if pad_data:
        while len(data) % 4:
            data += b"\x00"

    real_size = d_off + len(data)
    declared_size = real_size if file_size is None else file_size
    declared_data_size = len(data) if data_size is None else data_size

    # Resolve string_data_off overrides after the data section is final so
    # symbolic "data_end" / "data_end-N" values can be used by mutations.
    string_offs = []
    for index, computed in enumerate(computed_offs):
        override = off_overrides.get(index)
        if override is None:
            string_offs.append(computed)
        elif isinstance(override, str):
            match = _DATA_END_RE.fullmatch(override)
            if not match:
                raise ValueError(f"bad string_data_off expression {override!r}")
            string_offs.append(real_size - int(match.group(1) or 0))
        else:
            string_offs.append(override)

    hdr = bytearray(HEADER_SIZE)
    hdr[0:8] = magic if magic is not None else b"dex\n" + bytes(version) + b"\x00"
    struct.pack_into(endian + "I", hdr, OFF_FILE_SIZE, declared_size)
    struct.pack_into(endian + "I", hdr, OFF_HEADER_SIZE, header_size)
    tag = endian_tag if endian_tag is not None else (ENDIAN_BIG if big else ENDIAN_LITTLE)
    # The tag bytes are fixed by the format: 78 56 34 12 for a normal DEX,
    # 12 34 56 78 for a byte-swapped one. Emit them little-endian; the reader
    # interprets them as 0x12345678 / 0x78563412 and picks the table order.
    struct.pack_into("<I", hdr, OFF_ENDIAN_TAG, tag)
    struct.pack_into("<I", hdr, OFF_MAP_OFF, map_off)
    struct.pack_into(endian + "II", hdr, OFF_STRING_IDS_SIZE, n_str, str_off)
    struct.pack_into(endian + "II", hdr, OFF_TYPE_IDS_SIZE, n_types, type_off)
    struct.pack_into(endian + "II", hdr, OFF_CLASS_DEFS_SIZE,
                     n_defs if class_defs_size is None else class_defs_size, defs_off)
    struct.pack_into(endian + "II", hdr, OFF_DATA_SIZE, declared_data_size, d_off)

    out = bytearray(hdr)
    out += b"".join(struct.pack(endian + "I", off) for off in string_offs)
    if len(out) != type_off:
        raise ValueError("string_ids table does not end at type_ids_off "
                         f"({len(out)} != {type_off})")
    out += b"".join(struct.pack(endian + "I", idx) for idx in types)
    if len(out) != defs_off:
        raise ValueError("type_ids table does not end at class_defs_off "
                         f"({len(out)} != {defs_off})")
    out += b"".join(struct.pack(endian + "8I", idx, 0, 0, 0, 0, 0, 0, 0)
                    for idx in class_idxs)
    if len(out) != d_off:
        raise ValueError("class_defs table does not end at data_off "
                         f"({len(out)} != {d_off})")
    out += data
    if len(out) != real_size:
        raise ValueError(f"built DEX size {len(out)} != expected {real_size}")
    return bytes(out)


def write_apk(path, members):
    """Write members as a ZIP. Accepts a list of (name, payload) pairs or a dict.

    A list is required for duplicate member names; the duplicate-name
    UserWarning from zipfile is intentionally suppressed for those fixtures.
    """
    pairs = list(members.items()) if isinstance(members, dict) else list(members)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
            for name, payload in pairs:
                if isinstance(payload, str):
                    payload = payload.encode("utf-8")
                zf.writestr(name, payload)


def xposed_members(module_id=MODULE_ID, version=VERSION_NAME):
    """The xposed metadata members expected by verify-release-artifacts.sh."""
    return [
        ("META-INF/xposed/module.prop",
         f"id={module_id}\nversion={version}\nexceptionMode=protective\n".encode("utf-8")),
        ("META-INF/xposed/java_init.list", JAVA_INIT_LIST),
        ("META-INF/xposed/scope.list", SCOPE_LIST),
        ("AndroidManifest.xml", ANDROID_MANIFEST),
    ]


def _write_dex(outdir, name, payload):
    with open(os.path.join(outdir, name), "wb") as handle:
        handle.write(payload)
    return payload


def build_all(outdir):
    """Generate the standard fixture set; return the list of written names."""
    os.makedirs(outdir, exist_ok=True)
    written = []

    def dex(name, *args, **kwargs):
        written.append(name)
        return _write_dex(outdir, name, build_dex(*args, **kwargs))

    def apk(name, members):
        written.append(name)
        write_apk(os.path.join(outdir, name), members)

    # --- positive / neutral DEXes and APKs ---------------------------------
    positive = dex("positive.dex", [TARGET])
    negative = dex("negative.dex", [TARGET], class_idxs=[])
    both = dex("both.dex", [TARGET, OTHER])
    big_endian = dex("big-endian.dex", [TARGET], big=True)
    inner_only = dex("inner-only.dex",
                     [TARGET, TARGET[:-1] + "$Inner;", OTHER, OTHER[:-1] + "$Companion;"],
                     class_idxs=[1, 3])
    missing = dex("missing.dex", [OTHER])
    dup_classidx = dex("dup-classidx.dex", [TARGET, OTHER], class_idxs=[0, 0])
    dup_descriptor = dex("dup-descriptor-within.dex", [TARGET], types=[0, 0],
                         class_idxs=[0, 1])
    bad_index = dex("bad-index.dex", [TARGET], class_idxs=[999])
    bad_magic = dex("bad-magic.dex", [TARGET], version=b"xyz")
    bad_version = dex("bad-version.dex", [TARGET], version=b"041")
    bad_header = dex("bad-header-size.dex", [TARGET], header_size=0x71)
    file_small = dex("file-size-small.dex", [TARGET], file_size=len(positive) - 1)
    file_large = dex("file-size-large.dex", [TARGET], file_size=len(positive) + 1)
    # Keep the other table offsets at their baseline values: only the named
    # header field is mutated, so the pinned error is unambiguous.
    base_str, base_type, base_defs, base_data = layout_offsets(positive)
    fixed_layout = dict(type_ids_off=base_type, class_defs_off=base_defs,
                        data_off=base_data)
    bad_string_off = dex("bad-string-ids-offset.dex", [TARGET],
                         string_ids_off=len(positive) + 4096, **fixed_layout)
    misaligned = dex("misaligned-offset.dex", [TARGET],
                     string_ids_off=base_str + 1, **fixed_layout)
    malformed_uleb = dex("malformed-uleb.dex", [TARGET],
                         uleb_bytes={0: b"\x80\x80\x80\x80\x80"})
    # The prefix sits on the last byte of a non-padded data section: reading a
    # 6th byte crosses `data_end`, producing "truncated uleb128".
    truncated_uleb = dex("truncated-uleb.dex", [TARGET], trailer=b"\x80",
                         pad_data=False, string_data_offs={0: "data_end-1"})
    string_outside = dex("string-off-outside-data.dex", [TARGET],
                         string_data_offs={0: HEADER_SIZE})
    unterminated = dex("unterminated-string.dex", [TARGET], pad_data=False,
                       omit_terminators={0})
    utf16_zero = dex("utf16-size-zero.dex", [TARGET], utf16_sizes={0: 0})
    utf16_large = dex("utf16-size-large.dex", [TARGET],
                      utf16_sizes={0: len(TARGET) + 8})
    # Raw invalid MUTF-8 payloads. utf16_sizes is the would-be decoded UTF-16
    # code-unit count, so the MUTF-8-specific guard fires before the utf16_size
    # equality check. The truncated case omits the NUL and padding so the
    # incomplete 3-byte lead sits exactly at data_end (otherwise the following
    # terminator would look like a bad continuation byte instead).
    mutf8_leading = dex("mutf8-leading.dex", [TARGET],
                        string_payloads={0: b"\x80"}, utf16_sizes={0: 1})
    mutf8_overlong = dex("mutf8-overlong.dex", [TARGET],
                         string_payloads={0: b"\xc0\xaf"}, utf16_sizes={0: 1})
    mutf8_truncated = dex("mutf8-truncated.dex", [TARGET],
                          string_payloads={0: b"\xe2\x82"}, utf16_sizes={0: 1},
                          omit_terminators={0}, pad_data=False)
    # Structural mutation fixtures: a bad map_off, an overlong data_size, an
    # overlapping string_ids/type_ids pair, and a class_defs table whose
    # declared size runs past file_size. The overlap is declared via
    # string_ids_off (the builder lays tables out contiguously, so a shifted
    # declared start is the way to make two table ranges intersect).
    bad_map_off = dex("bad-map-off.dex", [TARGET], map_off=8)
    bad_data_size = dex("bad-data-size.dex", [TARGET], data_size=len(positive))
    two_offsets = layout_offsets(build_dex([TARGET, OTHER]))
    overlapping_tables = dex("overlapping-tables.dex", [TARGET, OTHER],
                             string_ids_off=two_offsets[1] - 4,
                             type_ids_off=two_offsets[1],
                             class_defs_off=two_offsets[2],
                             data_off=two_offsets[3])
    table_out_of_bounds = dex("table-out-of-bounds.dex", [TARGET],
                              class_defs_size=100)
    empty_sibling = dex("empty-sibling.dex", [TARGET], class_idxs=[])
    bad_endian = bytearray(positive)
    struct.pack_into("<I", bad_endian, OFF_ENDIAN_TAG, 0xDEADBEEF)
    bad_endian = _write_dex(outdir, "bad-endian.dex", bytes(bad_endian))
    written.append("bad-endian.dex")
    truncated_header = _write_dex(outdir, "truncated-header.dex", positive[:50])
    written.append("truncated-header.dex")

    apk("positive.apk", [("classes.dex", positive)])
    apk("negative.apk", [("classes.dex", negative)])
    apk("missing.apk", [("classes.dex", missing)])
    apk("inner-only.apk", [("classes.dex", inner_only)])
    apk("both.apk", [("classes.dex", both)])
    apk("big-endian.apk", [("classes.dex", big_endian)])
    apk("truncated.apk", [("classes.dex", truncated_header)])
    apk("bad-endian.apk", [("classes.dex", bad_endian)])
    apk("bad-index.apk", [("classes.dex", bad_index)])
    apk("no-dex.apk", [("META-INF/xposed/module.prop", MODULE_PROP)])
    with open(os.path.join(outdir, "not-a-zip.apk"), "wb") as handle:
        handle.write(b"this is not a zip archive")
    written.append("not-a-zip.apk")

    # Multi-DEX: target only in the secondary entry.
    multi_primary = dex("multi-primary.dex", [OTHER])
    multi_secondary = dex("multi-secondary.dex", [TARGET])
    apk("multi.apk", [("classes.dex", multi_primary),
                      ("classes2.dex", multi_secondary)])

    # --- multi-dex enumeration / structural mutation negatives -------------
    apk("dup-classidx.apk", [("classes.dex", dup_classidx)])
    apk("dup-descriptor-within.apk", [("classes.dex", dup_descriptor)])
    # Both entries define TARGET: cross-DEX duplicate descriptor.
    apk("dup-descriptor-across.apk", [("classes.dex", positive),
                                      ("classes2.dex", positive)])
    apk("empty-sibling.apk", [("classes.dex", positive),
                              ("classes2.dex", empty_sibling)])
    apk("no-primary.apk", [("classes2.dex", positive)])
    apk("numbering-gap.apk", [("classes.dex", positive),
                              ("classes3.dex", positive)])
    for name in ("classes0", "classes1", "classes01"):
        apk(f"noncanonical-{name}.apk", [(name + ".dex", positive)])
    # A valid primary sibling must NOT make a non-canonical name acceptable:
    # this pins the name check itself rather than the missing-primary guard.
    apk("noncanonical-with-primary.apk", [("classes.dex", positive),
                                          ("classes1.dex", positive)])
    dup_member_other = dex("dup-member-other.dex", [OTHER])
    apk("duplicate-member.apk", [("classes.dex", positive),
                                 ("classes.dex", dup_member_other)])
    apk("bad-magic.apk", [("classes.dex", bad_magic)])
    apk("bad-version.apk", [("classes.dex", bad_version)])
    apk("file-size-small.apk", [("classes.dex", file_small)])
    apk("file-size-large.apk", [("classes.dex", file_large)])
    apk("bad-header-size.apk", [("classes.dex", bad_header)])
    apk("bad-string-ids-offset.apk", [("classes.dex", bad_string_off)])
    apk("misaligned-offset.apk", [("classes.dex", misaligned)])
    apk("malformed-uleb.apk", [("classes.dex", malformed_uleb)])
    apk("truncated-uleb.apk", [("classes.dex", truncated_uleb)])
    apk("string-off-outside-data.apk", [("classes.dex", string_outside)])
    apk("unterminated-string.apk", [("classes.dex", unterminated)])
    apk("utf16-size-zero.apk", [("classes.dex", utf16_zero)])
    apk("utf16-size-large.apk", [("classes.dex", utf16_large)])
    for name, payload in (
        ("mutf8-leading", mutf8_leading),
        ("mutf8-overlong", mutf8_overlong),
        ("mutf8-truncated", mutf8_truncated),
        ("bad-map-off", bad_map_off),
        ("bad-data-size", bad_data_size),
        ("overlapping-tables", overlapping_tables),
        ("table-out-of-bounds", table_out_of_bounds),
    ):
        apk(name + ".apk", [("classes.dex", payload)])

    # --- integration APKs with the xposed metadata members ----------------
    # Both canonical targets present, split across two DEXes.
    apk("integration-positive.apk",
        xposed_members() + [("classes.dex", multi_primary),
                            ("classes2.dex", multi_secondary)])
    # Negative variants isolate each release-artifact gate without changing
    # any of the metadata members.
    for name, dex_payload in (
        ("integration-zero-classdefs", negative),
        ("integration-missing-dualhook", positive),
        ("integration-missing-shizuku", missing),
    ):
        apk(f"{name}.apk", xposed_members() + [("classes.dex", dex_payload)])
    # A gap in secondary DEX numbering (no classes2.dex) with otherwise valid
    # content and full xposed metadata. Both targets are present and no member
    # is duplicated, so the ONLY defect is the name-level multi-dex policy —
    # this proves check-dex-classdefs.py enforces it through the wrapper too.
    apk("integration-numbering-gap.apk",
        xposed_members() + [("classes.dex", multi_primary),
                            ("classes3.dex", multi_secondary)])
    return written


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if len(argv) != 1:
        print("usage: dex_builder.py <outdir>", file=sys.stderr)
        return 2
    names = build_all(argv[0])
    print(f"generated {len(names)} fixtures in {argv[0]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
