#!/usr/bin/env python3
"""
Patch teavm-core.jar with source-level fixes compiled via javac.

Patches applied (all compiled from scripts/teavm-patches/ against the
pristine teavm-core classes, then swapped into the JAR):

1. PhiUpdater.use() -- TeaVM 0.15 throws AssertionError("Variable used
   before definition") on some MC 26.x methods. The patched source returns
   the unmapped variable instead of crashing the optimizer.

2. Null-safe optimizer classes (VariableEscapeAnalyzer,
   ConstantConditionElimination, EscapeAnalysis) -- the AGGRESSIVE
   optimization pass NPEs on getOperand() returning null.

NOTE: an earlier revision of this script did raw 4-byte swaps over the
WHOLE class file (B7 xx xx BF -> other opcodes, plus an "aggressive"
fallback replacing every 0xBF byte). That matched byte sequences inside
the constant pool and corrupted PhiUpdater.class, which later made
Gradle's classpath instrumentation fail with
"java.lang.ArrayIndexOutOfBoundsException ... ClassReader.readUtf".
Never patch class files by blind byte matching -- compile patched
sources instead (as this script now does).
"""
import zipfile
import shutil
import os
import sys
import subprocess
import glob

JAR_PATH = sys.argv[1] if len(sys.argv) > 1 else '/tmp/teavm-core.jar'
OUTPUT_PATH = sys.argv[2] if len(sys.argv) > 2 else '/tmp/teavm-core-patched.jar'

# ============================================================
# Compile source-level patches
# ============================================================

PATCHES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'teavm-patches')
JAVA_HOME = os.environ.get('JAVA_HOME', '')
JAVAC = os.path.join(JAVA_HOME, 'bin', 'javac') if JAVA_HOME else 'javac'

# Classes to patch: (source path relative to PATCHES_DIR, class files in JAR)
PATCH_CLASSES = [
    ('org/teavm/model/util/PhiUpdater.java',
     ['org/teavm/model/util/PhiUpdater.class']),
    ('org/teavm/model/optimization/VariableEscapeAnalyzer.java',
     ['org/teavm/model/optimization/VariableEscapeAnalyzer.class',
      'org/teavm/model/optimization/VariableEscapeAnalyzer$InstructionAnalyzer.class']),
    ('org/teavm/model/optimization/ConstantConditionElimination.java',
     ['org/teavm/model/optimization/ConstantConditionElimination.class']),
    ('org/teavm/model/analysis/EscapeAnalysis.java',
     ['org/teavm/model/analysis/EscapeAnalysis.class',
      'org/teavm/model/analysis/EscapeAnalysis$InstructionEscapeVisitor.class']),
]

compiled_classes = {}  # class file name -> bytes

print(f"=== Compiling teavm-core patches ===")
print(f"PATCHES_DIR: {PATCHES_DIR}")
print(f"JAVAC: {JAVAC}")
print(f"JAR_PATH (classpath): {JAR_PATH}")

if not os.path.isdir(PATCHES_DIR):
    print(f"ERROR: Patches directory not found: {PATCHES_DIR}")
    sys.exit(1)

# Collect all .java files to compile
java_files = []
for src_rel, _ in PATCH_CLASSES:
    src_abs = os.path.join(PATCHES_DIR, src_rel)
    if os.path.exists(src_abs):
        java_files.append(src_abs)
        print(f"  Found: {src_rel}")
    else:
        print(f"  ERROR: {src_rel} not found!")
        sys.exit(1)

# Compile with teavm-core JAR AND all its dependencies as classpath.
# teavm-core depends on hppc, common, etc. which need to be on the
# classpath for EscapeAnalysis.java to compile.
compile_dir = OUTPUT_PATH + '.compile_out'
os.makedirs(compile_dir, exist_ok=True)

cache_dir = os.path.expanduser('~/.gradle/caches/modules-2/files-2.1')
all_jars = glob.glob(os.path.join(cache_dir, '**', '*.jar'), recursive=True)
# Filter out sources and javadoc JARs
all_jars = [j for j in all_jars if not (j.endswith('-sources.jar') or j.endswith('-javadoc.jar'))]
# Always include the teavm-core JAR itself
all_jars.append(JAR_PATH)
# TeaVM relocates its bundled third-party libs (hppc -> org.teavm.hppc),
# and the pristine patch sources import those packages. The relocated jars
# are NOT on the build classpath, so ship them next to the patch sources.
libs_dir = os.path.join(PATCHES_DIR, 'libs')
all_jars.extend(sorted(glob.glob(os.path.join(libs_dir, '*.jar'))))
classpath = ':'.join(all_jars)
print(f"  Classpath has {len(all_jars)} JARs")

cmd = [JAVAC, '-nowarn', '-cp', classpath, '-d', compile_dir] + java_files
print(f"  Running: {JAVAC} -cp <{len(all_jars)} JARs> -d {compile_dir} {len(java_files)} files")
result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
if result.returncode != 0:
    print(f"  COMPILE ERROR (returncode={result.returncode}):")
    print(result.stderr[:3000])
    print("--- stdout ---")
    print(result.stdout[:1000])
    print("ERROR: Patches could not be compiled. Refusing to ship an unpatched jar"
          " (the PhiUpdater assertion will crash AGGRESSIVE-optimized builds).")
    shutil.rmtree(compile_dir, ignore_errors=True)
    sys.exit(1)

print("  Compilation succeeded!")
for root, dirs, files in os.walk(compile_dir):
    for fname in files:
        if fname.endswith('.class'):
            class_path = os.path.join(root, fname)
            rel_path = os.path.relpath(class_path, compile_dir)
            with open(class_path, 'rb') as f:
                compiled_classes[rel_path.replace(os.sep, '/')] = f.read()
            print(f"    Compiled: {rel_path} ({os.path.getsize(class_path)} bytes)")

shutil.rmtree(compile_dir, ignore_errors=True)

# ============================================================
# Write the patched JAR with all source-level patches
# ============================================================
with zipfile.ZipFile(JAR_PATH, 'r') as zin:
    with zipfile.ZipFile(OUTPUT_PATH + '.tmp', 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename in compiled_classes:
                zout.writestr(item, compiled_classes[item.filename])
                print(f"Replaced {item.filename} with patched version "
                      f"({len(compiled_classes[item.filename])} bytes)")
            else:
                zout.writestr(item, zin.read(item.filename))

# Also write any new inner class files that didn't exist in the original JAR
if compiled_classes:
    existing_names = set()
    with zipfile.ZipFile(OUTPUT_PATH + '.tmp', 'r') as znew:
        existing_names = set(znew.namelist())
    with zipfile.ZipFile(OUTPUT_PATH + '.tmp', 'a', zipfile.ZIP_DEFLATED) as zappend:
        for class_name, class_bytes in compiled_classes.items():
            if class_name not in existing_names:
                zappend.writestr(class_name, class_bytes)
                print(f"Added new class: {class_name} ({len(class_bytes)} bytes)")

os.replace(OUTPUT_PATH + '.tmp', OUTPUT_PATH)

# Self-check: every replaced class must parse with a strict reader.
with zipfile.ZipFile(OUTPUT_PATH, 'r') as zout:
    for name in compiled_classes:
        data = zout.read(name)
        if len(data) < 8 or data[:4] != b'\xca\xfe\xba\xbe':
            print(f"ERROR: patched {name} is not a valid class file")
            sys.exit(1)
print(f"\nPatched JAR saved to {OUTPUT_PATH}")
