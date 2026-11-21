#!/usr/bin/env python3
"""Yomi iOS artifact verifier.

Validates that a built Yomi.app / Yomi-unsigned.ipa is a real physical-device
iPhoneOS build (not a simulator binary), with the expected bundle identity,
architecture and minimum iOS version. Pure Python stdlib — runs on Windows,
macOS and Linux.

Usage:
  python verify_ipa.py path/to/Yomi.app
  python verify_ipa.py path/to/Yomi-unsigned.ipa

Exit code 0 = all checks pass; 1 = any check fails.
"""
import plistlib
import struct
import sys
import zipfile
from pathlib import Path

EXPECTED_BUNDLE_ID = "app.yomi.reader"
EXPECTED_MIN_OS = (17, 0)
REQUIRED_CPU = "arm64"

# Mach-O constants
MH_MAGIC_64 = 0xFEEDFACF
FAT_MAGIC = 0xCAFEBABE
FAT_MAGIC_64 = 0xCAFEBABF
LC_BUILD_VERSION = 0x32
PLATFORM_IOS = 2
PLATFORM_IOSSIMULATOR = 7
CPU_TYPE_ARM64 = 0x0100000C


def parse_version(packed: int) -> tuple:
    return ((packed >> 16) & 0xFF, (packed >> 8) & 0xFF, packed & 0xFF)


def mach_o_slices(data: bytes) -> list:
    """Returns a list of (cpu_name, platform, minos) per architecture slice."""
    results = []
    if len(data) < 32:
        return results
    (magic,) = struct.unpack_from("<I", data, 0)
    if magic == MH_MAGIC_64:
        results.append(parse_thin(data, 0))
    elif struct.unpack_from(">I", data, 0)[0] in (FAT_MAGIC, FAT_MAGIC_64):
        nfat = struct.unpack_from(">I", data, 4)[0]
        offset = 8
        for _ in range(nfat):
            # fat_arch: cputype, cpusubtype, offset, size, align (big-endian)
            fields = struct.unpack_from(">IIIII", data, offset)
            slice_offset = fields[2]
            results.append(parse_thin(data, slice_offset))
            offset += 20
    return [r for r in results if r]


def parse_thin(data: bytes, base: int):
    (magic,) = struct.unpack_from("<I", data, base)
    if magic != MH_MAGIC_64:
        return None
    cputype, _cpusubtype, _filetype, ncmds, sizeofcmds = struct.unpack_from(
        "<IIIII", data, base + 4
    )
    cpu_name = "arm64" if cputype == CPU_TYPE_ARM64 else f"cpu_{cputype:#x}"
    platform = None
    minos = None
    cursor = base + 32  # sizeof(mach_header_64)
    for _ in range(ncmds):
        if cursor + 8 > len(data):
            break
        cmd, cmdsize = struct.unpack_from("<II", data, cursor)
        if cmd == LC_BUILD_VERSION and cmdsize >= 24:
            platform = struct.unpack_from("<I", data, cursor + 8)[0]
            minos = parse_version(struct.unpack_from("<I", data, cursor + 12)[0])
        cursor += cmdsize
    return (cpu_name, platform, minos)


def check(condition: bool, label: str, failures: list) -> None:
    mark = "PASS" if condition else "FAIL"
    print(f"  [{mark}] {label}")
    if not condition:
        failures.append(label)


def verify_app(app_dir: Path) -> list:
    failures = []
    print(f"Verifying app bundle: {app_dir}")

    info = {}
    info_plist = app_dir / "Info.plist"
    check(info_plist.is_file(), "Info.plist exists", failures)
    if info_plist.is_file():
        with open(info_plist, "rb") as handle:
            info = plistlib.load(handle)
        bundle_id = info.get("CFBundleIdentifier")
        check(
            bundle_id == EXPECTED_BUNDLE_ID,
            f"CFBundleIdentifier == {EXPECTED_BUNDLE_ID} (found: {bundle_id})",
            failures,
        )
        min_os_raw = info.get("MinimumOSVersion", "")
        try:
            min_os = tuple(int(x) for x in str(min_os_raw).split(".")[:2])
        except ValueError:
            min_os = ()
        check(
            min_os >= EXPECTED_MIN_OS,
            f"MinimumOSVersion >= {EXPECTED_MIN_OS[0]}.{EXPECTED_MIN_OS[1]} (found: {min_os_raw})",
            failures,
        )
        check(
            info.get("CFBundleName") in ("Yomi", "$(PRODUCT_NAME)", None)
            or info.get("CFBundleDisplayName") == "Yomi",
            "app display name is Yomi",
            failures,
        )

    # The main executable is CFBundleExecutable (typically "Yomi" inside "Yomi.app")
    executable_name = info.get("CFBundleExecutable") or app_dir.stem
    binary = app_dir / executable_name
    check(binary.is_file(), f"main executable exists ({executable_name})", failures)

    if binary.is_file():
        data = binary.read_bytes()
        slices = mach_o_slices(data)
        check(bool(slices), "Mach-O headers parse", failures)
        for cpu, platform, minos in slices:
            check(cpu == REQUIRED_CPU, f"architecture is {REQUIRED_CPU} (found: {cpu})", failures)
            check(
                platform == PLATFORM_IOS,
                f"platform is iOS device (2), NOT simulator (7) — found: {platform}",
                failures,
            )
            if minos:
                check(
                    minos[:2] >= EXPECTED_MIN_OS,
                    f"LC_BUILD_VERSION minos >= {EXPECTED_MIN_OS[0]}.{EXPECTED_MIN_OS[1]} (found: {minos[0]}.{minos[1]})",
                    failures,
                )

    frameworks = app_dir / "Frameworks"
    if frameworks.is_dir():
        nested = [p for p in frameworks.rglob("*") if p.suffix in (".framework", ".dylib")]
        print(f"  [INFO] embedded frameworks/dylibs: {[p.name for p in nested]}")
        # Informational: free-ID resigning tools handle these, but their
        # presence must be known. YomiKit links statically, so this is empty.
    else:
        print("  [PASS] no embedded Frameworks directory (static linking)")
    return failures


def verify_ipa(ipa_path: Path) -> list:
    failures = []
    print(f"Verifying IPA: {ipa_path}")
    with zipfile.ZipFile(ipa_path) as zf:
        names = zf.namelist()
        check(any(n == "Payload/Yomi.app/" or n.startswith("Payload/Yomi.app/") for n in names),
              "IPA contains Payload/Yomi.app", failures)
        check(
            not any(n.lower().endswith(".dsym") for n in names),
            "no dSYM junk in IPA",
            failures,
        )
        bad = [n for n in names if n.startswith("Payload/") and n.count(".app/") > 1]
        check(not bad, f"no unexpected nested bundles ({bad[:3]})", failures)

        # Extract the app to a temp dir and run the app-level checks
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            zf.extractall(tmp)
            app_dir = Path(tmp) / "Payload" / "Yomi.app"
            if app_dir.is_dir():
                failures.extend(verify_app(app_dir))
            else:
                check(False, "Payload/Yomi.app directory exists in zip", failures)
    return failures


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    target = Path(sys.argv[1])
    if not target.exists():
        print(f"error: {target} does not exist")
        return 2
    if target.suffix.lower() == ".ipa":
        failures = verify_ipa(target)
    elif target.is_dir():
        failures = verify_app(target)
    else:
        print("error: expected a .app directory or .ipa file")
        return 2

    print()
    if failures:
        print(f"RESULT: FAILED ({len(failures)} check(s))")
        return 1
    print("RESULT: ALL CHECKS PASSED — real arm64 iPhoneOS build for physical devices")
    return 0


if __name__ == "__main__":
    sys.exit(main())
