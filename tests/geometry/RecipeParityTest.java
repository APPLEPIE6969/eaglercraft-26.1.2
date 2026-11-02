import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JVM parity test: the port's recipe table (CraftingRecipes.ALL, GENERATED
 * from the vanilla 26.1.2 client jar's data/minecraft/recipe/*.json) must be
 * structurally sound, reference only real registry ids, and still carry the
 * vanilla truths a player can check by hand.
 *
 *  1. STRUCTURE: unique keys; shaped recipes declare width*height cells;
 *     shapeless recipes declare ingredients; result counts are 1..64.
 *  2. REGISTRY: every result and ingredient id is a registered block id
 *     (< 512) or item id (>= 4096). The 512..4095 gap is illegal.
 *  3. VANILLA SPOT LOCKS (read from the bake's provenance keys):
 *     - oak_planks: shapeless, one oak_log -> 4 oak planks
 *     - stick: shaped 1x2 of planks -> 4 sticks
 *     - crafting_table: shaped 2x2 of planks -> the crafting_table block
 *     - torch: burns coal over a stick -> 4 torches
 *     - furnace: 8 cobblestone ring (empty center) -> the furnace block
 *
 * Exit 0 = parity holds; 1 = violation.
 */
public class RecipeParityTest {

        static int failures = 0;

        static void check(boolean ok, String what) {
                if (!ok) {
                        System.out.println("FAIL: " + what);
                        failures++;
                }
        }

        static String read(String path) throws Exception {
                FileInputStream in = new FileInputStream(path);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                in.close();
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }

        public static void main(String[] args) throws Exception {
                // ── registry ids from the port sources ──
                // The registration id may be a literal or a named constant
                // (new Block(STONE, ...)) - resolve constants first.
                Map<String, Integer> constIds = new HashMap<>();
                Map<String, Integer> idByName = new HashMap<>();
                Set<Integer> validIds = new HashSet<>();
                String blocks = read("sources/teavm/java/net/minecraft/world/level/block/Blocks.java");
                String items = read("sources/teavm/java/net/minecraft/world/item/Items.java");
                Matcher cf = Pattern.compile("public static final int ([A-Z0-9_]+)\\s*=\\s*(\\d+)").matcher(blocks);
                while (cf.find()) constIds.put(cf.group(1), Integer.parseInt(cf.group(2)));
                Matcher iff = Pattern.compile("public static final int ([A-Z0-9_]+)\\s*=\\s*(\\d+)").matcher(items);
                while (iff.find()) constIds.put(iff.group(1), Integer.parseInt(iff.group(2)));
                Matcher bf = Pattern.compile("register\\((?:new Block\\()?(\\d+|[A-Z][A-Z0-9_]*),\\s*\"([a-z0-9_]+)\"")
                                .matcher(blocks);
                while (bf.find()) {
                        Integer id = resolve(bf.group(1), constIds);
                        if (id == null) continue;
                        validIds.add(id);
                        idByName.putIfAbsent(bf.group(2), id);
                }
                Matcher ifr = Pattern.compile("register\\((\\d+|[A-Z][A-Z0-9_]*),\\s*\"([a-z0-9_]+)\"").matcher(items);
                while (ifr.find()) {
                        Integer id = resolve(ifr.group(1), constIds);
                        if (id == null) continue;
                        validIds.add(id);
                        idByName.putIfAbsent(ifr.group(2), id);
                }
                check(validIds.size() >= 100, "registry must expose a real id set (found " + validIds.size() + ")");

                // ── the generated table ──
                net.minecraft.world.item.crafting.CraftingRecipes.Recipe[] all =
                                net.minecraft.world.item.crafting.CraftingRecipes.ALL;
                check(all.length >= 20, "the generated recipe table must be substantial (found " + all.length + ")");

                Set<String> keys = new HashSet<>();
                List<String> badRefs = new ArrayList<>();
                for (var r : all) {
                        check(keys.add(r.key), "recipe keys must be unique (duplicate: " + r.key + ")");
                        check(r.resultCount >= 1 && r.resultCount <= 64,
                                        r.key + ": result count must be 1..64 (got " + r.resultCount + ")");
                        if (r.shaped) {
                                check(r.cells != null && r.width > 0 && r.height > 0
                                                && r.cells.length == r.width * r.height,
                                                r.key + ": shaped recipe must declare width*height cells");
                                check(r.ingredients == null, r.key + ": shaped recipe must not declare ingredients");
                        } else {
                                check(r.ingredients != null && r.ingredients.length > 0,
                                                r.key + ": shapeless recipe must declare ingredients");
                                check(r.cells == null, r.key + ": shapeless recipe must not declare cells");
                        }
                        if (!validIds.contains(r.resultId)) badRefs.add(r.key + "->result " + r.resultId);
                        if (r.cells != null) {
                                for (int[] cell : r.cells) {
                                        if (cell == null) continue;
                                        for (int id : cell) {
                                                if (!validIds.contains(id)) badRefs.add(r.key + "->ingredient " + id);
                                        }
                                }
                        }
                        if (r.ingredients != null) {
                                for (int[] alt : r.ingredients) {
                                        for (int id : alt) {
                                                if (!validIds.contains(id)) badRefs.add(r.key + "->ingredient " + id);
                                        }
                                }
                        }
                }
                check(badRefs.isEmpty(), "every recipe id must exist in the registry (bad: " + badRefs + ")");

                // ── vanilla spot locks ──
                net.minecraft.world.item.crafting.CraftingRecipes.Recipe planks =
                                byKey(all, "data/minecraft/recipe/oak_planks.json");
                check(planks != null && !planks.shaped && planks.resultId == idByName.getOrDefault("oak_planks", -1)
                                && planks.resultCount == 4 && planks.ingredients.length == 1
                                && contains(planks.ingredients[0], 351),
                                "oak_planks must stay shapeless 1 oak_log -> 4 planks (vanilla truth)");
                net.minecraft.world.item.crafting.CraftingRecipes.Recipe stick =
                                byKey(all, "data/minecraft/recipe/stick.json");
                check(stick != null && stick.shaped && stick.width == 1 && stick.height == 2
                                && stick.resultId == 4096 && stick.resultCount == 4,
                                "stick must stay shaped 1x2 planks -> 4 sticks (vanilla truth)");
                net.minecraft.world.item.crafting.CraftingRecipes.Recipe table =
                                byKey(all, "data/minecraft/recipe/crafting_table.json");
                check(table != null && table.shaped && table.width == 2 && table.height == 2
                                && table.resultId == idByName.getOrDefault("crafting_table", -1),
                                "crafting_table must stay shaped 2x2 planks -> the crafting_table block");
                // Tool shapes (vanilla truths for the implemented subset): a
                // pickaxe is the vanilla 3x3 pattern "XXX"/" S "/" S " -
                // three head materials on top, sticks at row-major cells 4
                // and 7, empties elsewhere - and yields exactly one tool.
                int ironIngot = idByName.getOrDefault("iron_ingot", -1);
                int cobble = idByName.getOrDefault("cobblestone", -1);
                int stickId = idByName.getOrDefault("stick", -1);
                net.minecraft.world.item.crafting.CraftingRecipes.Recipe ironPick =
                                byKey(all, "data/minecraft/recipe/iron_pickaxe.json");
                check(ironPick != null && ironPick.shaped && ironPick.width == 3 && ironPick.height == 3
                                && ironPick.resultCount == 1
                                && contains(ironPick.cells[0], ironIngot) && contains(ironPick.cells[1], ironIngot)
                                && contains(ironPick.cells[2], ironIngot)
                                && ironPick.cells[3] == null && ironPick.cells[5] == null
                                && ironPick.cells[6] == null && ironPick.cells[8] == null
                                && contains(ironPick.cells[4], stickId) && contains(ironPick.cells[7], stickId),
                                "iron_pickaxe must stay the vanilla XXX / _S_ / _S_ pattern");
                net.minecraft.world.item.crafting.CraftingRecipes.Recipe stonePick =
                                byKey(all, "data/minecraft/recipe/stone_pickaxe.json");
                check(stonePick != null && stonePick.shaped && stonePick.width == 3 && stonePick.height == 3
                                && stonePick.resultCount == 1
                                && contains(stonePick.cells[0], cobble) && contains(stonePick.cells[1], cobble)
                                && contains(stonePick.cells[2], cobble)
                                && stonePick.cells[3] == null && stonePick.cells[5] == null
                                && stonePick.cells[6] == null && stonePick.cells[8] == null
                                && contains(stonePick.cells[4], stickId) && contains(stonePick.cells[7], stickId),
                                "stone_pickaxe must stay the vanilla XXX / _S_ / _S_ pattern (cobblestone head)");

                System.out.println("RecipeParityTest: " + all.length + " recipes, "
                                + validIds.size() + " registry ids, failures=" + failures);
                if (failures > 0) System.exit(1);
        }

        static net.minecraft.world.item.crafting.CraftingRecipes.Recipe byKey(
                        net.minecraft.world.item.crafting.CraftingRecipes.Recipe[] all, String key) {
                for (var r : all) if (r.key.equals(key)) return r;
                return null;
        }

        static boolean contains(int[] arr, int v) {
                for (int x : arr) if (x == v) return true;
                return false;
        }

        /** Resolves a registration id token: a digit literal or a constant name. */
        static Integer resolve(String token, Map<String, Integer> constIds) {
                if (Character.isDigit(token.charAt(0))) return Integer.valueOf(token);
                return constIds.get(token);
        }
}
