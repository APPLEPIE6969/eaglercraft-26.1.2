package net.lax1dude.eaglercraft.v2_6.placeholder_server.mc;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * EaglerCraft 26.1.2 - minimal network-NBT reader for chat components.
 *
 * <p>The bridge only needs one thing out of a serverbound chat component:
 * the display text. Modern servers (1.20.3+) encode chat as unnamed-root
 * NBT. Two shapes are handled properly:</p>
 * <ul>
 *   <li>root TAG_String — used verbatim;</li>
 *   <li>root TAG_Compound — the {@code text} string field is used, and a
 *       nested {@code extra} list contributes its text parts.</li>
 * </ul>
 * <p>Every other tag type is skipped structurally (compound/list descent,
 * primitive array lengths) so a payload can never desynchronize the
 * connection even if it contains tags this reader does not understand.</p>
 */
public final class McNbt {

    private static final int TAG_END = 0x00;
    private static final int TAG_BYTE = 0x01;
    private static final int TAG_SHORT = 0x02;
    private static final int TAG_INT = 0x03;
    private static final int TAG_LONG = 0x04;
    private static final int TAG_FLOAT = 0x05;
    private static final int TAG_DOUBLE = 0x06;
    private static final int TAG_BYTE_ARRAY = 0x07;
    private static final int TAG_STRING = 0x08;
    private static final int TAG_LIST = 0x09;
    private static final int TAG_COMPOUND = 0x0A;
    private static final int TAG_INT_ARRAY = 0x0B;
    private static final int TAG_LONG_ARRAY = 0x0C;

    private static final int MAX_DEPTH = 32;

    private McNbt() {
    }

    /**
     * Reads a root NBT tag (network style: no root name) and extracts the
     * chat text. Returns a placeholder when nothing text-like is found.
     */
    public static String readChatText(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        StringBuilder sb = new StringBuilder();
        readValue(in, type, sb, 0);
        String s = sb.toString().trim();
        return s.isEmpty() ? "(chat)" : s;
    }

    private static void readValue(DataInputStream in, int type, StringBuilder sb, int depth)
            throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting too deep");
        }
        switch (type) {
            case TAG_BYTE:
                in.skipBytes(1);
                break;
            case TAG_SHORT:
                in.skipBytes(2);
                break;
            case TAG_INT:
            case TAG_FLOAT:
                in.skipBytes(4);
                break;
            case TAG_LONG:
            case TAG_DOUBLE:
                in.skipBytes(8);
                break;
            case TAG_BYTE_ARRAY:
                int bl = in.readInt();
                if (bl < 0 || bl > 1 << 20) {
                    throw new IOException("bad byte array length " + bl);
                }
                in.skipBytes(bl);
                break;
            case TAG_STRING:
                sb.append(readStringTag(in));
                break;
            case TAG_LIST: {
                int elem = in.readUnsignedByte();
                int len = in.readInt();
                if (len < 0 || len > 1 << 16) {
                    throw new IOException("bad list length " + len);
                }
                // lists of TAG_END (empty) carry no payload
                for (int i = 0; i < len && elem != TAG_END; i++) {
                    readValue(in, elem, sb, depth + 1);
                }
                break;
            }
            case TAG_COMPOUND:
                readCompound(in, sb, depth + 1);
                break;
            case TAG_INT_ARRAY: {
                int il = in.readInt();
                if (il < 0 || il > 1 << 18) {
                    throw new IOException("bad int array length " + il);
                }
                in.skipBytes(il * 4);
                break;
            }
            case TAG_LONG_ARRAY: {
                int ll = in.readInt();
                if (ll < 0 || ll > 1 << 17) {
                    throw new IOException("bad long array length " + ll);
                }
                in.skipBytes(ll * 8);
                break;
            }
            default:
                throw new IOException("unknown NBT tag type " + type);
        }
    }

    private static void readCompound(DataInputStream in, StringBuilder sb, int depth)
            throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return;
            }
            String name = readStringTag(in);
            StringBuilder value = new StringBuilder();
            readValue(in, type, value, depth);
            // collect "text" fields; everything else is skipped but consumed
            if (name.equals("text") && value.length() > 0) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(value);
            }
        }
    }

    private static String readStringTag(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        if (len > 1 << 16) {
            throw new IOException("NBT string too long: " + len);
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
