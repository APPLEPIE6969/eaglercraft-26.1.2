package net.lax1dude.eaglercraft.v2_6.placeholder_server.mc;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * EaglerCraft 26.1.2 - VarInt / VarLong / string codecs for the minimal
 * Minecraft protocol subset spoken by the gateway bridge.
 *
 * <p>All helpers are static and allocation-light; the read side works on
 * {@link DataInputStream} (blocking), the write side on plain
 * {@link ByteArrayOutputStream}.</p>
 */
public final class McVarInt {

    private McVarInt() {
    }

    // ==================== reading ====================

    /** Reads an unsigned VarInt. */
    public static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = in.readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarInt too large");
    }

    /** Reads a signed VarLong. */
    public static long readVarLong(DataInputStream in) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            byte b = in.readByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarLong too large");
    }

    /** Reads a Minecraft protocol string (VarInt length + UTF-8). */
    public static String readString(DataInputStream in, int maxChars) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > maxChars * 4 + 16) {
            throw new IOException("string length out of range: " + len);
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    // ==================== writing ====================

    public static void writeVarInt(ByteArrayOutputStream out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    public static void writeVarLong(ByteArrayOutputStream out, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out.write((int) value);
                return;
            }
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    public static void writeString(ByteArrayOutputStream out, String s, int maxChars) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (s.length() > maxChars) {
            throw new IllegalArgumentException("string exceeds " + maxChars + " chars");
        }
        writeVarInt(out, b.length);
        out.write(b, 0, b.length);
    }

    /** Writes a root TAG_String in network NBT style (no root name). */
    public static void writeNbtString(ByteArrayOutputStream out, String s) {
        out.write(0x08); // TAG_String
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write((b.length >>> 8) & 0xFF);
        out.write(b.length & 0xFF);
        out.write(b, 0, b.length);
    }

    /** Number of bytes the VarInt encoding of {@code value} occupies. */
    public static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}
