#!/usr/bin/env python3
"""
Post-build patcher for classes.js.

Works around a TeaVM 0.15 bug where interface default methods are NOT
included in the virtual method table of implementing classes.

This version works on UNOBFUSCATED builds only (where function names
start with cms_, os_, jl_, etc.).

Strategy:
  1. Scan classes.js for all default method definitions
     (pattern: <prefix>_<Interface>_<method> = $this => ... )
  2. Build a JS registry mapping interface function name → { method: funcName }
  3. Append a JS patcher that:
     a. Builds a name→function lookup from global scope
     b. Iterates through all registered classes (Fnk array)
     c. For each class, checks its interfaces
     d. Adds missing default methods to cls.prototype
"""
import re
import sys
import json


def find_default_methods(data):
    """
    Find all default method definitions in classes.js.

    Default methods are top-level functions that take $this as first arg:
      <prefix>_<Interface>_<method> = $this => ...
      <prefix>_<Interface>_<method> = ($this, ...) => ...

    Returns: dict mapping interface_func_name -> { "$method": "funcName" }
    """
    # Match: identifier = $this =>  OR  identifier = ($this
    # The identifier can be any valid JS identifier (letters, digits, _, $)
    pattern = re.compile(
        r'([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*(?:\$this\s*=>|(?:\([^)]*\$this[^)]*\))\s*=>)'
    )

    registry = {}
    for match in pattern.finditer(data):
        full_name = match.group(1)

        # Parse: <prefix>_<Interface>_<method>
        # We need at least 3 segments: prefix_Interface_method
        # But interface names can contain $ (inner classes)
        # Strategy: split on _ and take first segment as prefix,
        # last segment as method, everything in between as interface

        parts = full_name.split('_')
        if len(parts) < 3:
            continue

        # prefix is parts[0] (e.g., cms, os, jl)
        prefix = parts[0]
        # method is parts[-1]
        method_name = parts[-1]
        # interface is parts[1:-1] joined with _
        iface_name = '_'.join(parts[1:-1])

        if not iface_name or not method_name:
            continue

        # Skip constructors and abstract methods
        if method_name in ('_init_', '_clinit_', '$callClinit', 'encode', 'decode'):
            continue
        # Skip if method name starts with _ (likely internal)
        if method_name.startswith('_'):
            continue

        # Reconstruct the full interface function name
        iface_func_name = prefix + '_' + iface_name

        if iface_func_name not in registry:
            registry[iface_func_name] = {}

        vmethod = '$' + method_name
        registry[iface_func_name][vmethod] = full_name

    return registry


def build_patcher_js(registry, registry_names=None, meta_names=None):
    """
    Build a JS snippet that patches all class prototypes with missing
    default methods from their interfaces.

    registry_names: list of detected variable names for the class registry
                    array (e.g., ['ETN', 'FnY']). These are closure vars.
    meta_names: list of detected variable names for the metadata Symbol
                (e.g., ['Gf', 'GN']). These are closure vars.
    """
    registry_json = json.dumps(registry, separators=(',', ':'))

    # Build JS expressions to try each detected name
    if registry_names:
        registry_checks = ' || '.join(
            f'(typeof {name}!=="undefined"&&Array.isArray({name})&&{name}.length>100?{name}:null)'
            for name in registry_names
        )
    else:
        registry_checks = 'null'

    if meta_names:
        meta_checks = ' || '.join(
            f'(typeof {name}!=="undefined"?{name}:null)'
            for name in meta_names
        )
    else:
        meta_checks = 'null'

    patcher = """
// ============================================================
// TeaVM Default Method Patcher (hybrid: prototype + registry)
// Works with both obfuscated and unobfuscated builds.
// ============================================================
// This code is inserted INSIDE the TeaVM IIFE, so it has direct access
// to closure variables. However, obfuscated builds mangle variable
// names (FnY→ETN, GN→Gf, etc.) so we can't hardcode them.
// Instead, we scan for the metadata Symbol by its description
// "teavm_meta" and find the class registry by checking candidate arrays.
(function() {
    var __registry = %s;


    // === Find the metadata Symbol ===
    // Try detected names first (from Python scan of source code)
    var meta = %(meta_checks)s;
    // Fallback: try known unobfuscated names
    if (!meta) try { if (typeof GN !== 'undefined') meta = GN; } catch(e) {}
    if (!meta) try { if (typeof $rt_meta !== 'undefined') meta = $rt_meta; } catch(e) {}

    // For obfuscated builds, find Symbol("teavm_meta") by scanning
    if (!meta) {
        var globalObj = typeof self !== 'undefined' ? self : typeof global !== 'undefined' ? global : this;
        var checked = 0;
        for (var key in globalObj) {
            if (checked > 2000) break;
            try {
                var val = globalObj[key];
                if (typeof val === 'function' && val.prototype) {
                    var symKeys = Object.getOwnPropertySymbols(val);
                    for (var k = 0; k < symKeys.length; k++) {
                        if (symKeys[k].toString() === 'Symbol(teavm_meta)') {
                            meta = symKeys[k];
                            break;
                        }
                    }
                    if (meta) break;
                    checked++;
                }
            } catch(e) {}
        }
    }

    if (!meta) { console.warn('[DefaultMethodPatcher] Metadata symbol not found'); return; }

    // === Find the class registry array ===
    // Try detected names first (from Python scan of source code)
    var allClasses = %(registry_checks)s;
    // Fallback: try known unobfuscated names
    if (!allClasses) try { if (typeof FnY !== 'undefined' && Array.isArray(FnY) && FnY.length > 100) allClasses = FnY; } catch(e) {}
    if (!allClasses) try { if (typeof Fnk !== 'undefined' && Array.isArray(Fnk) && Fnk.length > 100) allClasses = Fnk; } catch(e) {}
    if (!allClasses) try { if (typeof $rt_allClasses !== 'undefined' && Array.isArray($rt_allClasses) && $rt_allClasses.length > 100) allClasses = $rt_allClasses; } catch(e) {}

    // For obfuscated builds, scan global scope for large arrays
    if (!allClasses) {
        var globalObj2 = typeof self !== 'undefined' ? self : typeof global !== 'undefined' ? global : this;
        var bestCandidate = null;
        var bestLen = 0;
        for (var key in globalObj2) {
            try {
                var val = globalObj2[key];
                if (Array.isArray(val) && val.length > 500) {
                    var sample = val[0];
                    if (sample && typeof sample === 'function' && sample[meta]) {
                        if (val.length > bestLen) { bestLen = val.length; bestCandidate = val; }
                    }
                }
            } catch(e) {}
        }
        if (bestCandidate) allClasses = bestCandidate;
    }

    if (!allClasses) { console.warn('[DefaultMethodPatcher] Class registry not found'); return; }
    console.log('[DefaultMethodPatcher] count=' + allClasses.length + ' meta=' + (typeof meta === 'symbol' ? meta.toString() : String(meta)));

    var __funcs = {};
    // Only use eval-based lookup for unobfuscated builds (registry non-empty)
    if (Object.keys(__registry).length > 0) {
        for (var __n in __registry) {
            try { __funcs[__n] = eval(__n); } catch(e) {}
            for (var __m in __registry[__n]) {
                try { __funcs[__registry[__n][__m]] = eval(__registry[__n][__m]); } catch(e) {}
            }
        }
    }

    var patched = 0, classesPatched = 0;
    for (var i = 0; i < allClasses.length; i++) {
        var cls = allClasses[i];
        if (!cls || !cls.prototype || !cls[meta]) continue;
        var clsMeta = cls[meta];
        if (!clsMeta.superinterfaces || clsMeta.superinterfaces.length === 0) continue;
        var classPatched = false;
        for (var j = 0; j < clsMeta.superinterfaces.length; j++) {
            var iface = clsMeta.superinterfaces[j];
            if (!iface) continue;

            // Method 1: Copy from interface prototype
            var proto = iface.prototype;
            for (var key in proto) {
                if (typeof proto[key] === 'function' && key !== 'constructor') {
                    if (!Object.prototype.hasOwnProperty.call(cls.prototype, key)) {
                        (function(key, fn) {
                            cls.prototype[key] = function() {
                                var args = [this];
                                for (var k = 0; k < arguments.length; k++) args.push(arguments[k]);
                                return fn.apply(null, args);
                            };
                        })(key, proto[key]);
                        patched++; classPatched = true;
                    }
                }
            }

            // Method 2: Copy from registry (for DCE'd methods not on prototype)
            for (var ifaceName in __registry) {
                var ifaceFunc = __funcs[ifaceName];
                if (!ifaceFunc || ifaceFunc !== iface) continue;
                var methods = __registry[ifaceName];
                for (var vmethod in methods) {
                    if (!Object.prototype.hasOwnProperty.call(cls.prototype, vmethod)) {
                        var func = __funcs[methods[vmethod]];
                        if (typeof func === 'function') {
                            (function(vmethod, func) {
                                cls.prototype[vmethod] = function() {
                                    var args = [this];
                                    for (var k = 0; k < arguments.length; k++) args.push(arguments[k]);
                                    return func.apply(null, args);
                                };
                            })(vmethod, func);
                            patched++; classPatched = true;
                        }
                    }
                }
            }
        }
        if (classPatched) classesPatched++;
    }
    console.log('[DefaultMethodPatcher] Patched ' + patched + ' methods across ' + classesPatched + ' classes');
})();
""" % {'meta_checks': meta_checks, 'registry_checks': registry_checks}
    # Now substitute the registry JSON (can't use %s with named params)
    patcher = patcher.replace('%s', registry_json, 1)
    return patcher


def wrap_clinits_textually(data):
    """
    Wrap all __clinit_ function bodies in try/catch.

    TeaVM generates static initializers like:
        nmu_ExtraCodecs__clinit_ = () => {
            <body>
        },

    We wrap the body in try/catch:
        nmu_ExtraCodecs__clinit_ = () => {
            try { <body> } catch(__e) { if(!window.__eaglerClinitErrors) window.__eaglerClinitErrors=0; if(window.__eaglerClinitErrors<10) console.warn('[ClinitWrap] '+__e.message); window.__eaglerClinitErrors++; }
        },

    This prevents forward-reference crashes from killing the game.
    """
    # Pattern: <name>__clinit_ = () => {
    # We need to find the matching closing brace and wrap the body.
    # Since JS brace matching is complex, we use a simpler approach:
    # Replace the opening pattern to add try/catch at the start,
    # and add the catch before the closing brace.

    # Strategy: Find all "__clinit_ = () => {" patterns
    # For each, find the matching closing "}" (accounting for nested braces)
    # and wrap the body.

    import re

    pattern = re.compile(r'(\w+__clinit_\s*=\s*\(\)\s*=>\s*\{)')

    result = []
    last_end = 0
    wrapped_count = 0

    for match in pattern.finditer(data):
        # Find the matching closing brace
        start_pos = match.end()  # position after the opening {
        depth = 1
        pos = start_pos
        while pos < len(data) and depth > 0:
            if data[pos] == '{':
                depth += 1
            elif data[pos] == '}':
                depth -= 1
            elif data[pos] == '"' or data[pos] == "'":
                # Skip string literals
                quote = data[pos]
                pos += 1
                while pos < len(data) and data[pos] != quote:
                    if data[pos] == '\\':
                        pos += 1
                    pos += 1
            elif data[pos] == '`':
                # Skip template literals
                pos += 1
                while pos < len(data) and data[pos] != '`':
                    if data[pos] == '\\':
                        pos += 1
                    pos += 1
            pos += 1

        if depth == 0:
            # pos-1 is the closing }
            body = data[start_pos:pos-1]
            # Wrap the body in try/catch
            wrapped_body = (
                '\ntry {\n' + body + '\n} catch(__e) { '
                'if(typeof window!=="undefined"){if(!window.__eaglerClinitErrors)window.__eaglerClinitErrors=0;'
                'if(window.__eaglerClinitErrors<10)console.warn("[ClinitWrap] "+(__e&&__e.message?__e.message:String(__e)));'
                'window.__eaglerClinitErrors++;} }'
            )
            result.append(data[last_end:match.start()])
            result.append(match.group(1))
            result.append(wrapped_body)
            last_end = pos - 1  # include the closing }
            wrapped_count += 1

    result.append(data[last_end:])
    patched = ''.join(result)

    print(f"Wrapped {wrapped_count} __clinit_ functions in try/catch")
    return patched


def patch_add_suppressed(data):
    """
    Patch jl_Throwable_addSuppressed to handle null suppressed array.

    TeaVM's implementation accesses var$2.data.length where var$2 is
    the suppressed exceptions array, which can be null. We add a null
    check at the beginning of the function.
    """
    import re

    # Find the function: jl_Throwable_addSuppressed = ($this, $exception) => {
    # and add a null check for the suppressed array after the opening brace
    pattern = r'(jl_Throwable_addSuppressed\s*=\s*\([^)]*\)\s*=>\s*\{)'

    match = re.search(pattern, data)
    if not match:
        print("  WARNING: jl_Throwable_addSuppressed not found")
        return data

    # Find the body and add null checks
    # The function body typically does:
    #   var$1 = $this.$suppressed; (or similar)
    #   if (var$1 === null) { $this.$suppressed = ...; var$1 = ...; }
    #   var$3 = var$1.data.length + 1 | 0;
    # We need to ensure var$1 (the suppressed array) is not null

    # Simple approach: wrap the entire function body in try/catch
    start_pos = match.end()
    depth = 1
    pos = start_pos
    while pos < len(data) and depth > 0:
        if data[pos] == '{':
            depth += 1
        elif data[pos] == '}':
            depth -= 1
        elif data[pos] == '"' or data[pos] == "'":
            quote = data[pos]
            pos += 1
            while pos < len(data) and data[pos] != quote:
                if data[pos] == '\\':
                    pos += 1
                pos += 1
        pos += 1

    if depth == 0:
        body = data[start_pos:pos-1]
        wrapped_body = '\ntry {\n' + body + '\n} catch(__e) { /* suppressed array null — ignore */ }\n'
        result = data[:match.start()] + match.group(1) + wrapped_body + data[pos-1:]
        print("  Wrapped jl_Throwable_addSuppressed in try/catch")
        return result

    print("  WARNING: Could not find end of jl_Throwable_addSuppressed")
    return data


def patch_file_channel_open(data):
    """
    Patch jnc_FileChannel_open to return a fake FileChannel instead of
    throwing IOException("Cannot open file channel in browser").

    The original implementation always throws. We replace it with a
    function that returns a fake channel object with read/write/close
    methods that do nothing.
    """
    import re

    # Find the function: jnc_FileChannel_open = (var$1, var$2) => { ... throw ... };
    pattern = r'(jnc_FileChannel_open\s*=\s*\([^)]*\)\s*=>\s*\{)'

    match = re.search(pattern, data)
    if not match:
        print("  WARNING: jnc_FileChannel_open not found")
        return data

    # Find the matching closing brace
    start_pos = match.end()
    depth = 1
    pos = start_pos
    while pos < len(data) and depth > 0:
        if data[pos] == '{':
            depth += 1
        elif data[pos] == '}':
            depth -= 1
        elif data[pos] == '"' or data[pos] == "'":
            quote = data[pos]
            pos += 1
            while pos < len(data) and data[pos] != quote:
                if data[pos] == '\\':
                    pos += 1
                pos += 1
        pos += 1

    if depth == 0:
        # Replace the body with a fake channel return
        fake_body = """
    // PATCHED: Return fake FileChannel instead of throwing IOException.
    // MC's DownloadQueue needs a FileChannel for persistent storage.
    // In the browser, we don't have real file channels, so return a
    // fake one that does nothing (all reads return -1, writes no-op).
    var fakeChannel = {
        $read: function() { return -1; },
        read: function() { return -1; },
        $read0: function() { return -1; },
        read0: function() { return -1; },
        $read1: function(b) { return -1; },
        read1: function(b) { return -1; },
        $read2: function(b,o,l) { return -1; },
        read2: function(b,o,l) { return -1; },
        $read3: function(b,o,l) { return -1; },
        read3: function(b,o,l) { return -1; },
        $write: function() { return 0; },
        write: function() { return 0; },
        $write0: function() { return 0; },
        write0: function() { return 0; },
        $write1: function(b) { return 0; },
        write1: function(b) { return 0; },
        $close: function() {},
        close: function() {},
        $isOpen: function() { return 1; },
        isOpen: function() { return true; },
        $position: function() { return 0; },
        position: function() { return 0; },
        $position0: function(p) { return this; },
        position0: function(p) { return this; },
        $size: function() { return 0; },
        size: function() { return 0; },
        $truncate: function(s) { return this; },
        truncate: function(s) { return this; },
        $force: function() {},
        force: function() {},
        $lock: function() { return this; },
        lock: function() { return this; },
        $tryLock: function() { return this; },
        tryLock: function() { return this; },
        $map: function() { return null; },
        map: function() { return null; },
        $transferFrom: function() { return 0; },
        transferFrom: function() { return 0; },
        $transferTo: function() { return 0; },
        transferTo: function() { return 0; },
        $write2: function(b,p) { return 0; },
        write2: function(b,p) { return 0; },
        $read4: function(b,p) { return -1; },
        read4: function(b,p) { return -1; }
    };
    return fakeChannel;
"""
        result = data[:match.start()] + match.group(1) + fake_body + data[pos-1:]
        print("  Replaced jnc_FileChannel_open with fake channel return")
        return result

    print("  WARNING: Could not find end of jnc_FileChannel_open")
    return data


def patch_data_fixer(data):
    """
    Patch nmud_DataFixers_getDataFixer to return a dummy fixer when
    DATA_FIXER is null (because the clinit failed and was caught).

    The original: return nmud_DataFixers_DATA_FIXER.$fixerUpper0;
    Patched: return (nmud_DataFixers_DATA_FIXER && nmud_DataFixers_DATA_FIXER.$fixerUpper0) || {};
    """
    old = 'return nmud_DataFixers_DATA_FIXER.$fixerUpper0;'
    new = 'return (nmud_DataFixers_DATA_FIXER && nmud_DataFixers_DATA_FIXER.$fixerUpper0) || {};'
    if old in data:
        data = data.replace(old, new)
        print("  Patched getDataFixer with null safety")
    else:
        print("  WARNING: getDataFixer pattern not found")
    return data


def patch_shared_constants(data):
    """
    Patch SharedConstants.getCurrentVersion to return a dummy version
    when CURRENT_VERSION is null (because the clinit failed).

    Original:
        var$1 = nm_SharedConstants_CURRENT_VERSION;
        if (var$1 !== null)
            return var$1;
        var$2 = new jl_IllegalStateException;
        jl_Throwable__init_(var$2, $rt_s(1994));
        $rt_throw(var$2);

    Patched: Return a dummy version object when CURRENT_VERSION is null.
    """
    old = """var$1 = nm_SharedConstants_CURRENT_VERSION;
        if (var$1 !== null)
            return var$1;
        var$2 = new jl_IllegalStateException;
        jl_Throwable__init_(var$2, $rt_s(1994));
        $rt_throw(var$2);"""

    new = """var$1 = nm_SharedConstants_CURRENT_VERSION;
        if (var$1 !== null)
            return var$1;
        var __vstr = function(s) { var o = new String(s); o.$nativeString = s; return o; };
        return { $getName: function() { return __vstr('26.1.2'); }, getName: function() { return __vstr('26.1.2'); }, $name: function() { return __vstr('26.1.2'); }, name: function() { return __vstr('26.1.2'); }, $getProtocolVersion: function() { return 775; }, getProtocolVersion: function() { return 775; }, $getProtocolVersionIp: function() { return 775; }, $getDataVersion: function() { return 4189; }, getDataVersion: function() { return 4189; }, $dataVersion: function() { return 4189; }, dataVersion: function() { return 4189; }, $getServerBrands: function() { return []; }, getServerBrands: function() { return []; }, $toString: function() { return __vstr('26.1.2'); }, toString: function() { return '26.1.2'; }, $getId: function() { return __vstr('26.1.2'); }, getId: function() { return __vstr('26.1.2'); }, $packVersion: function() { return 18; }, packVersion: function() { return 18; }, $getWorldVersion: function() { return 4189; }, getWorldVersion: function() { return 4189; }, $getResourcePackFormat: function() { return 18; }, getResourcePackFormat: function() { return 18; }, $getDataPackFormat: function() { return 18; }, getDataPackFormat: function() { return 18; }, $stable: function() { return 1; }, stable: function() { return true; }, $isStable: function() { return 1; }, isStable: function() { return true; }, $isSnapshot: function() { return 0; }, isSnapshot: function() { return false; } };"""

    if old in data:
        data = data.replace(old, new)
        print("  Patched getCurrentVersion with dummy version return")
    else:
        print("  WARNING: getCurrentVersion pattern not found")
    return data


def patch_gl_getstring(data):
    """
    Patch WebGL glGetString calls.

    WebGL contexts expose no getString() method — that is native OpenGL ES.
    The WebGL equivalents are getParameter() with the same GL enum constants:
      GL_VENDOR = 7936, GL_RENDERER = 7937, GL_VERSION = 7938,
      GL_EXTENSIONS = 7939, GL_SHADING_LANGUAGE_VERSION = 35724.

    TeaVM compiles the platform glue as e.g.
      nlevi_PlatformOpenGL_gl.getString(7938)
    which throws "gl.getString is not a function" and crashes boot inside
    nlev_EaglerCraft_initialize. Requiring a numeric literal argument keeps
    the rewrite away from unrelated Java-level getString() methods, which
    are always receiver-calls without a constant argument.
    """
    import re as _re_gl
    pattern = _re_gl.compile(r'\.getString\((\d+)\)')
    count = 0

    def _replace(m):
        nonlocal count
        count += 1
        return f'.getParameter({m.group(1)})'

    data = pattern.sub(_replace, data)
    if count > 0:
        print(f"  Replaced {count} gl.getString(N) calls with getParameter(N)")
    else:
        print("  No gl.getString(N) calls found (may already be patched)")
    return data


def ensure_safe_helper(data):
    """
    Ensure the __safe null-guard helper is defined at the top of the file.

    Many patches (null-return stubs, .data field guards, null-safe method
    calls, BigInt wrappers) emit references to __safe even when an
    individual patch matched zero sites, so the helper must ALWAYS be
    defined. Without it every wrapped site throws "__safe is not defined",
    which the ClinitWrap swallows and leaves whole classes uninitialized.
    """
    if 'var __safe = function' in data:
        print("  __safe helper already defined")
        return data
    helper = """
// Null-return stub helper: returns obj as-is if non-null, shared stub if null.
// The stub is a CALLABLE Proxy — can be used as both object and function.
// ALL property accesses return the stub itself, so any chain works:
//   __safe(null).a.b.c.d -> stub (no throw)
//   __safe(null).method() -> stub (callable)
//   __safe(null).data -> [] (array-like)
//   __safe(null) + 1 -> 1 (numeric)
//   __safe(null) + '' -> '' (string)
var __safeStub = null;
var __safe = function(obj) {
    if (obj !== null && obj !== undefined) return obj;
    if (__safeStub) return __safeStub;
    var emptyArray = [];
    __safeStub = new Proxy(function(){}, {
        get: function(t, p) {
            if (p === '$id$') return 0;
            if (p === 'data') return emptyArray;
            if (p === 'length') return 0;
            if (p === 'valueOf') return function() { return 0; };
            if (p === 'toString') return function() { return ''; };
            if (p === 'constructor') return function() { return __safeStub; };
            if (typeof p === 'symbol') return undefined;
            if (typeof p === 'number') return undefined;
            // Return null for ALL property accesses
            // This allows while(x.field!==null) loops to terminate
            // For method calls, use (obj.method||noop)() pattern which
            // checks if method exists before calling
            return null;
        },
        apply: function() {
            // When called as a function, return null (not stub)
            // This allows while(x.method()!==null) loops to terminate
            return null;
        },
        has: function() { return true; }
    });
    return __safeStub;
};
"""
    use_strict = '"use strict";\n'
    if use_strict in data[:64]:
        data = data.replace(use_strict, use_strict + helper, 1)
    else:
        data = helper + data
    print("  Defined __safe null-guard helper at top of file")
    return data


def patch_mainclass_stack_trace(data):
    """
    Make the MainClass entry catch print the Java stack trace to console.

    nlevit_MainClass_main wraps the whole client init in try/catch and only
    forwards exception.getMessage() to the crash screen. For browser boot
    failures (e.g. "Illegal invocation") the message alone is not enough to
    localise the fault, while nlev_EaglerCraft_initialize's own catch block
    already prints full stack traces via jl_Throwable_getStackTrace. Mirror
    that behaviour so every crash is diagnosable from the console.
    """
    anchor = 'nlevit_MainClass_main = var$1 => {'
    idx = data.find(anchor)
    if idx < 0:
        print("  WARNING: nlevit_MainClass_main not found")
        return data
    window_text = data[idx:idx + 4000]
    marker = 'if ($$je instanceof jl_Throwable) {'
    midx = window_text.find(marker)
    if midx < 0:
        print("  WARNING: MainClass catch marker not found")
        return data
    insert_at = idx + midx + len(marker)
    stack_print = (
        '\n                        try { var __st = (jl_Throwable_getStackTrace($$je)).data;'
        ' for (var __i = 0; __i < __st.length; __i++)'
        ' console.error("  at " + jl_StackTraceElement_toString(__st[__i])); }'
        ' catch (__e2) { console.error("  (no stack trace available)"); }'
    )
    data = data[:insert_at] + stack_print + data[insert_at:]
    print("  MainClass catch now prints stack trace to console")
    return data


def patch_audio_worklet_check(data):
    """
    Replace the audio-worklet feature probe with a non-invoking check.

    'AudioContext.prototype.audioWorklet' invokes the native audioWorklet
    accessor with a plain prototype object as receiver, which throws
    "Illegal invocation" in Chromium and crashed nlevi_PlatformAudio__init
    (killing the whole client init). The 'in' operator tests for the
    property without invoking any getter.
    """
    old = "!!(window.AudioContext && AudioContext.prototype.audioWorklet) ? 1 : 0"
    new = "(window.AudioContext && ('audioWorklet' in AudioContext.prototype)) ? 1 : 0"
    if old in data:
        data = data.replace(old, new)
        print("  Replaced audioWorklet prototype access with 'in' check")
    elif "'audioWorklet' in AudioContext.prototype" in data:
        print("  audioWorklet check already patched")
    else:
        print("  WARNING: audioWorklet pattern not found")
    return data


def patch_long_time_functions(data):
    """
    Make the platform time helpers return TeaVM longs (BigInt).

    TeaVM represents Java long as native JS BigInt, but the hand-written
    js_body time helpers return plain JS numbers:
      nlevit_ClientMain_getCurrentTimeMillis$js_body$_25
      nlevi_PlatformRuntime_getCurrentTimeMillis0$js_body$_39
      nlevi_PlatformRuntime_getTimeOrigin0$js_body$_40
    The game tick feeds those numbers into Long_sub/Long_add, which then
    throws "Cannot mix BigInt and other types, use explicit conversions"
    and kills the frame loop on every animation frame. Wrap the raw number
    in Long_fromNumber() so every helper returns a proper BigInt long.
    """
    fixes = [
        (
            "        return Math.floor(window.performance.now());\n    } else {\n        return Date.now();",
            "        return Long_fromNumber(Math.floor(window.performance.now()));\n    } else {\n        return Long_fromNumber(Date.now());",
        ),
        (
            "        return Math.floor(window.performance.timeOrigin);",
            "        return Long_fromNumber(Math.floor(window.performance.timeOrigin));",
        ),
    ]
    for old, new in fixes:
        n = data.count(old)
        if n:
            data = data.replace(old, new)
            print(f"  Wrapped {n} raw time return(s) in Long_fromNumber ({old.strip()[7:47]}...)")
        else:
            print(f"  WARNING time pattern not found: {old.strip()[:50]}")
    return data


def patch_long_arithmetic(data):
    """
    Harden every Long_* helper against non-BigInt operands.

    TeaVM models Java long as native BigInt, so any stray plain JS number
    that reaches Long_add/Long_sub/Long_compare/... throws "Cannot mix
    BigInt and other types" (in the game tick that kills the entire frame
    loop). Coerce operands through __toBigInt() instead of throwing.
    """
    helper = """
var __toBigInt = function(v) {
    if (typeof v === 'bigint') return v;
    if (v === null || v === undefined) return 0n;
    if (typeof v === 'number') {
        if (v !== v || v === Infinity || v === -Infinity) return 0n;
        return BigInt(Math.trunc(v));
    }
    try { return BigInt(v); } catch (e) { return 0n; }
};
"""
    if 'var __toBigInt = function' not in data:
        marker = 'var __safeAsIntN = function'
        if marker in data:
            data = data.replace(marker, helper + '\n' + marker, 1)
            print("  Added __toBigInt coercion helper at top of file")
        else:
            data = helper + data
            print("  Added __toBigInt coercion helper (prepended — __safeAsIntN marker missing)")

    ops = [
        ('Long_fromNumber = val => __safeAsIntN(64, BigInt(val >= 0 ? Math.floor(val) : Math.ceil(val)))',
         'Long_fromNumber = val => { if (typeof val === "bigint") return __safeAsIntN(64, val); if (typeof val !== "number" || val !== val || val === Infinity || val === -Infinity) return 0n; return __safeAsIntN(64, BigInt(val >= 0 ? Math.floor(val) : Math.ceil(val))); }'),
        ('Long_hi = val => Number(__safeAsIntN(64, val >> BigInt(32))) | 0',
         'Long_hi = val => Number(__safeAsIntN(64, __toBigInt(val) >> BigInt(32))) | 0'),
        ('Long_lo = val => Number(__safeAsIntN(32, val)) | 0',
         'Long_lo = val => Number(__safeAsIntN(32, __toBigInt(val))) | 0'),
        ('Long_eq = (a, b) => a === b',
         'Long_eq = (a, b) => __toBigInt(a) === __toBigInt(b)'),
        ('Long_ne = (a, b) => a !== b;',
         'Long_ne = (a, b) => __toBigInt(a) !== __toBigInt(b);'),
        ('Long_gt = (a, b) => a > b',
         'Long_gt = (a, b) => __toBigInt(a) > __toBigInt(b)'),
        ('Long_ge = (a, b) => a >= b',
         'Long_ge = (a, b) => __toBigInt(a) >= __toBigInt(b)'),
        ('Long_lt = (a, b) => a < b',
         'Long_lt = (a, b) => __toBigInt(a) < __toBigInt(b)'),
        ('Long_le = (a, b) => a <= b',
         'Long_le = (a, b) => __toBigInt(a) <= __toBigInt(b)'),
        ('Long_add = (a, b) => __safeAsIntN(64, a + b)',
         'Long_add = (a, b) => __safeAsIntN(64, __toBigInt(a) + __toBigInt(b))'),
        ('Long_neg = a => __safeAsIntN(64,  -a)',
         'Long_neg = a => __safeAsIntN(64, -__toBigInt(a))'),
        ('Long_sub = (a, b) => __safeAsIntN(64, a - b)',
         'Long_sub = (a, b) => __safeAsIntN(64, __toBigInt(a) - __toBigInt(b))'),
        ('Long_compare = (a, b) => a < b ?  -1 : a > b ? 1 : 0',
         'Long_compare = (a, b) => { a = __toBigInt(a); b = __toBigInt(b); return a < b ? -1 : a > b ? 1 : 0; }'),
        ('Long_mul = (a, b) => __safeAsIntN(64, a * b)',
         'Long_mul = (a, b) => __safeAsIntN(64, __toBigInt(a) * __toBigInt(b))'),
        ('Long_div = (a, b) => __safeAsIntN(64, a / b)',
         'Long_div = (a, b) => __safeAsIntN(64, __toBigInt(a) / __toBigInt(b))'),
        ('Long_udiv = (a, b) => __safeAsIntN(64, __safeAsUintN(64, a) / __safeAsUintN(64, b))',
         'Long_udiv = (a, b) => __safeAsIntN(64, __safeAsUintN(64, __toBigInt(a)) / __safeAsUintN(64, __toBigInt(b)))'),
        ('Long_rem = (a, b) => __safeAsIntN(64, a % b)',
         'Long_rem = (a, b) => __safeAsIntN(64, __toBigInt(a) % __toBigInt(b))'),
        ('Long_urem = (a, b) => __safeAsIntN(64, __safeAsUintN(64, a) % __safeAsUintN(64, b))',
         'Long_urem = (a, b) => __safeAsIntN(64, __safeAsUintN(64, __toBigInt(a)) % __safeAsUintN(64, __toBigInt(b)))'),
        ('Long_and = (a, b) => __safeAsIntN(64, a & b)',
         'Long_and = (a, b) => __safeAsIntN(64, __toBigInt(a) & __toBigInt(b))'),
        ('Long_or = (a, b) => __safeAsIntN(64, a | b)',
         'Long_or = (a, b) => __safeAsIntN(64, __toBigInt(a) | __toBigInt(b))'),
        ('Long_xor = (a, b) => __safeAsIntN(64, a ^ b)',
         'Long_xor = (a, b) => __safeAsIntN(64, __toBigInt(a) ^ __toBigInt(b))'),
        ('Long_shl = (a, b) => __safeAsIntN(64, a << BigInt(b & 63))',
         'Long_shl = (a, b) => __safeAsIntN(64, __toBigInt(a) << BigInt(Number(b) & 63))'),
        ('Long_shr = (a, b) => __safeAsIntN(64, a >> BigInt(b & 63))',
         'Long_shr = (a, b) => __safeAsIntN(64, __toBigInt(a) >> BigInt(Number(b) & 63))'),
        ('Long_shru = (a, b) => __safeAsIntN(64, __safeAsUintN(64, a) >> BigInt(b & 63))',
         'Long_shru = (a, b) => __safeAsIntN(64, __safeAsUintN(64, __toBigInt(a)) >> BigInt(Number(b) & 63))'),
    ]
    count = 0
    for old, new in ops:
        if old in data:
            data = data.replace(old, new, 1)
            count += 1
        else:
            print(f"  WARNING Long pattern not found: {old[:64]}")
    print(f"  Hardened {count}/{len(ops)} Long helpers with __toBigInt coercion")
    return data


def patch_classes_js(input_path, output_path):
    """Patch classes.js with default method workaround."""
    with open(input_path, 'r', encoding='utf-8') as f:
        data = f.read()

    # Idempotency guard: re-running on an already-patched bundle double-
    # applies the textual passes and corrupts the Long helpers (observed
    # as a BigInt "Division by zero" at boot). The __safe helper plus a
    # hardened Long helper means this file was patched already.
    if '__safe null-guard helper' in data[:6000] and \
                    'Long_div = (a, b) => __safeAsIntN(64, __toBigInt(a)' in data:
        print("Already-patched classes.js detected - copying through unchanged")
        if input_path != output_path:
            import shutil
            shutil.copyfile(input_path, output_path)
        return

    # __safe is referenced by many patches below — always define it first.
    print("\nEnsuring __safe null-guard helper is defined...")
    data = ensure_safe_helper(data)

    print(f"Scanning {len(data)} bytes for default method definitions...")

    registry = find_default_methods(data)
    total_methods = sum(len(m) for m in registry.values())
    print(f"Found {len(registry)} interfaces with {total_methods} default methods:")
    for iface, methods in sorted(registry.items()):
        print(f"  {iface}: {len(methods)} methods")

    # Wrap all __clinit_ functions in try/catch (textual replacement)
    print("\nWrapping __clinit_ functions in try/catch...")
    data = wrap_clinits_textually(data)

    # Wrap the obfuscated clinit template in try/catch
    # TeaVM 0.15 obfuscated builds use: ()=>{m.clinit=()=>{};clinit();}
    # This template is used for ALL class clinits. Wrapping it once protects all.
    print("\nWrapping obfuscated clinit template in try/catch...")
    obf_clinit_old = '()=>{m.clinit=()=>{};clinit();}'
    obf_clinit_new = '()=>{m.clinit=()=>{};try{clinit();}catch(e){if(typeof console!=="undefined")console.warn("[ClinitWrap]",(m&&m.$name)?m.$name:"?","|",(e&&e.stack)?e.stack.split("\\n").slice(0,5).join(" | "):(e&&e.message?e.message:e));}}'
    if obf_clinit_old in data:
        data = data.replace(obf_clinit_old, obf_clinit_new)
        print("  Wrapped obfuscated clinit template (protects all class clinits)")
    else:
        print("  Obfuscated clinit template not found (may be unobfuscated build)")

    # Wrap lazy clinit initializer body calls in try/catch
    # Pattern: FUNC=()=>{FUNC=U(CLASS);BODYFUNC();}
    # The BODYFUNC() is the actual clinit body. If it throws, the class
    # is left in an invalid state. Wrap BODYFUNC() in try/catch.
    print("\nWrapping lazy clinit initializer body calls in try/catch...")
    import re as _re_lazy
    lazy_pattern = _re_lazy.compile(r'(\w+=\(\)=>\{\w+=U\(\w+\);)(\w+)\(\);\}')
    lazy_count = 0
    def lazy_replace(m):
        nonlocal lazy_count
        lazy_count += 1
        prefix = m.group(1)
        body_func = m.group(2)
        return f'{prefix}try{{{body_func}();}}catch(__e){{if(typeof console!=="undefined")console.warn("[ClinitWrap]","{body_func}","|",__e&&__e.stack?__e.stack.split("\\n").slice(0,3).join(" | "):(__e&&__e.message?__e.message:__e));}}}}'
    data = lazy_pattern.sub(lazy_replace, data)
    if lazy_count > 0:
        print(f"  Wrapped {lazy_count} lazy clinit body calls in try/catch")

    # Patch null-return stubs to return their first argument instead of null
    # This fixes DataFixers DSL builder chain crashes (CY returns null → .fa() crashes)
    print("\nPatching null-return stubs...")
    data = patch_null_return_stubs(data)

    # WebGL has no gl.getString() — rewrite to getParameter(GL_enum)
    # (GL_VENDOR/GL_RENDERER/GL_VERSION lookups crash nlev_EaglerCraft_initialize)
    print("\nPatching gl.getString → gl.getParameter...")
    data = patch_gl_getstring(data)

    # Crash screens without stack traces are undebuggable — print the stack
    print("\nPatching MainClass catch to print stack traces...")
    data = patch_mainclass_stack_trace(data)

    # audioWorklet feature probe threw "Illegal invocation" and killed audio init
    print("\nPatching audioWorklet feature check...")
    data = patch_audio_worklet_check(data)

    # Patch the $id$ function to be null-safe
    # TeaVM has a function like: X=a=>{let b;b=a;if(!b.$id$)b.$id$=Y();return a.$id$;}
    # When a is null, b.$id$ crashes. Add null check.
    print("\nPatching $id$ function for null safety...")
    import re as _re_id
    # Pattern: =a=>{let b;b=a;if(!b.$id$)b.$id$=WORD();return a.$id$;}
    id_pattern = _re_id.compile(
        r'=(\w)=>\{let (\w);\2=\1;if\(!\2\.\$id\$\)\2\.\$id\$=(\w+)\(\);return \1\.\$id\$;\}'
    )
    id_match = id_pattern.search(data)
    if id_match:
        param = id_match.group(1)
        bvar = id_match.group(2)
        gen_func = id_match.group(3)
        old_text = id_match.group(0)
        new_text = f'={param}=>{{if({param}===null||{param}===undefined)return 0;let {bvar};{bvar}={param};if(!{bvar}.$id$){bvar}.$id$={gen_func}();return {param}.$id$;}}'
        data = data.replace(old_text, new_text, 1)
        print(f"  Patched $id$ function (null check added)")
    else:
        print("  $id$ function pattern not found")

    # Patch the .constructor function to be null-safe
    # TeaVM has: X=a=>{return a.constructor;}
    # When a is null, a.constructor crashes. Add null check.
    print("\nPatching .constructor function for null safety...")
    import re as _re_ctor
    ctor_pattern = _re_ctor.compile(r'=(\w)=>\{return \1\.constructor;\}')
    ctor_match = ctor_pattern.search(data)
    if ctor_match:
        param = ctor_match.group(1)
        old_text = ctor_match.group(0)
        new_text = f'={param}=>{{if({param}===null||{param}===undefined)return Object;return {param}.constructor;}}'
        data = data.replace(old_text, new_text, 1)
        print(f"  Patched .constructor function (null check added)")
    else:
        print("  .constructor function pattern not found")

    # Patch the Java exception throw function to set a flag instead of throwing
    # TeaVM uses: V=ex=>{throw BN7(ex);} to throw Java exceptions
    # Setting a flag allows the coroutine to check it and unwind properly
    print("\nPatching Java exception throw function to use flag...")
    import re as _re_throw
    throw_pattern = _re_throw.compile(r'(\w+)=(\w+)=>\{throw (\w+)\(\2\);\}')
    throw_match = throw_pattern.search(data)
    if throw_match:
        fname = throw_match.group(1)
        param = throw_match.group(2)
        throw_func = throw_match.group(3)
        old_text = throw_match.group(0)
        # Instead of no-op, set a global exception flag and return
        # The caller should check the flag after calling V
        new_text = f'{fname}={param}=>{{window.__javaException={param};{throw_func}({param});}}'
        data = data.replace(old_text, new_text, 1)
        print(f"  Patched {fname} to set exception flag before throwing")
    else:
        print("  Throw function pattern not found")
    
    # Patch the classObject function to be null-safe
    # TeaVM has: X=cls=>{if(cls[SYM].classObject===null){...
    # When cls[SYM] is undefined, .classObject crashes. Add null check.
    print("\nPatching classObject function for null safety...")
    import re as _re_co
    co_pattern = _re_co.compile(r'=(\w+)=>\{if\(\1\[(\w+)\]\.classObject===null\)\{')
    co_match = co_pattern.search(data)
    if co_match:
        param = co_match.group(1)
        sym = co_match.group(2)
        old_text = co_match.group(0)
        new_text = f'={param}=>{{if({param}[{sym}]===undefined||{param}[{sym}]===null)return null;if({param}[{sym}].classObject===null){{'
        data = data.replace(old_text, new_text, 1)
        print(f"  Patched classObject function (null check added)")
    else:
        print("  classObject function pattern not found")

    # Wrap null-unsafe property accesses with __safe()
    # Pattern: $z=VAR.METHOD( where VAR could be null
    # Replace: $z=__safe(VAR).METHOD(
    # This prevents "Cannot read properties of null" crashes
    print("\nWrapping null-unsafe property accesses with __safe()...")
    import re as _re_safe
    
    # Run chained pattern FIRST: =VAR.field1.field2 → =__safe(VAR.field1).field2
    # DISABLED: Same issue as single-variable pattern — causes "a is not defined"
    # ReferenceErrors when the regex matches variables not in scope.
    # safe_pattern2 = _re_safe.compile(
    #     r'=([a-z]\w{0,1})\.([A-Za-z_$][A-Za-z0-9_$]{0,4})\.([A-Za-z_$][A-Za-z0-9_$]{0,4})([;(,.\[])'
    # )
    # safe_count2 = 0
    # def __safe_replace2(m):
    #     nonlocal safe_count2
    #     safe_count2 += 1
    #     v = m.group(1); f1 = m.group(2); f2 = m.group(3); t = m.group(4) if m.group(4) else '('
    #     return '=__safe(' + v + '.' + f1 + ').' + f2 + t
    # data = safe_pattern2.sub(__safe_replace2, data)
    # if safe_count2 > 0:
    #     print(f"  Wrapped {safe_count2} chained property accesses")
    print(f"  Chained __safe pattern DISABLED (causes ReferenceError)")
    
    # Patch functions containing .itemType to be null-safe with try/catch
    # Match: X=a=>{let b;b=...itemType...;return ...;}
    import re as _re_it
    it_pattern = _re_it.compile(r'(\w+)=(\w)=>\{(let [^;]+;[^}]*itemType[^}]*return [^}]+);\}')
    it_count = 0
    for m in it_pattern.finditer(data):
        fname = m.group(1)
        param = m.group(2)
        body = m.group(3)
        old_text = m.group(0)
        new_text = f'{fname}={param}=>{{try{{{body};}}catch(e){{return null;}}}}'
        data = data.replace(old_text, new_text, 1)
        it_count += 1
    if it_count > 0:
        print(f"  Patched {it_count} itemType functions with try/catch")
    
    # Then run single pattern: =VAR.field( → =__safe(VAR).field(
    # DISABLED: This pattern was causing "a is not defined" ReferenceErrors
    # because the regex matches variable names that aren't in scope (e.g.,
    # inside string literals, or in functions where 'a' is not a parameter).
    # The __safe(a) call evaluates 'a' first, which throws ReferenceError
    # before __safe can check for null.
    # The chained pattern above is sufficient for most null-safety cases.
    safe_pattern = _re_safe.compile(
        r'=([a-z]\w{0,1})\.([A-Za-z_$][A-Za-z0-9_$]{0,4})([;(,.\[])'
    )
    safe_count = 0
    # Commented out to prevent "a is not defined" errors
    # data = safe_pattern.sub(__safe_replace, data)
    # if safe_count > 0:
    #     print(f"  Wrapped {safe_count} null-unsafe property accesses")
    print(f"  Single-variable __safe pattern DISABLED (causes ReferenceError)")

    # Wrap ONLY clinit body calls in try/catch (targeted, not broad regex)
    # Clinit functions have the pattern: FLAG=true;$p=1;case 1:BODYFUNC();if(D()){break _;}
    # We wrap only BODYFUNC() — the actual initialization code.
    # This prevents DataFixers init crashes without breaking other code.
    print("\nWrapping clinit body calls in try/catch (targeted)...")
    import re as _re_clinit
    # Pattern: =true;$p=1;case 1:FUNCNAME();if(D()){break _;}
    # The "=true;$p=1;case 1:" prefix identifies this as a clinit body call
    # (not just any func();if(D()){break _;} pattern)
    clinit_pattern = _re_clinit.compile(
        r'(=true;\$p=1;case 1:)([A-Za-z_$][A-Za-z0-9_$]*)\(\);if\(D\(\)\)\{break _;\}'
    )
    clinit_count = 0
    for m in clinit_pattern.finditer(data):
        prefix = m.group(1)
        func_name = m.group(2)
        old_text = m.group(0)
        new_text = (
            f'{prefix}try{{{func_name}();}}catch(__e)'
            f'{{if(typeof window!=="undefined")window.__lastClinitError={{func:"{func_name}",error:__e}};'
            f'if(typeof console!=="undefined")console.warn("[ClinitWrap]",'
            f'"{func_name}","|",__e&&__e.stack?__e.stack.split("\\n").slice(0,5).join(" | "):(__e&&__e.message?__e.message:__e));}}if(D()){{break _;}}'
        )
        data = data.replace(old_text, new_text, 1)
        clinit_count += 1
    if clinit_count > 0:
        print(f"  Wrapped {clinit_count} clinit body calls in try/catch")
    else:
        print("  No clinit body calls found (pattern may differ)")


    # Fix TeaVM bug: some arrow functions use variables without declaring them
    # This causes ReferenceError in strict mode.
    # The comprehensive fix: remove "use strict" so undeclared assignments
    # create global variables instead of throwing.
    # This is safe because TeaVM's code doesn't rely on strict mode semantics
    # (no arguments.callee, no caller/callee properties, etc.)
    print("\nRemoving 'use strict' to fix undeclared variable ReferenceErrors...")
    if data.startswith('"use strict";'):
        data = data[len('"use strict";'):]
        print("  Removed 'use strict' directive")
    elif '"use strict";\n' in data[:100]:
        data = data.replace('"use strict";\n', '', 1)
        print("  Removed 'use strict' directive (with newline)")

    # Also fix specific patterns as a backup
    print("Fixing specific undeclared variable patterns...")
    import re as _re_undecl

    # Pattern 1: FUNC=()=>{VAR=EXPR;return EXPR;} where VAR is not preceded by let/var
    undecl_pattern1 = _re_undecl.compile(
        r'(\w+=\(\)=>\{)([a-z])(=[^;]+;return\s)'
    )
    undecl_count = 0
    def undecl_replace1(m):
        nonlocal undecl_count
        undecl_count += 1
        return m.group(1) + 'let ' + m.group(2) + m.group(3)
    data = undecl_pattern1.sub(undecl_replace1, data)

    # Pattern 2: Functions that use 'a' without declaring it
    # Find: FUNC=PARAM=>{BODY} where BODY references 'a' but 'a' is not in PARAM
    # and BODY doesn't have 'let a' or 'var a'
    # Add 'let a;' at the start of the body
    func_pattern = _re_undecl.compile(r'(\w+=(\w)=>\{)([^}]+)\}')
    fixes2 = 0
    def fix_undecl_a(m):
        nonlocal fixes2
        prefix = m.group(1)  # FUNC=PARAM=>{
        param = m.group(2)   # PARAM
        body = m.group(3)    # BODY}
        
        # Check if body uses 'a' as a bare variable (not a.x, not a[, etc.)
        # and 'a' is not the parameter
        if param == 'a':
            return m.group(0)  # 'a' is the parameter, fine
        
        # Check if body already declares 'a'
        if _re_undecl.search(r'(?:let|var|const)\s+a\b', body):
            return m.group(0)  # already declared
        
        # Check if body references bare 'a'
        # Exclude: a., a[, a:, break a, continue a, a= (assignment is handled by pattern 1)
        bare_a = _re_undecl.findall(r'(?<![a-zA-Z0-9_$.])a(?![a-zA-Z0-9_$\[.:=])', body)
        # Also check for a= (assignment without let)
        assign_a = _re_undecl.findall(r'(?<![a-zA-Z0-9_$.])a=', body)
        
        if bare_a or assign_a:
            fixes2 += 1
            return prefix + 'let a;' + body + '}'
        return m.group(0)
    data = func_pattern.sub(fix_undecl_a, data)

    # Pattern 3: Functions with no params that use 'a'
    func_pattern2 = _re_undecl.compile(r'(\w+=\(\)=>\{)([^}]+)\}')
    fixes3 = 0
    def fix_undecl_a_noparam(m):
        nonlocal fixes3
        prefix = m.group(1)
        body = m.group(2)
        
        if _re_undecl.search(r'(?:let|var|const)\s+a\b', body):
            return m.group(0)
        
        bare_a = _re_undecl.findall(r'(?<![a-zA-Z0-9_$.])a(?![a-zA-Z0-9_$\[.:=])', body)
        assign_a = _re_undecl.findall(r'(?<![a-zA-Z0-9_$.])a=', body)
        
        if bare_a or assign_a:
            fixes3 += 1
            return prefix + 'let a;' + body + '}'
        return m.group(0)
    data = func_pattern2.sub(fix_undecl_a_noparam, data)

    total_fixes = undecl_count + fixes2 + fixes3
    if total_fixes > 0:
        print(f"  Fixed {undecl_count} (pattern 1) + {fixes2} (pattern 2) + {fixes3} (pattern 3) = {total_fixes} undeclared variables")

    # Add missing uk method to DDw (TemplateCollections$SingleElementList)
    # Make ALL .uk() calls null-safe using regex (obfuscated names change each build)
    print("\nPatching ALL .uk() calls to be null-safe...")
    import re as _re_uk
    uk_pattern = _re_uk.compile(r'(?<![\w$.])([a-zA-Z$\w]\w*(?:\.\w+)*)\.uk\(')
    uk_count = 0
    def uk_replace(m):
        nonlocal uk_count
        uk_count += 1
        obj = m.group(1)
        return f'(__safe({obj}).uk||function(){{return null;}})('
    data = uk_pattern.sub(uk_replace, data)
    if uk_count > 0:
        print(f"  Patched {uk_count} .uk() calls")

    # Make ALL .boR() calls null-safe (ComparatorMode.java cascading NPE)
    print("\nPatching ALL .boR() calls to be null-safe...")
    import re as _re_bor
    bor_pattern = _re_bor.compile(r'(?<![\w$.])([a-zA-Z$\w]\w*(?:\.\w+)*)\.boR\(')
    bor_count = 0
    def bor_replace(m):
        nonlocal bor_count
        bor_count += 1
        obj = m.group(1)
        return f'(__safe({obj}).boR||function(){{return null;}})('
    data = bor_pattern.sub(bor_replace, data)
    if bor_count > 0:
        print(f"  Patched {bor_count} .boR() calls")

    # Fix NPE: btB returns null, causing cascading NPEs.
    # Make ALL single-param arrow functions that return null return __safe(null) instead.
    # Pattern: FUNC=a=>{...case 0:return null;...} -> return __safe(null)
    # This is a broad fix that covers btB and similar stub methods.
    print("\nPatching null-returning stub methods to return __safe(null)...")
    import re as _re_null
    # Pattern: =a=>{...return null;...} where the function has the TeaVM coroutine pattern
    # More specifically: case 0:return null; -> case 0:return __safe(null);
    null_pattern = _re_null.compile(r'case 0:return null;')
    null_count = 0
    def null_replace(m):
        nonlocal null_count
        null_count += 1
        return 'case 0:return __safe(null);'
    data = null_pattern.sub(null_replace, data)
    if null_count > 0:
        print(f"  Patched {null_count} null-returning stubs to return __safe(null)")

    # Also make ALL .LK() calls null-safe as a backup
    print("\nPatching ALL .LK() calls to be null-safe...")
    import re as _re_lk
    lk_pattern = _re_lk.compile(r'(?<![\w$.])([a-zA-Z$\w]\w*(?:\.\w+)*)\.LK\(')
    lk_count = 0
    def lk_replace(m):
        nonlocal lk_count
        lk_count += 1
        obj = m.group(1)
        return f'(__safe({obj}).LK||function(){{return null;}})('
    data = lk_pattern.sub(lk_replace, data)
    if lk_count > 0:
        print(f"  Patched {lk_count} .LK() calls to be null-safe")

    # Make specific .cJ() calls null-safe (Gson type adapter method)
    # Can't use broad regex because arguments may contain nested parens
    # like d.cJ(h.dz()) which breaks the pattern
    print("\nPatching specific .cJ() calls to be null-safe...")
    import re as _re_cj
    # Only match simple arguments (no nested parens): VAR.cJ(simple_arg)
    # Pattern: VAR.cJ(VAR2) where VAR2 is a simple variable
    cj_pattern = _re_cj.compile(r'(?<![\w$.])([a-zA-Z$\w]\w*(?:\.\w+)*)\.cJ\(')
    cj_count = 0
    def cj_replace(m):
        nonlocal cj_count
        cj_count += 1
        obj = m.group(1)
        return f'(__safe({obj}).cJ||function(){{return null;}})('
    data = cj_pattern.sub(cj_replace, data)
    if cj_count > 0:
        print(f"  Patched {cj_count} .cJ() calls with simple args")

    # Wrap Gson AJM function in try/catch to prevent all Gson serialization errors
    # AJM is Gson.java:1387 (fromJson/toJson adapter dispatch)
    # It calls e.cJ(b), e.eY(), and many other methods on type adapters
    # Instead of patching each method, wrap the whole function in try/catch
    print("\nWrapping Gson functions in try/catch...")

    def wrap_function_trycatch(data, func_name):
        """Wrap a function body in try/catch returning null."""
        import re as _re
        pattern = _re.compile(r'\b' + _re.escape(func_name) + r'\s*=\s*\(([^)]*)\)\s*=>\s*\{')
        m = pattern.search(data)
        if not m:
            return data, False
        start = data.find('{', m.start()) + 1
        depth = 1
        end = start
        while depth > 0 and end < len(data):
            if data[end] == '{': depth += 1
            elif data[end] == '}': depth -= 1
            end += 1
        body = data[start:end-1]
        params = m.group(1)
        old_func = f'{func_name}=({params})=>{{{body}}}'
        new_func = f'{func_name}=({params})=>{{try{{{body}}}catch(__e){{return null;}}}}'
        if old_func in data:
            data = data.replace(old_func, new_func, 1)
            return data, True
        return data, False

    # Note: Function wrapping by obfuscated name (AJM, CCn, Qko, EfF, etc.)
    # has been REMOVED because obfuscated names change every build when
    # EaglerCraft.java is modified. Instead, we rely on:
    # 1. Null-safe method calls (.uk, .boR, .LK, .cJ, .ek_)
    # 2. __safe(null) for null-returning stubs
    # 3. ClinitWrap for clinit failures
    # 4. The __safe stub Proxy for null object access

    # Make .ek_() calls null-safe (method dispatch in MC constructor)
    # Use (VAR.ek_||function(){return null;})(args) pattern to handle any args
    print("\nPatching .ek_() calls to be null-safe...")
    import re as _re_ek
    ek_pattern = _re_ek.compile(r'(?<![\w$.])([a-zA-Z$\w]\w*(?:\.\w+)*)\.ek_\(')
    ek_count = 0
    def ek_replace(m):
        nonlocal ek_count
        ek_count += 1
        obj = m.group(1)
        return f'(__safe({obj}).ek_||function(){{return null;}})('
    data = ek_pattern.sub(ek_replace, data)
    if ek_count > 0:
        print(f"  Patched {ek_count} .ek_() calls")

    # Make additional method calls null-safe using the (__safe(VAR).method||noop)(args) pattern
    # These are methods that fail with "is not a function" or "reading X of undefined"
    # due to TeaVM dispatch bugs or uninitialized fields.
    # The regex captures full object expressions including field access chains:
    #   a.q1(       -> __safe(a).q1
    #   a.fEu.q1(   -> __safe(a.fEu).q1
    #   b.c.d.q1(   -> __safe(b.c.d).q1
    print("\nPatching additional method calls to be null-safe...")
    import re as _re_methods
    for method_name in ['eY', 'd_', 'PG', 'elf', 'dha', 'a7k', 'N4', 'q1', 'oz', 'b5', 'W', 'cI', 'X', 'bHU', 'ks', 'O', 'b8', 'dEy', 'W0', 'Ah', 'bZ8', 'P', 'bXt', 'dEA', 'hV']:
        # Match VAR.field.field.method( — capture the full object expression
        method_pattern = _re_methods.compile(
            r'(?<![\w$.])([a-zA-Z$\w]\w*(?:\.\w+)*)\.' + _re_methods.escape(method_name) + r'\('
        )
        method_count = 0
        def method_replace(m, _mn=method_name):
            nonlocal method_count
            method_count += 1
            obj_expr = m.group(1)
            return f'(__safe({obj_expr}).{_mn}||function(){{return null;}})('
        data = method_pattern.sub(method_replace, data)
        if method_count > 0:
            print(f"  Patched {method_count} .{method_name}() calls")

    # Make .data field accesses null-safe — MUST run BEFORE field access patches
    # Pattern: VAR.field.data -> (__safe(VAR.field).data||[])
    # Also handles when .data itself is null (not just the parent)
    print("\nPatching .data field accesses to be null-safe...")
    import re as _re_data
    # Match VAR.data where VAR can be a field access chain
    # Only match .data followed by . or [ (array-like access)
    data_pattern = _re_data.compile(
        r'(?<![\w$.])([a-zA-Z$\w]\w*(?:\.\w+)*)\.data(?=[.\[;,\)])'
    )
    data_count = 0
    def data_replace(m):
        nonlocal data_count
        data_count += 1
        obj_expr = m.group(1)
        return f'(__safe({obj_expr}).data||[])'
    data = data_pattern.sub(data_replace, data)
    if data_count > 0:
        print(f"  Patched {data_count} .data field accesses")

    # Also patch __safe(VAR).data which was already wrapped but data is null
    # Pattern: __safe(VAR).data. -> (__safe(VAR).data||[]).
    # This handles the case where __safe returns a real object but .data is null
    safe_data_pattern = _re_data.compile(r'__safe\(([^)]+)\)\.data(?=[.\[;,\)])')
    safe_data_count = 0
    def safe_data_replace(m):
        nonlocal safe_data_count
        safe_data_count += 1
        var = m.group(1)
        return f'(__safe({var}).data||[])'
    data = safe_data_pattern.sub(safe_data_replace, data)
    if safe_data_count > 0:
        print(f"  Patched {safe_data_count} __safe(VAR).data accesses")

    # Make field accesses null-safe — wrap with __safe
    # These are FIELD accesses (not method calls) that fail with
    # "Cannot read properties of undefined (reading 'FIELD')"
    # Pattern: VAR.FIELD -> __safe(VAR).FIELD
    print("\nPatching field accesses to be null-safe...")
    import re as _re_fields
    for field_name in ['fdU', 'fw$', 'f6r', 'fEu', 'fbq', 'fc2', 'fcM', 'fhf',
                       'gi1', 'fDY', 'htO', 'fsP', 'fnR', 'b5', 'fc0', 'bTt',
                       'ekN', 'fWu', 'g0p', 'hdT', 'gev', 'gpC', 'frw',
                       'gIP', 'gJw', 'gMN', 'gRl', 'g_H', 'gvc', 'gR1',
                       'hyH', 'hlW', 'hY_', 'hiv', 'gFc', 'giT', 'gWp',
                       'g$k', 'fbl', 'faa', 'fa9', 'fgh', 'gdS', 'gC9', 'ha0', 'ha',
                       'fg0', 'fmR', 'fdO', 'fh_', 'fiy', 'fpz', 'fdb', 'fFj']:
        field_pattern = _re_fields.compile(
            r'(?<![\w$.])([a-zA-Z$\w]\w*(?:\.\w+)*)\.' + _re_fields.escape(field_name) + r'(?![\w(])'
        )
        field_count = 0
        def field_replace(m, _fn=field_name):
            nonlocal field_count
            field_count += 1
            obj_expr = m.group(1)
            return f'__safe({obj_expr}).{_fn}'
        data = field_pattern.sub(field_replace, data)
        if field_count > 0:
            print(f"  Patched {field_count} .{field_name} field accesses")

    # Wrap array element access results with __safe
    # Pattern: =EXPR[VAR] -> =__safe(EXPR[VAR])
    # This prevents undefined from array access propagating to field access
    print("\nWrapping array access results with __safe...")
    import re as _re_arr
    # Match =[EXPR][VAR] where EXPR ends with .data or is a variable
    # Only match simple cases: =(VAR.data)[VAR] and =VAR[VAR]
    arr_pattern = _re_arr.compile(r'=(\w+\.\w+\[)(\w)(\])')
    arr_count = 0
    def arr_replace(m):
        nonlocal arr_count
        arr_count += 1
        prefix = m.group(1)
        idx_var = m.group(2)
        suffix = m.group(3)
        return f'=__safe({prefix}{idx_var}{suffix})'
    data = arr_pattern.sub(arr_replace, data)
    if arr_count > 0:
        print(f"  Wrapped {arr_count} array access results with __safe")

    # Fix ALL BigInt conversion errors by replacing BigInt.asIntN/asUintN
    # with safe wrappers that return 0n on error.
    # This handles ALL BigInt functions at once (BD, Gb, CP, T, JF, etc.)
    print("\nPatching ALL BigInt operations for null safety...")
    # Replace BigInt.asIntN( with __safeAsIntN(
    data = data.replace('BigInt.asIntN(', '__safeAsIntN(')
    # Replace BigInt.asUintN( with __safeAsUintN(
    data = data.replace('BigInt.asUintN(', '__safeAsUintN(')
    # Add safe wrapper definitions at the VERY TOP of the file
    # Must be before any function that uses them (line 49+)
    bigint_wrappers = """
var __safeAsIntN = function(bits, val) {
    if (val === null || val === undefined || typeof val !== 'bigint') return 0n;
    try { return BigInt.asIntN(bits, val); } catch(e) { return 0n; }
};
var __safeAsUintN = function(bits, val) {
    if (val === null || val === undefined || typeof val !== 'bigint') return 0n;
    try { return BigInt.asUintN(bits, val); } catch(e) { return 0n; }
};
"""
    data = bigint_wrappers + data
    print("  Added BigInt safe wrappers at top of file")
    # Wrap simple BigInt arrow functions in try/catch
    # Only match simple arrow functions (no braces) containing BigInt
    # Skip functions with { (if/else blocks) to avoid breaking syntax
    print("\nWrapping simple BigInt functions in try/catch...")
    import re as _re_bi_simple
    # Match: NAME=PARAM=>EXPR_WITH_BIGINT (no { in EXPR, ends at , or ;)
    bi_simple_pattern = _re_bi_simple.compile(
        r'(\w+=(?:\w+|\(\w+(?:,\w+)*\))=>)(BigInt[^,;{}]+)(?=[,;])'
    )
    bi_count = 0
    def bi_simple_replace(m):
        nonlocal bi_count
        prefix = m.group(1)
        body = m.group(2)
        bi_count += 1
        return f'{prefix}{{try{{return {body};}}catch(e){{return 0;}}}}'
    data = bi_simple_pattern.sub(bi_simple_replace, data)
    if bi_count > 0:
        print(f"  Wrapped {bi_count} simple BigInt functions")

    # Also directly patch Gb which has commas in its body
    # Gb=val=>Number(__safeAsIntN(64,val>>BigInt(32)))|0
    # The error: val>>BigInt(32) throws when val is a number
    gb_old = 'Gb=val=>Number(__safeAsIntN(64,val>>BigInt(32)))|0'
    gb_new = 'Gb=val=>{try{return Number(__safeAsIntN(64,val>>BigInt(32)))|0;}catch(e){return 0;}}'
    if gb_old in data:
        data = data.replace(gb_old, gb_new)
        print("  Patched Gb (direct replacement)")

    # Also patch Bo, JF, BX, CI, CP which use __safeAsIntN with arithmetic
    # These can fail if arguments aren't BigInts
    for func_name in ['Bo', 'JF', 'BX', 'CI', 'CP', 'T', 'KM']:
        # Find the function
        func_prefix = func_name + '='
        idx = data.find(func_prefix)
        if idx < 0:
            continue
        # Get the function text (up to next comma at depth 0)
        depth = 0
        end = idx
        for i in range(idx, min(len(data), idx+200)):
            c = data[i]
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
            elif c == ',' and depth == 0:
                end = i
                break
        func_text = data[idx:end]
        # Check if it has __safeAsIntN/__safeAsUintN and no try{
        if ('__safeAs' in func_text and 'try{' not in func_text and
            '=>' in func_text and '{' not in func_text[func_text.find('=>')+2:]):
            # Wrap in try/catch
            arrow_pos = func_text.find('=>')
            name_params = func_text[:arrow_pos+2]
            body = func_text[arrow_pos+2:]
            new_func = name_params + '{try{return ' + body + ';}catch(e){return 0;}}'
            data = data.replace(func_text, new_func, 1)
            print(f"  Patched {func_name} (try/catch wrapper)")
    # Platform time helpers must return TeaVM longs (BigInt), not JS numbers.
    # NOTE: must run after the BigInt wrappers section so Long_fromNumber is
    # the final form when the Long_* helpers are rewritten below.
    print("\nPatching platform time helpers to return BigInt longs...")
    data = patch_long_time_functions(data)

    # All Long_* helpers must tolerate stray plain numbers instead of throwing
    print("\nHardening Long arithmetic against BigInt/number mixing...")
    data = patch_long_arithmetic(data)

    print("\nPatching jl_Throwable_addSuppressed for null safety...")
    data = patch_add_suppressed(data)

    # Patch jnc_FileChannel_open to return a fake channel instead of throwing
    print("\nPatching jnc_FileChannel_open to return fake channel...")
    data = patch_file_channel_open(data)

    # Patch nmud_DataFixers_getDataFixer to handle null DATA_FIXER
    print("\nPatching DataFixers.getDataFixer for null safety...")
    data = patch_data_fixer(data)

    # Patch SharedConstants.getCurrentVersion to return dummy version
    print("\nPatching SharedConstants.getCurrentVersion for null safety...")
    data = patch_shared_constants(data)

    # Patch ji_File_toPath to return a non-null path (TeaVM's version returns null)
    print("\nPatching ji_File_toPath to return fake path...")
    # The original function body is: case 0: return null;
    # We replace 'return null;' with 'return (function() { ... })();'
    old_return = 'ji_File_toPath = var$0 => {\n    let $ptr, $tmp;\n    $ptr = 0;\n    if ($rt_resuming()) {\n        let $thread = $rt_nativeThread();\n        $ptr = $thread.pop();var$0 = $thread.pop();\n    }\n    main: while (true) { switch ($ptr) {\n    case 0:\n        return null;'
    if old_return in data:
        new_return = '''ji_File_toPath = var$0 => {
    var __p = var$0.$path || var$0.path || '/';
    var __pathObj = {
        $resolve: function(o) { return __pathObj; }, resolve: function(o) { return __pathObj; },
        $resolveSibling: function(o) { return __pathObj; }, resolveSibling: function(o) { return __pathObj; },
        $toString: function() { var s = new String(__p); s.$nativeString = __p; return s; }, toString: function() { return __p; },
        $getParent: function() { return __pathObj; }, getParent: function() { return __pathObj; },
        $getFileName: function() { return __pathObj; }, getFileName: function() { return __pathObj; },
        $getFileNameString: function() { return __p; }, getFileNameString: function() { return __p; },
        $getRoot: function() { return __pathObj; }, getRoot: function() { return __pathObj; },
        $isAbsolute: function() { return 1; }, isAbsolute: function() { return true; },
        $normalize: function() { return __pathObj; }, normalize: function() { return __pathObj; },
        $relativize: function(o) { return o; }, relativize: function(o) { return o; },
        $toAbsolutePath: function() { return __pathObj; }, toAbsolutePath: function() { return __pathObj; },
        $toFile: function() { return null; }, toFile: function() { return null; },
        $startsWith: function(p) { return 1; }, startsWith: function(p) { return true; },
        $endsWith: function(p) { return 1; }, endsWith: function(p) { return true; },
        $compareTo: function(o) { return 0; }, compareTo: function(o) { return 0; },
        $subpath: function(a,b) { return __pathObj; }, subpath: function(a,b) { return __pathObj; },
        $getNameCount: function() { return 1; }, getNameCount: function() { return 1; },
        $getName: function(i) { return __pathObj; }, getName: function(i) { return __pathObj; },
        $toRealPath: function() { return __pathObj; }, toRealPath: function() { return __pathObj; },
        $toUri: function() { return { toString: function() { return 'file://'+__p; }, $toString: function() { return 'file://'+__p; } }; },
        toUri: function() { return { toString: function() { return 'file://'+__p; }, $toString: function() { return 'file://'+__p; } }; },
        $getFileSystem: function() {
            if (__pathObj.__fs) return __pathObj.__fs;
            var __fsp = {};
            __fsp.$getScheme = function() { return 'file'; };
            __fsp.$readAttributes = function() { return { $isDirectory: function() { return 0; }, $isRegularFile: function() { return 0; }, $size: function() { return 0; }, $isReadable: function() { return 0; }, $isWritable: function() { return 0; }, $isHidden: function() { return 0; }, $lastModifiedTime: function() { return { toMillis: function() { return 0; } }; }, $toString: function() { return '{}'; } }; };
            __fsp.$newInputStream = function() { return { $read: function() { return -1; }, $available: function() { return 0; }, $close: function() {} }; };
            __fsp.$newDirectoryStream0 = function() { return { $iterator: function() { return { $hasNext: function() { return 0; }, $next: function() { return null; } }; }, $close: function() {} }; };
            __fsp.$exists = function() { return 0; };
            __fsp.$createDirectories = function() {};
            __fsp.$createDirectory = function() {};
            __fsp.$isDirectory = function() { return 0; };
            __fsp.$toString = function() { return 'file'; };
            var __fs = {};
            __fs.$provider = function() { return __fsp; };
            __fs.$isOpen = function() { return 1; };
            __fs.$close = function() {};
            __fs.$toString = function() { return 'file:///'; };
            __pathObj.__fs = __fs;
            return __fs;
        },
        getFileSystem: function() { return __pathObj.$getFileSystem(); }
    };
    return __pathObj;
    main: while (true) { switch (0) {
    case 0:
        return __pathObj;'''
        data = data.replace(old_return, new_return)
        print("  Replaced ji_File_toPath return null with fake path")
    else:
        print("  WARNING: ji_File_toPath original pattern not found - may already be patched")

    # Textual patches that use hardcoded function names — only apply in unobfuscated builds
    is_unobfuscated = 'ji_File_toPath' in data or 'jnf_Files_exists' in data
    if is_unobfuscated:
        print("\nApplying unobfuscated-only textual patches...")
        # Patch populatePackList
        if '$discoveredPacks = var$4.$path3;' in data:
            data = data.replace('$discoveredPacks = var$4.$path3;', 'if (var$4 === null || var$4 === undefined) { return; } $discoveredPacks = var$4.$path3;')
            print("  Patched populatePackList null check")
        # Patch getExternalAssetSource
        if '$tmp = var$2.$resolve(var$3);' in data:
            data = data.replace('$tmp = var$2.$resolve(var$3);', 'if (var$2 === null || var$2 === undefined) return null; $tmp = var$2.$resolve(var$3);')
            print("  Patched getExternalAssetSource null check")

        # Patch Files.exists
        if 'jnf_Files_exists = ($path, $options) => {' in data:
            data = data.replace('jnf_Files_exists = ($path, $options) => {', 'jnf_Files_exists = ($path, $options) => {\n    if ($path === null || $path === undefined) return 0;')
            print("  Patched Files.exists null check")
    else:
        print("\nSkipping unobfuscated-only textual patches (obfuscated build)")

    # Patch jl_Object_identity to handle null this (works for both builds)
    data = data.replace(
        'jl_Object_identity = $this => {',
        'jl_Object_identity = $this => {\n    if ($this === null || $this === undefined) return 0;'
    )
    print("  Patched Object.identity null check")

    # Patch HashMap.putAll to handle null map
    if 'ju_HashMap_putAll = ' in data:
        data = data.replace(
            'ju_HashMap_putAll = ',
            'ju_HashMap_putAll = nullSafePutAll || ' if False else 'ju_HashMap_putAll = '
        )
        # Actually just add a null check at the start
        data = data.replace(
            'ju_HashMap_putAll = ($this, $map) => {',
            'ju_HashMap_putAll = ($this, var$1) => {\n    if (var$1 === null || var$1 === undefined) return;'
        )
        print("  Patched HashMap.putAll null check")

    # Patch Collection.toArray to handle null generator
    if 'ju_Collection_toArray = ' in data:
        data = data.replace(
            '$tmp = $gen.$apply1(var$2);',
            'if ($gen === null || $gen === undefined) return []; $tmp = $gen.$apply1(var$2);'
        )
        print("  Patched Collection.toArray null check (inside case 1)")

    # Patch ImmutableMap.copyOf to handle undefined var$3.data
    # Find the function and replace within its body
    im_pos = data.find('cgcc_ImmutableMap_copyOf = ')
    if im_pos >= 0:
        # Find the var$4 = var$3.data; within this function (next 2000 chars)
        search_end = min(len(data), im_pos + 3000)
        search_chunk = data[im_pos:search_end]
        target = 'var$4 = var$3.data;'
        target_pos = search_chunk.find(target)
        if target_pos >= 0:
            abs_pos = im_pos + target_pos
            data = data[:abs_pos] + 'var$4 = (var$3 && var$3.data) ? var$3.data : {length: 0};' + data[abs_pos + len(target):]
            print("  Patched ImmutableMap.copyOf null check (within function)")
        else:
            print("  WARNING: var$4 = var$3.data not found in ImmutableMap.copyOf")
    else:
        print("  WARNING: ImmutableMap.copyOf not found")

    # Replace juc_Executors_newScheduledThreadPool body (returns null from bytecode patcher)
    # or add it if DCE'd entirely
    if 'juc_Executors_newScheduledThreadPool' in data and 'juc_Executors_newScheduledThreadPool =' in data:
        # Function exists but returns null — replace body
        import re
        pattern = r'(juc_Executors_newScheduledThreadPool\s*=\s*\([^)]*\)\s*=>\s*\{)[^}]*(?:\{[^}]*\}[^}]*)*\}'
        match = re.search(pattern, data)
        if match:
            new_body = '''juc_Executors_newScheduledThreadPool = (threadCount, threadFactory) => {
    return { $execute: function(r) { try { r.$run(); } catch(e) {} }, execute: function(r) { try { r.$run(); } catch(e) {} }, $shutdown: function() {}, shutdown: function() {}, $isShutdown: function() { return 0; }, $toString: function() { return 'fake-executor'; } };
}'''
            data = data[:match.start()] + new_body + data[match.end():]
            print("  Replaced juc_Executors_newScheduledThreadPool body")
    else:
        # Function doesn't exist — add it after newFixedThreadPool
        insert_after = 'juc_Executors_newFixedThreadPool = (var$1, var$2) => {\n    return new juc_Executors$1;\n},'
        if insert_after in data:
            new_func = '''juc_Executors_newFixedThreadPool = (var$1, var$2) => {
    return new juc_Executors$1;
},
juc_Executors_newScheduledThreadPool = (threadCount, threadFactory) => {
    return { $execute: function(r) { try { r.$run(); } catch(e) {} }, execute: function(r) { try { r.$run(); } catch(e) {} }, $shutdown: function() {}, shutdown: function() {}, $isShutdown: function() { return 0; }, $toString: function() { return 'fake-executor'; } };
},'''
            data = data.replace(insert_after, new_func)
            print("  Added juc_Executors_newScheduledThreadPool (was DCE'd)")
        else:
            print("  WARNING: Could not find insertion point for newScheduledThreadPool")

    # Find the actual variable names for the class registry and metadata Symbol
    # by scanning the source code. These names change between obfuscated builds
    # (e.g., FnY→ETN, GN→Gf) so we must detect them dynamically.
    import re as _re
    registry_names = []
    meta_names = []

    # Pattern: X.push(cls) — X is the class registry array
    for m in _re.finditer(r'(\w{1,8})\.push\(cls\)', data):
        name = m.group(1)
        if name not in registry_names:
            registry_names.append(name)

    # Pattern: X=Symbol("teavm_meta") — X is the metadata Symbol
    for m in _re.finditer(r'(\w{1,8})=Symbol\("teavm_meta"\)', data):
        name = m.group(1)
        if name not in meta_names:
            meta_names.append(name)

    # Also try unobfuscated patterns: X=[] ... X.push(cls)
    # And: X=Symbol("teavm_meta")

    print(f"\nDetected class registry names: {registry_names}")
    print(f"Detected metadata symbol names: {meta_names}")

    patcher = build_patcher_js(registry, registry_names, meta_names)

    # Insert the patcher INSIDE the TeaVM IIFE, right before the closing
    # The IIFE ends with: $rt_exports.main = $rt_export_main;\n}));
    # We insert before the last }));
    # This ensures the patcher has access to Fnk, GN, and all internal
    # variables (which are local to the IIFE, not global).
    insert_marker = '}));'
    last_pos = data.rfind(insert_marker)
    if last_pos < 0:
        # Fallback: try }))) (3 parens)
        insert_marker = '})))'
        last_pos = data.rfind(insert_marker)
    if last_pos < 0:
        # Fallback: try just appending
        print("WARNING: Could not find IIFE closing })); — appending at end")
        patched_data = data + "\n" + patcher
    else:
        patched_data = data[:last_pos] + "\n" + patcher + "\n" + data[last_pos:]

    with open(output_path, 'w', encoding='utf-8') as f:
        f.write(patched_data)

    print(f"\nPatched classes.js written to {output_path}")
    print(f"  Original: {len(data)} bytes")
    print(f"  Patched:  {len(patched_data)} bytes")
    print(f"  Patcher:  {len(patcher)} bytes")

    # Verify patches were applied
    print(f"\n=== Patch verification ===")
    print(f"  OxF=(b.uk?b.uk():null) in patched_data: {'OxF=(b.uk?b.uk():null)' in patched_data}")
    print(f"  OxF=b.uk() in patched_data: {'OxF=b.uk()' in patched_data}")
    print(f"  DDw this.uk in patched_data: {'this.uk=function' in patched_data}")
    print(f"  TfA __safe(null) in patched_data: {'__safe(null)' in patched_data}")


def patch_null_return_stubs(data):
    """
    Patch functions that return null (stubs from MissingMethodTransformer).
    
    Pattern: X=b=>{return null;}
    Replace with: X=b=>{return __safe(b);}
    
    __safe returns the object if non-null, or an empty object {} if null.
    This prevents 'Cannot read properties of null' crashes while keeping
    the returned object usable (though missing methods will still fail
    with 'is not a function').
    """
    import re
    
    # Pattern: name=param=>{return null;}
    pattern = re.compile(r'(\w{1,5})\s*=\s*(\w)\s*=>\s*\{return null;\}')
    
    count = 0
    patched = data
    for match in pattern.finditer(data):
        name = match.group(1)
        param = match.group(2)
        old = match.group(0)
        new = f'{name}={param}=>{{return __safe({param});}}'
        patched = patched.replace(old, new, 1)
        count += 1
    
    if count > 0:
        print(f"  Patched {count} null-return stubs to return __safe(obj)")
        # The helper itself is emitted unconditionally at the start of the
        # pipeline (ensure_safe_helper) — many other patches reference __safe
        # even when this patch matched zero sites.
    return patched

if __name__ == '__main__':
    input_path = sys.argv[1] if len(sys.argv) > 1 else 'public/classes.js'
    output_path = sys.argv[2] if len(sys.argv) > 2 else input_path
    patch_classes_js(input_path, output_path)

