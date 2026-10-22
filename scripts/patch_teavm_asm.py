#!/usr/bin/env python3
"""
Patch teavm-relocated-libs-asm-0.15.0.jar to accept class file version 70
(Java 26) in org.teavm.asm.ClassReader.

TeaVM 0.15.0's repackaged ASM only supports up to class file version 69
(Java 25 = Opcodes.V25). When running on JDK 26, TeaVM's
MetaprogrammingClassLoader tries to parse JDK 26's own classes (which
are version 70) and throws:
    IllegalArgumentException: Unsupported class file major version 70

The version check is in ClassReader.<init>(byte[], int, int), verified by
javap disassembly of the pristine artifact (bytecode offsets 26/28):
    26: bipush 69        ; Opcodes.V25
    28: if_icmple +22    ; version <= 69 -> skip the throw
In the class file this is the byte triple 10 45 A4 at file offset 15971
(the only other 10 45 occurrence, at offset 251, is constant-pool DATA -
never patch class files by blind byte matching without disassembler proof).

This script patches `bipush 69` (0x10 0x45) to `bipush 127` (0x10 0x7F),
raising the upper bound to class file version 127 (Java 83). The patched
ClassReader was functionally tested: it accepts a JDK-26-compiled probe
class (major 70) while the pristine one still rejects it with exactly
"Unsupported class file major version 70".

The script is IDEMPOTENT: if the pattern is absent but the already-patched
form (10 7F A4) is present, it exits 0 without touching the file. This
matters because the gradle-home cache can persist patched jars across
CI runs (a past run cached an already-patched jar and broke this step).
"""
import zipfile
import os
import sys

JAR_PATH = sys.argv[1] if len(sys.argv) > 1 else '/tmp/teavm-asm.jar'
OUTPUT_PATH = sys.argv[2] if len(sys.argv) > 2 else '/tmp/teavm-asm-patched.jar'

with zipfile.ZipFile(JAR_PATH, 'r') as zin:
    cr_data = bytearray(zin.read('org/teavm/asm/ClassReader.class'))

print(f"Original ClassReader.class size: {len(cr_data)} bytes")

ORIG = bytes([0x10, 0x45, 0xA4])   # bipush 69, if_icmple
PATCHED = bytes([0x10, 0x7F, 0xA4])  # bipush 127, if_icmple

orig_sites = [i for i in range(len(cr_data) - 2) if bytes(cr_data[i:i+3]) == ORIG]
patched_sites = [i for i in range(len(cr_data) - 2) if bytes(cr_data[i:i+3]) == PATCHED]

if not orig_sites and patched_sites:
    print(f"  Already patched (bipush 127 + if_icmple at offset {patched_sites[0]}) - nothing to do")
    if JAR_PATH != OUTPUT_PATH:
        import shutil
        shutil.copyfile(JAR_PATH, OUTPUT_PATH)
    sys.exit(0)

if len(orig_sites) != 1:
    print(f"ERROR: expected exactly 1 version-check site (10 45 A4), found {len(orig_sites)}: {orig_sites}")
    for i in range(len(cr_data) - 1):
        if cr_data[i] == 0x10 and cr_data[i+1] == 0x45:
            print(f"  bipush 69 at offset {i}, context: {bytes(cr_data[i:i+5]).hex()}")
    sys.exit(1)

site = orig_sites[0]
cr_data[site + 1] = 0x7F
print(f"  Patched version check at offset {site}: bipush 69 -> bipush 127")

# Sanity: the patched triple must now be present exactly once, and the
# following bytes must still be a 2-byte branch (if_icmple 0xA4 + s2 operand)
assert bytes(cr_data[site:site+3]) == PATCHED
assert cr_data[site + 2] == 0xA4

with zipfile.ZipFile(JAR_PATH, 'r') as zin:
    with zipfile.ZipFile(OUTPUT_PATH + '.tmp', 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename == 'org/teavm/asm/ClassReader.class':
                zout.writestr(item, bytes(cr_data))
                print(f"Wrote patched ClassReader.class ({len(cr_data)} bytes)")
            else:
                zout.writestr(item, zin.read(item.filename))

os.replace(OUTPUT_PATH + '.tmp', OUTPUT_PATH)
print(f"\nPatched JAR saved to {OUTPUT_PATH}")
