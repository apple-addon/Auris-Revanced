#!/usr/bin/env python3
"""Static ELF/JNI audit for the packaged reference engines.

Requires pyelftools and capstone. The audit never loads an ARM binary on the
host. It reconstructs JNINativeMethod tables from AArch64 RegisterNatives call
sites and compares them with the rebranded smali declarations.
"""

from __future__ import annotations

import argparse
import json
import re
import struct
from collections import defaultdict
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_MEM, ARM64_OP_REG, ARM64_REG_SP
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection


class ElfImage:
    def __init__(self, path: Path):
        self.path = path
        self.stream = path.open("rb")
        self.elf = ELFFile(self.stream)
        self.relocations: dict[int, int] = {}
        self.plt_symbols: dict[int, str] = {}
        for section in self.elf.iter_sections():
            if isinstance(section, RelocationSection):
                for relocation in section.iter_relocations():
                    # R_AARCH64_RELATIVE: runtime pointer is load bias + addend.
                    if relocation["r_info_type"] == 0x403:
                        self.relocations[relocation["r_offset"]] = relocation["r_addend"]
        plt = self.elf.get_section_by_name(".plt")
        rela_plt = self.elf.get_section_by_name(".rela.plt")
        if plt is not None and rela_plt is not None:
            symbols = self.elf.get_section(rela_plt["sh_link"])
            for index, relocation in enumerate(rela_plt.iter_relocations()):
                # AArch64 PLT0 is 32 bytes; each subsequent entry is 16 bytes.
                address = plt["sh_addr"] + 32 + index * 16
                self.plt_symbols[address] = symbols.get_symbol(relocation["r_info_sym"]).name

    def close(self) -> None:
        self.stream.close()

    def raw(self, address: int, size: int) -> bytes | None:
        for section in self.elf.iter_sections():
            if section["sh_type"] == "SHT_NOBITS":
                continue
            start = section["sh_addr"]
            if start <= address and address + size <= start + section["sh_size"]:
                self.stream.seek(section["sh_offset"] + address - start)
                return self.stream.read(size)
        return None

    def pointer(self, address: int) -> int | None:
        if address in self.relocations:
            return self.relocations[address]
        data = self.raw(address, 8)
        return struct.unpack("<Q", data)[0] if data else None

    def cstring(self, address: int | None) -> str | None:
        if not address:
            return None
        data = self.raw(address, 8192)
        if not data:
            return None
        return data.split(b"\0", 1)[0].decode("utf-8", errors="replace")


def register_key(md: Cs, operand) -> str | None:
    if operand.type != ARM64_OP_REG:
        return None
    name = md.reg_name(operand.reg)
    if name == "sp":
        return "sp"
    match = re.fullmatch(r"[wx](\d+)", name)
    return f"x{match.group(1)}" if match else name


def register_native_methods(image: ElfImage) -> list[dict]:
    text = image.elf.get_section_by_name(".text")
    md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    md.detail = True
    md.skipdata = True

    registers: dict[str, int] = {"sp": 0}
    vectors: dict[str, tuple[int | None, int | None]] = {}
    memory: dict[int, int | None] = {}
    vtable_calls: dict[str, int] = {}
    current_class: str | None = None
    results: list[dict] = []

    def address_of(insn, operand_index: int = 1) -> int | None:
        operand = insn.operands[operand_index]
        if operand.type != ARM64_OP_MEM or operand.mem.index:
            return None
        base = md.reg_name(operand.mem.base)
        base = "sp" if base == "sp" else base
        base_value = 0 if base == "sp" else registers.get(base)
        return base_value + operand.mem.disp if base_value is not None else None

    def method_at(table: int | None, index: int, stack_table: bool) -> dict | None:
        offset = index * 24
        if stack_table and table is not None:
            base = table + offset
            pointers = [memory.get(base), memory.get(base + 8), memory.get(base + 16)]
        elif table is not None:
            pointers = [image.pointer(table + offset), image.pointer(table + offset + 8), image.pointer(table + offset + 16)]
        else:
            return None
        if any(pointer is None for pointer in pointers):
            return None
        return {
            "name": image.cstring(pointers[0]),
            "signature": image.cstring(pointers[1]),
            "function": f"0x{pointers[2]:x}",
            "table": "stack" if stack_table else f"0x{table:x}",
        }

    for insn in md.disasm(text.data(), text["sh_addr"]):
        if insn.id == 0:  # Capstone skipdata pseudo-instruction.
            continue
        mnemonic = insn.mnemonic
        operands = insn.operands

        if mnemonic == "ret":
            registers = {"sp": 0}
            vectors.clear()
            memory.clear()
            vtable_calls.clear()
            current_class = None
            continue

        if mnemonic == "adrp" and len(operands) == 2:
            key = register_key(md, operands[0])
            if key:
                registers[key] = operands[1].imm
            continue

        if mnemonic in {"add", "sub"} and len(operands) == 3 and operands[2].type == ARM64_OP_IMM:
            destination = register_key(md, operands[0])
            source = register_key(md, operands[1])
            if destination:
                value = 0 if source == "sp" else registers.get(source)
                if value is not None:
                    registers[destination] = value + operands[2].imm * (1 if mnemonic == "add" else -1)
                else:
                    registers.pop(destination, None)
            continue

        if mnemonic in {"mov", "movz"} and len(operands) == 2:
            destination = register_key(md, operands[0])
            if destination:
                if operands[1].type == ARM64_OP_IMM:
                    registers[destination] = operands[1].imm
                else:
                    source = register_key(md, operands[1])
                    value = 0 if source == "sp" else registers.get(source)
                    if value is None:
                        registers.pop(destination, None)
                    else:
                        registers[destination] = value
            continue

        if mnemonic == "movk" and len(operands) >= 3:
            destination = register_key(md, operands[0])
            if destination and destination in registers:
                shift = operands[2].imm if len(operands) == 3 else 0
                mask = 0xFFFF << shift
                registers[destination] = (registers[destination] & ~mask) | (operands[1].imm << shift)
            continue

        if mnemonic in {"ldr", "ldur"} and len(operands) == 2 and operands[1].type == ARM64_OP_MEM:
            destination = register_key(md, operands[0])
            address = address_of(insn)
            if destination:
                if md.reg_name(operands[0].reg).startswith("q"):
                    if address is not None:
                        vectors[destination] = (image.pointer(address), image.pointer(address + 8))
                else:
                    value = memory.get(address) if address is not None and address < 0x10000 else image.pointer(address) if address is not None else None
                    if value is None:
                        registers.pop(destination, None)
                    else:
                        registers[destination] = value
                vtable_calls.pop(destination, None)
                if operands[1].mem.disp in {0x30, 0x6B8} and destination:
                    vtable_calls[destination] = operands[1].mem.disp
            continue

        if mnemonic in {"str", "stur"} and len(operands) == 2 and operands[1].type == ARM64_OP_MEM:
            source = register_key(md, operands[0])
            address = address_of(insn)
            if source and address is not None and 0 <= address < 0x10000:
                if md.reg_name(operands[0].reg).startswith("q"):
                    pair = vectors.get(source, (None, None))
                    memory[address], memory[address + 8] = pair
                else:
                    memory[address] = registers.get(source)
            continue

        if mnemonic in {"ldp", "stp"} and len(operands) == 3 and operands[2].type == ARM64_OP_MEM:
            first, second = register_key(md, operands[0]), register_key(md, operands[1])
            address = address_of(insn, 2)
            vector = md.reg_name(operands[0].reg).startswith("q")
            if mnemonic == "ldp":
                if vector and address is not None:
                    vectors[first] = (image.pointer(address), image.pointer(address + 8))
                    vectors[second] = (image.pointer(address + 16), image.pointer(address + 24))
                elif address is not None:
                    for key, slot in ((first, address), (second, address + 8)):
                        value = memory.get(slot) if 0 <= slot < 0x10000 else image.pointer(slot)
                        if value is None:
                            registers.pop(key, None)
                        else:
                            registers[key] = value
            elif address is not None and 0 <= address < 0x10000:
                if vector:
                    for key, slot in ((first, address), (second, address + 16)):
                        pair = vectors.get(key, (None, None))
                        memory[slot], memory[slot + 8] = pair
                else:
                    memory[address] = registers.get(first) if first else None
                    memory[address + 8] = registers.get(second) if second else None
            continue

        if mnemonic == "bl" and operands and operands[0].type == ARM64_OP_IMM:
            symbol = image.plt_symbols.get(operands[0].imm)
            if symbol == "memcpy":
                destination, source, size = registers.get("x0"), registers.get("x1"), registers.get("x2")
                if destination is not None and source is not None and size is not None and 0 <= destination < 0x10000 and size <= 16384:
                    for offset in range(0, size - 7, 8):
                        memory[destination + offset] = image.pointer(source + offset)
            # Internal direct calls in these binaries frequently preserve the
            # table pointer / JNIEnv vtable scratch register. Keep the symbolic
            # state; RegisterNatives itself overwrites every argument it needs.
            continue

        if mnemonic == "blr" and operands:
            target = register_key(md, operands[0])
            call = vtable_calls.get(target)
            if call == 0x30:
                current_class = image.cstring(registers.get("x1"))
            elif call == 0x6B8:
                table = registers.get("x2")
                count = registers.get("x3")
                stack_table = table is not None and 0 <= table < 0x10000 and any(
                    table + offset in memory for offset in (0, 8, 16)
                )
                methods = []
                if count is not None and 0 < count <= 512:
                    for index in range(count):
                        method = method_at(table, index, stack_table)
                        if method is None:
                            methods.append({"error": f"unresolved table entry {index}"})
                        else:
                            methods.append(method)
                results.append({
                    "call_site": f"0x{insn.address:x}",
                    "class": current_class,
                    "method_count": count,
                    "methods": methods,
                })
            # Calls clobber the AArch64 caller-saved registers. Keeping stack
            # constants is enough for tables copied before RegisterNatives.
            for index in range(18):
                registers.pop(f"x{index}", None)
                vtable_calls.pop(f"x{index}", None)
            registers["sp"] = 0
            continue

    return results


def smali_native_methods(root: Path) -> dict[str, list[dict]]:
    methods: dict[str, list[dict]] = defaultdict(list)
    class_pattern = re.compile(r"^\.class\s+.*?L([^;]+);", re.MULTILINE)
    native_pattern = re.compile(r"^\.method\s+(.*?)\s+native\s+([^(\s]+)\(([^)]*)\)(.+)$", re.MULTILINE)
    for path in root.rglob("*.smali"):
        text = path.read_text(encoding="utf-8", errors="replace")
        class_match = class_pattern.search(text)
        if not class_match:
            continue
        for match in native_pattern.finditer(text):
            methods[class_match.group(1)].append({
                "name": match.group(2),
                "signature": f"({match.group(3)}){match.group(4)}",
                "source": str(path),
            })
    return methods


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("library", type=Path)
    parser.add_argument("--smali-root", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    image = ElfImage(args.library)
    try:
        registrations = register_native_methods(image)
    finally:
        image.close()

    declared = smali_native_methods(args.smali_root) if args.smali_root else {}
    for registration in registrations:
        class_name = registration["class"]
        expected = {(method["name"], method["signature"]) for method in declared.get(class_name, [])}
        actual = {(method.get("name"), method.get("signature")) for method in registration["methods"] if "error" not in method}
        registration["smali_declared_count"] = len(expected)
        registration["missing_from_smali"] = sorted(f"{name}{signature}" for name, signature in actual - expected)
        registration["missing_from_registration"] = sorted(f"{name}{signature}" for name, signature in expected - actual)

    report = {
        "library": str(args.library),
        "register_natives_call_sites": len(registrations),
        "registered_method_count": sum(registration.get("method_count") or 0 for registration in registrations),
        "registrations": registrations,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "registrations"}, indent=2))
    for registration in registrations:
        print(f"{registration['call_site']} {registration['class']}: {registration['method_count']} methods")
        for method in registration["methods"]:
            print(f"  {method.get('name', '<unresolved>')}{method.get('signature', method.get('error', ''))} {method.get('function', '')}")


if __name__ == "__main__":
    main()
