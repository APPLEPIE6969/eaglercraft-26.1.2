import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Random;

import net.lax1dude.eaglercraft.v2_6.placeholder_server.mc.McVarInt;

/**
 * JVM fuzz + differential test for the wire codecs (the gateway protocol's
 * foundation), at JVM speed instead of browser speed.
 *
 *  1. ROUND-TRIP: 8192 seeded random ints and longs (plus every boundary
 *     value) survive write->read through McVarInt byte-for-byte.
 *  2. DIFFERENTIAL: the port's VarInt encoder must byte-match an
 *     independently written reference encoder over the same corpus, so a
 *     subtle encoder change cannot hide behind its own decoder.
 *  3. MALFORMED INPUT: truncated streams, 6-byte overlong VarInts,
 *     12-byte overlong VarLongs and oversized string lengths must throw
 *     IOException - never hang, never allocate by a bogus length, never
 *     "succeed" with garbage.
 *  4. LENGTH DISCIPLINE: varIntSize() must equal the encoded length for
 *     the whole corpus.
 *
 * Exit 0 = the wire layer holds; 1 = violation.
 */
public class ProtocolFuzzTest {

        static int failures = 0;

        static void check(boolean ok, String what) {
                if (!ok) {
                        System.out.println("FAIL: " + what);
                        failures++;
                }
        }

        /** Independent reference VarInt encoder (loop-unrolled by byte count). */
        static byte[] referenceVarInt(int value) {
                int size = 1;
                int v = value;
                while ((v & ~0x7F) != 0) { v >>>= 7; size++; }
                byte[] out = new byte[size];
                v = value;
                for (int i = 0; i < size; i++) {
                        out[i] = (byte) ((v & 0x7F) | (i == size - 1 ? 0x00 : 0x80));
                        v >>>= 7;
                }
                return out;
        }

        static byte[] encodePort(int value) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                McVarInt.writeVarInt(out, value);
                return out.toByteArray();
        }

        static int decodePort(byte[] bytes) throws IOException {
                return McVarInt.readVarInt(new DataInputStream(new ByteArrayInputStream(bytes)));
        }

        public static void main(String[] args) throws Exception {
                Random rng = new Random(0x5EEDC0DE);

                int[] boundaries = { 0, 1, 127, 128, 129, 16383, 16384, 2097151, 2097152,
                                268435455, 268435456, Integer.MAX_VALUE, -1,
                                Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -128, -16384 };
                int[] corpus = new int[boundaries.length + 8192];
                System.arraycopy(boundaries, 0, corpus, 0, boundaries.length);
                for (int i = boundaries.length; i < corpus.length; i++) corpus[i] = rng.nextInt();

                // ── 1. round-trip + 2. differential + 4. size discipline ──
                for (int value : corpus) {
                        byte[] port = encodePort(value);
                        byte[] ref = referenceVarInt(value);
                        check(java.util.Arrays.equals(port, ref),
                                        "encoder differential mismatch for " + value
                                        + " (port=" + java.util.Arrays.toString(port)
                                        + " ref=" + java.util.Arrays.toString(ref) + ")");
                        check(decodePort(port) == value, "round-trip mismatch for " + value);
                        check(McVarInt.varIntSize(value) == port.length,
                                        "varIntSize(" + value + ")=" + McVarInt.varIntSize(value)
                                        + " but encoded " + port.length + " bytes");
                }

                // VarLong round-trip (seeded + boundaries)
                long[] longBoundaries = { 0L, 1L, 127L, 128L, Long.MAX_VALUE, Long.MIN_VALUE, -1L,
                                1L << 40, -(1L << 40) };
                long[] longCorpus = new long[longBoundaries.length + 4096];
                System.arraycopy(longBoundaries, 0, longCorpus, 0, longBoundaries.length);
                for (int i = longBoundaries.length; i < longCorpus.length; i++) longCorpus[i] = rng.nextLong();
                for (long value : longCorpus) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        McVarInt.writeVarLong(out, value);
                        long back = McVarInt.readVarLong(new DataInputStream(new ByteArrayInputStream(out.toByteArray())));
                        check(back == value, "VarLong round-trip mismatch for " + value);
                }

                // ── 3. malformed input ──
                // 6-byte overlong VarInt (every byte has the continuation bit)
                byte[] overlong = new byte[6];
                for (int i = 0; i < 5; i++) overlong[i] = (byte) 0x80;
                overlong[5] = 0x7F;
                try {
                        decodePort(overlong);
                        check(false, "a 6-byte overlong VarInt must be rejected");
                } catch (IOException expected) { /* good */ }

                // 12-byte overlong VarLong
                byte[] overlongLong = new byte[12];
                for (int i = 0; i < 11; i++) overlongLong[i] = (byte) 0x80;
                overlongLong[11] = 0x7F;
                try {
                        McVarInt.readVarLong(new DataInputStream(new ByteArrayInputStream(overlongLong)));
                        check(false, "a 12-byte overlong VarLong must be rejected");
                } catch (IOException expected) { /* good */ }

                // Truncated VarInt: EOF must surface as IOException, not a hang
                byte[] truncated = new byte[2];
                truncated[0] = (byte) 0x80; truncated[1] = (byte) 0x80; // promises 3+ bytes
                try {
                        decodePort(truncated);
                        check(false, "a truncated VarInt must be rejected");
                } catch (IOException expected) { /* good */ }

                // Empty stream
                try {
                        decodePort(new byte[0]);
                        check(false, "an empty stream must be rejected");
                } catch (IOException expected) { /* good */ }

                // readString: declared length far above the cap must throw
                // BEFORE any allocation, so the length is never trusted blindly
                ByteArrayOutputStream hostile = new ByteArrayOutputStream();
                McVarInt.writeVarInt(hostile, Integer.MAX_VALUE);
                try {
                        McVarInt.readString(new DataInputStream(new ByteArrayInputStream(hostile.toByteArray())), 16);
                        check(false, "an oversized string length must be rejected");
                } catch (IOException expected) { /* good */ }

                // readString: legal length but short stream -> EOF as IOException
                ByteArrayOutputStream shortStream = new ByteArrayOutputStream();
                McVarInt.writeVarInt(shortStream, 100);
                shortStream.write("only five".getBytes("UTF-8"), 0, 9);
                try {
                        McVarInt.readString(new DataInputStream(new ByteArrayInputStream(shortStream.toByteArray())), 256);
                        check(false, "a short string body must be rejected");
                } catch (IOException expected) { /* good */ }

                // writeString over the cap -> IllegalArgumentException
                try {
                        McVarInt.writeString(new ByteArrayOutputStream(), "0123456789abcdef", 8);
                        check(false, "writeString must enforce the cap");
                } catch (IllegalArgumentException expected) { /* good */ }

                // ── string round-trip with multibyte UTF-8 ──
                String[] strings = { "", "hello", "diddy62626", "\u00e9\u00fc\u00f1", "\u4f60\u597d\u4e16\u754c",
                                "mixed \u00e9\u4f60 ASCII" };
                for (String s : strings) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        McVarInt.writeString(out, s, 256);
                        String back = McVarInt.readString(
                                        new DataInputStream(new ByteArrayInputStream(out.toByteArray())), 256);
                        check(s.equals(back), "string round-trip mismatch for len=" + s.length());
                }

                System.out.println("ProtocolFuzzTest: " + corpus.length + " int + " + longCorpus.length
                                + " long round-trips, 8 malformed cases, failures=" + failures);
                if (failures > 0) System.exit(1);
        }
}
