import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * JVM parity test: the movement/physics constants the port bakes in must
 * still be the vanilla 26.1.2 constants, proven against the committed
 * decompile zip - the "canary" pattern.
 *
 * Each constant is checked twice:
 *  - PORT: a regex anchored on the constant's declaration must match the
 *    named port source, so a hand-edit that silently changes vanilla
 *    physics goes red.
 *  - VANILLA: the same-value regex must match the named decompiled file.
 *    The vanilla anchors were located in the real 26.1.2 decompile
 *    (LivingEntity's INPUT_FRICTION / DEFAULT_BASE_GRAVITY /
 *    BASE_JUMP_POWER, Abilities.flyingSpeed, Player's MOVEMENT_SPEED
 *    attribute add, Attributes' gravity attribute) - if Mojang moves or
 *    changes one, THIS side goes red and forces a human re-audit instead
 *    of letting both sides drift quietly.
 *
 * Exit 0 = parity holds; 1 = a canary tripped.
 */
public class PhysicsParityTest {

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

        /** Reads one decompiled vanilla source from the zip by path suffix. */
        static String zipText(ZipFile zip, String suffix) throws Exception {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        if (e.getName().endsWith(suffix)) return readUtf(zip.getInputStream(e));
                }
                return null;
        }

        static String repoText(String path) throws Exception {
                java.io.File f = new java.io.File(path);
                return f.exists() ? readUtf(new java.io.FileInputStream(f)) : null;
        }

        /**
         * One canary: a regex for the PORT declaration and a regex for the
         * VANILLA decompile declaration (they may anchor on different
         * constant names - the value must agree). vanillaSuffix "null" =
         * port-only invariant.
         */
        record Canary(String what, String portRegex, String vanillaRegex,
                        String portPath, String vanillaSuffix) {}

        public static void main(String[] args) throws Exception {
                String LP = "sources/teavm/java/net/minecraft/client/player/LocalPlayer.java";
                List<Canary> canaries = new ArrayList<>();
                // Gravity 0.08: vanilla names it DEFAULT_BASE_GRAVITY in
                // LivingEntity (the gravity attribute base is 0.08 too); the
                // port bakes it as GRAVITY in LocalPlayer.
                canaries.add(new Canary("entity gravity 0.08",
                                "GRAVITY = 0\\.08D", "DEFAULT_BASE_GRAVITY = 0\\.08",
                                LP, "world/entity/LivingEntity.java"));
                // Jump impulse 0.42 - the constant even keeps its vanilla NAME
                // (BASE_JUMP_POWER) on both sides.
                canaries.add(new Canary("jump impulse 0.42",
                                "BASE_JUMP_POWER = 0\\.42F", "BASE_JUMP_POWER = 0\\.42F",
                                LP, "world/entity/LivingEntity.java"));
                // Input friction 0.98: vanilla INPUT_FRICTION in LivingEntity.
                canaries.add(new Canary("input friction 0.98",
                                "0\\.98F", "INPUT_FRICTION = 0\\.98F",
                                LP, "world/entity/LivingEntity.java"));
                // Ground acceleration 0.21600002 (the constant behind the
                // 0.216 blocks/tick walk invariant).
                canaries.add(new Canary("ground acceleration 0.216",
                                "0\\.21600002", "0\\.216",
                                LP, "world/entity/LivingEntity.java"));
                // Creative fly speed 0.05: vanilla Abilities.flyingSpeed.
                canaries.add(new Canary("flying speed 0.05",
                                "FLYING_SPEED = 0\\.05F", "flyingSpeed = 0\\.05F",
                                LP, "world/entity/player/Abilities.java"));
                // Walking speed 0.1: vanilla Player adds MOVEMENT_SPEED 0.1F.
                canaries.add(new Canary("walk speed 0.1",
                                "WALKING_SPEED = 0\\.1F", "MOVEMENT_SPEED, 0\\.1F",
                                LP, "world/entity/player/Player.java"));
                // Fixed 20 TPS clock (port-side invariant; 50ms per tick).
                canaries.add(new Canary("target 20 TPS",
                                "TARGET_TPS\\s*=\\s*20", "null",
                                "sources/teavm/java/net/lax1dude/eaglercraft/v2_6/EaglerCraftConfig.java", "null"));

                try (ZipFile zip = new ZipFile("docs/decompiled-26.1.2-full-src.zip")) {
                        for (Canary c : canaries) {
                                String port = repoText(c.portPath());
                                check(port != null, "port source missing: " + c.portPath());
                                if (port != null) {
                                        check(Pattern.compile(c.portRegex()).matcher(port).find(),
                                                        "PORT canary missing: " + c.what() + " (" + c.portRegex()
                                                        + ") in " + c.portPath());
                                }
                                if ("null".equals(c.vanillaSuffix())) continue; // port-only invariant
                                String vanilla = zipText(zip, c.vanillaSuffix());
                                check(vanilla != null, "vanilla source not in decompile zip: " + c.vanillaSuffix());
                                if (vanilla == null) continue;
                                check(Pattern.compile(c.vanillaRegex()).matcher(vanilla).find(),
                                                "VANILLA canary missing: " + c.what() + " (" + c.vanillaRegex()
                                                + ") in " + c.vanillaSuffix()
                                                + " - vanilla changed or the constant moved; re-audit before editing the port value");
                        }
                }
                System.out.println("PhysicsParityTest: " + canaries.size() + " canaries, failures=" + failures);
                if (failures > 0) System.exit(1);
        }
}
