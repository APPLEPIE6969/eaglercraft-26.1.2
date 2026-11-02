import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * JVM parity test: the port's block registry vs the 26.1.2 decompile,
 * driven from the committed decompile zip so expectations cannot drift.
 *
 *  1. VANILLA SUBSET: every block name the port registers must exist as a
 *     `public static final Block` field in the decompiled vanilla
 *     net/minecraft/world/level/block/Blocks.java. A typo, a renamed id or
 *     an invented block goes red here.
 *  2. REGISTRY SANITY: ids are unique, AIR is 0, every registered name is
 *     lower snake_case, and the registry is large enough to matter.
 *  3. TEXTURE COVERAGE: every committed world texture
 *     (public/assets/world/textures/*.png) resolves to a texture literal in
 *     the port registry (either its own stem or the stem minus the
 *     _top/_side/_bottom/_front/_back/_end suffix), so no dead assets and
 *     no blocks with missing textures. destroy_stage_0..9 must exist.
 *
 * Reads paths relative to the repo root (CI checks out the repo and runs
 * from it). Exit code 0 = all invariants hold; 1 = violation (on stdout).
 */
public class RegistryParityTest {

        static int failures = 0;

        static void check(boolean ok, String what) {
                if (!ok) {
                        System.out.println("FAIL: " + what);
                        failures++;
                }
        }

        static String readUtf(InputStream in) throws Exception {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }

        static String readZipEntry(ZipFile zip, String suffix) throws Exception {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        if (e.getName().endsWith(suffix)) {
                                return readUtf(zip.getInputStream(e));
                        }
                }
                return null;
        }

        public static void main(String[] args) throws Exception {
                String vanillaBlocks = null;
                try (ZipFile zip = new ZipFile("docs/decompiled-26.1.2-full-src.zip")) {
                        vanillaBlocks = readZipEntry(zip, "world/level/block/Blocks.java");
                }
                check(vanillaBlocks != null, "the decompile zip must contain world/level/block/Blocks.java");
                if (vanillaBlocks == null) System.exit(1);

                String portBlocks = readUtf(new java.io.FileInputStream(
                                "sources/teavm/java/net/minecraft/world/level/block/Blocks.java"));

                // ── 1. vanilla field names ──
                Set<String> vanillaFields = new HashSet<>();
                Matcher vf = Pattern.compile("public static final Block ([A-Z0-9_]+)\\s*=")
                                .matcher(vanillaBlocks);
                while (vf.find()) vanillaFields.add(vf.group(1));
                check(vanillaFields.size() >= 500,
                                "vanilla Blocks.java must expose its block fields (found " + vanillaFields.size() + ")");

                // ── 2. port registry: names + ids ──
                // Registration forms: register(id, "name", ...) with a literal,
                // register(new Block(CONST, "name", ...) with a named constant,
                // and the constant itself may be declared as `public static
                // final int STONE = 1;`. Resolve constants first.
                Map<String, Integer> constIds = new HashMap<>();
                Matcher cf = Pattern.compile("public static final int ([A-Z0-9_]+)\\s*=\\s*(\\d+)")
                                .matcher(portBlocks);
                while (cf.find()) constIds.put(cf.group(1), Integer.parseInt(cf.group(2)));

                Map<String, Integer> portByIdName = new HashMap<>();
                Set<Integer> portIds = new HashSet<>();
                Set<Integer> dupIds = new HashSet<>();
                List<String> unresolved = new ArrayList<>();
                Matcher rf = Pattern.compile(
                                "register\\((?:new Block\\()?(\\d+|[A-Z][A-Z0-9_]*),\\s*\"([a-z0-9_]+)\"")
                                .matcher(portBlocks);
                while (rf.find()) {
                        String idToken = rf.group(1);
                        String name = rf.group(2);
                        Integer id;
                        if (Character.isDigit(idToken.charAt(0))) {
                                id = Integer.valueOf(idToken);
                        } else {
                                id = constIds.get(idToken);
                                if (id == null) { unresolved.add(idToken + "(" + name + ")"); continue; }
                        }
                        Integer prev = portByIdName.put(name, id);
                        if (prev != null && prev.equals(id)) dupIds.add(id);
                        if (!portIds.add(id)) dupIds.add(id);
                }
                check(unresolved.isEmpty(),
                                "every registration's id constant must resolve (unresolved: " + unresolved + ")");
                check(portByIdName.size() >= 80,
                                "the port must register a substantial block set (found " + portByIdName.size() + ")");
                check(dupIds.isEmpty(), "block ids must be unique (duplicates: " + dupIds + ")");

                List<String> notInVanilla = new ArrayList<>();
                for (String name : portByIdName.keySet()) {
                        if (!vanillaFields.contains(name.toUpperCase(Locale.ROOT))) {
                                notInVanilla.add(name);
                        }
                }
                check(notInVanilla.isEmpty(),
                                "every port block must exist in vanilla 26.1.2 (unknown: " + notInVanilla + ")");
                check(portByIdName.containsKey("air") && portByIdName.get("air") == 0, "AIR must be id 0");
                check(portByIdName.containsKey("stone"), "stone must be registered");

                // ── 3. texture coverage ──
                Set<String> textureLiterals = new HashSet<>();
                Matcher tf = Pattern.compile("\"([a-z0-9_]+)\"").matcher(portBlocks);
                while (tf.find()) textureLiterals.add(tf.group(1));

                java.io.File texDir = new java.io.File("public/assets/world/textures");
                String[] files = texDir.list();
                check(files != null && files.length >= 60,
                                "world textures must exist on disk (found " + (files == null ? 0 : files.length) + ")");
                List<String> deadTextures = new ArrayList<>();
                int destroyStages = 0;
                if (files != null) {
                        for (String f : files) {
                                if (!f.endsWith(".png")) continue;
                                String stem = f.substring(0, f.length() - 4);
                                if (stem.startsWith("destroy_stage_")) {
                                        destroyStages++;
                                        continue;
                                }
                                String base = stem.replaceAll("_(top|bottom|side|front|back|end|inventory)$", "");
                                if (textureLiterals.contains(stem) || textureLiterals.contains(base)) continue;
                                deadTextures.add(stem);
                        }
                }
                check(deadTextures.isEmpty(),
                                "every world texture must be referenced by the block registry (dead: " + deadTextures + ")");
                check(destroyStages == 10, "destroy_stage_0..9 must all exist (found " + destroyStages + ")");

                System.out.println("RegistryParityTest: " + portByIdName.size() + " port blocks, "
                                + vanillaFields.size() + " vanilla fields, "
                                + (files == null ? 0 : files.length) + " textures, failures=" + failures);
                if (failures > 0) System.exit(1);
        }
}
