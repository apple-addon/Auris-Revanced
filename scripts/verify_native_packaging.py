#!/usr/bin/env python3
"""Verify ARM64 ELF packaging without loading target binaries on the host."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path

from elftools.elf.dynamic import DynamicSection
from elftools.elf.elffile import ELFFile


SYSTEM_LIBRARIES = {
    "libandroid.so",
    "libaaudio.so",
    "libc.so",
    "libdl.so",
    "libEGL.so",
    "libGLESv2.so",
    "libjnigraphics.so",
    "liblog.so",
    "libm.so",
    "libmediandk.so",
    "libOpenSLES.so",
    "libz.so",
}


def audit_elf(name: str, stream, packaged_names: set[str]) -> dict:
    digest = hashlib.sha256()
    data = bytearray()
    while chunk := stream.read(1024 * 1024):
        digest.update(chunk)
        data.extend(chunk)
    elf = ELFFile(io.BytesIO(data))
    loads = [segment for segment in elf.iter_segments() if segment["p_type"] == "PT_LOAD"]
    needed = []
    bind_now = False
    for section in elf.iter_sections():
        if not isinstance(section, DynamicSection):
            continue
        for tag in section.iter_tags():
            if tag.entry.d_tag == "DT_NEEDED":
                needed.append(tag.needed)
            elif tag.entry.d_tag == "DT_BIND_NOW":
                bind_now = True
            elif tag.entry.d_tag == "DT_FLAGS" and tag.entry.d_val & 0x8:
                bind_now = True
            elif tag.entry.d_tag == "DT_FLAGS_1" and tag.entry.d_val & 0x1:
                bind_now = True
    unresolved = sorted(set(needed) - SYSTEM_LIBRARIES - packaged_names)
    return {
        "name": name,
        "sha256": digest.hexdigest(),
        "load_alignments": [hex(segment["p_align"]) for segment in loads],
        "aligned_16k": bool(loads) and all(segment["p_align"] == 0x4000 for segment in loads),
        "gnu_relro": any(segment["p_type"] == "PT_GNU_RELRO" for segment in elf.iter_segments()),
        "bind_now": bind_now,
        "needed": needed,
        "unresolved_needed": unresolved,
        "libstdcpp_needed": "libstdc++.so" in needed,
    }


def audit_directory(root: Path) -> list[dict]:
    paths = sorted(root.glob("app/src/main/jniLibs/arm64-v8a/*.so"))
    paths += sorted(root.glob("app/src/main/assets/Plugins/arm64-v8a/*.so"))
    packaged_names = {path.name for path in root.glob("app/src/main/jniLibs/*/*.so")}
    results = []
    for path in paths:
        with path.open("rb") as stream:
            results.append(audit_elf(str(path.relative_to(root)), stream, packaged_names))
    return results


def audit_apk(path: Path) -> list[dict]:
    with zipfile.ZipFile(path) as apk:
        names = set(apk.namelist())
        packaged_names = {name.rsplit("/", 1)[-1] for name in names if name.startswith("lib/") and name.endswith(".so")}
        targets = sorted(
            name for name in names
            if name.endswith(".so") and (
                name.startswith("lib/arm64-v8a/") or name.startswith("assets/Plugins/arm64-v8a/")
            )
        )
        return [audit_elf(name, apk.open(name), packaged_names) for name in targets]


def main() -> None:
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--project", type=Path)
    source.add_argument("--apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = args.project or args.apk.parent
    results = audit_directory(args.project) if args.project else audit_apk(args.apk)
    failures = []
    for result in results:
        for requirement in ("aligned_16k", "gnu_relro", "bind_now"):
            if not result[requirement]:
                failures.append(f"{result['name']}: {requirement}=false")
        if result["unresolved_needed"]:
            failures.append(f"{result['name']}: unresolved DT_NEEDED={result['unresolved_needed']}")
        if result["libstdcpp_needed"]:
            failures.append(f"{result['name']}: stale libstdc++.so")
    legacy = []
    if args.project:
        legacy += [str(path) for path in args.project.glob("app/src/main/jniLibs/*/libpowerampcore.so")]
        legacy += [str(path) for path in args.project.glob("app/src/main/java/com/maxmpz/**/*")]
    else:
        with zipfile.ZipFile(args.apk) as apk:
            legacy += [name for name in apk.namelist() if name.endswith("/libpowerampcore.so") or "/com/maxmpz/" in name]
    failures += [f"legacy payload: {name}" for name in legacy]

    report = {
        "source": str(args.project or args.apk),
        "arm64_shared_object_count": len(results),
        "results": results,
        "legacy_payloads": legacy,
        "failures": failures,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"ARM64 shared objects: {len(results)}")
    for result in results:
        print(
            f"{result['name']}: align={','.join(result['load_alignments'])} "
            f"RELRO={result['gnu_relro']} NOW={result['bind_now']} "
            f"needed={len(result['needed'])} sha256={result['sha256']}"
        )
    if failures:
        print("FAILURES:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        raise SystemExit(1)
    print("Native packaging verification: PASS")


if __name__ == "__main__":
    main()
