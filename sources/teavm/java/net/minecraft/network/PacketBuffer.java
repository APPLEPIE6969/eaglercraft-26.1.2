package net.minecraft.network;

/**
 * EaglerCraft 26.1.2 - the wire codec for the gateway protocol
 * (docs/PROTOCOL.md, "Minecraft 26.1.2 Protocol 775").
 *
 * <p>A growable big-endian byte buffer with the vanilla packet
 * primitives: VarInt/VarLong (7-bit continuation groups, little-endian
 * groups of a big-endian stream exactly like vanilla), VarInt-length
 * UTF-8 strings (max 131072 chars, the vanilla cap), signed/unsigned
 * fixed-width integers, IEEE 754 floats/doubles, booleans and raw
 * byte arrays. Every packet on the WebSocket is one binary frame:
 * {@code [VarInt packetId][payload]}, and the payload is read and
 * written with this class.</p>
 *
 * <p>The layout of every pinned packet is documented in
 * docs/PROTOCOL.md - "Gateway Field Reference"; this class is the
 * single encoder/decoder both the client and the smoke tests share
 * semantics with (the Java gateway mirrors it byte for byte).</p>
 */
public class PacketBuffer {

        /** Vanilla's maximum UTF-8 string length (chars). */
        private static final int MAX_STRING_CHARS = 131072;

        private byte[] data;
        private int readPos;
        private int writePos;

        public PacketBuffer() {
                this(64);
        }

        public PacketBuffer(int initialCapacity) {
                this.data = new byte[Math.max(16, initialCapacity)];
        }

        /** Wraps an existing payload for reading. */
        public PacketBuffer(byte[] bytes) {
                this.data = bytes;
                this.writePos = bytes.length;
        }

        // ========== capacity ==========

        private void ensure(int extra) {
                if (this.writePos + extra <= this.data.length) {
                        return;
                }
                int grown = this.data.length;
                while (grown < this.writePos + extra) {
                        grown *= 2;
                }
                byte[] next = new byte[grown];
                System.arraycopy(this.data, 0, next, 0, this.writePos);
                this.data = next;
        }

        public int readableBytes() {
                return this.writePos - this.readPos;
        }

        public int writtenBytes() {
                return this.writePos;
        }

        /** The written payload as an exact-size array (for one WS frame). */
        public byte[] toArray() {
                byte[] out = new byte[this.writePos];
                System.arraycopy(this.data, 0, out, 0, this.writePos);
                return out;
        }

        // ========== VarInt / VarLong ==========

        public PacketBuffer writeVarInt(int value) {
                // Vanilla grouping: 7 data bits per byte, MSB = continuation,
                // least-significant group first.
                while ((value & ~0x7F) != 0) {
                        this.ensure(1);
                        this.data[this.writePos++] =
                                        (byte) ((value & 0x7F) | 0x80);
                        value >>>= 7;
                }
                this.ensure(1);
                this.data[this.writePos++] = (byte) value;
                return this;
        }

        public int readVarInt() {
                int value = 0;
                int shift = 0;
                while (true) {
                        if (this.readPos >= this.writePos) {
                                throw new PacketDecoderException(
                                                "VarInt truncated");
                        }
                        byte b = this.data[this.readPos++];
                        value |= (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) {
                                return value;
                        }
                        shift += 7;
                        if (shift >= 32) {
                                throw new PacketDecoderException(
                                                "VarInt too wide (>5 bytes)");
                        }
                }
        }

        public PacketBuffer writeVarLong(long value) {
                while ((value & ~0x7FL) != 0) {
                        this.ensure(1);
                        this.data[this.writePos++] =
                                        (byte) ((value & 0x7F) | 0x80);
                        value >>>= 7;
                }
                this.ensure(1);
                this.data[this.writePos++] = (byte) value;
                return this;
        }

        public long readVarLong() {
                long value = 0;
                int shift = 0;
                while (true) {
                        if (this.readPos >= this.writePos) {
                                throw new PacketDecoderException(
                                                "VarLong truncated");
                        }
                        byte b = this.data[this.readPos++];
                        value |= (long) (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) {
                                return value;
                        }
                        shift += 7;
                        if (shift >= 64) {
                                throw new PacketDecoderException(
                                                "VarLong too wide (>10 bytes)");
                        }
                }
        }

        // ========== fixed-width integers (big-endian) ==========

        public PacketBuffer writeBoolean(boolean b) {
                this.ensure(1);
                this.data[this.writePos++] = (byte) (b ? 1 : 0);
                return this;
        }

        public boolean readBoolean() {
                this.need(1);
                return this.data[this.readPos++] != 0;
        }

        public PacketBuffer writeByte(int b) {
                this.ensure(1);
                this.data[this.writePos++] = (byte) b;
                return this;
        }

        public int readByte() {
                this.need(1);
                return this.data[this.readPos++];
        }

        public int readUByte() {
                this.need(1);
                return this.data[this.readPos++] & 0xFF;
        }

        public PacketBuffer writeShort(int s) {
                this.ensure(2);
                this.data[this.writePos++] = (byte) (s >> 8);
                this.data[this.writePos++] = (byte) s;
                return this;
        }

        public int readShort() {
                this.need(2);
                int v = ((this.data[this.readPos] & 0xFF) << 8)
                                | (this.data[this.readPos + 1] & 0xFF);
                this.readPos += 2;
                return (short) v;
        }

        public int readUShort() {
                this.need(2);
                int v = ((this.data[this.readPos] & 0xFF) << 8)
                                | (this.data[this.readPos + 1] & 0xFF);
                this.readPos += 2;
                return v;
        }

        public PacketBuffer writeInt(int v) {
                this.ensure(4);
                this.data[this.writePos++] = (byte) (v >> 24);
                this.data[this.writePos++] = (byte) (v >> 16);
                this.data[this.writePos++] = (byte) (v >> 8);
                this.data[this.writePos++] = (byte) v;
                return this;
        }

        public int readInt() {
                this.need(4);
                int v = ((this.data[this.readPos] & 0xFF) << 24)
                                | ((this.data[this.readPos + 1] & 0xFF) << 16)
                                | ((this.data[this.readPos + 2] & 0xFF) << 8)
                                | (this.data[this.readPos + 3] & 0xFF);
                this.readPos += 4;
                return v;
        }

        public PacketBuffer writeLong(long v) {
                this.ensure(8);
                for (int i = 7; i >= 0; i--) {
                        this.data[this.writePos++] = (byte) (v >> (8 * i));
                }
                return this;
        }

        public long readLong() {
                this.need(8);
                long v = 0L;
                for (int i = 0; i < 8; i++) {
                        v = (v << 8) | (this.data[this.readPos++] & 0xFFL);
                }
                return v;
        }

        public PacketBuffer writeFloat(float f) {
                return this.writeInt(Float.floatToIntBits(f));
        }

        public float readFloat() {
                return Float.intBitsToFloat(this.readInt());
        }

        public PacketBuffer writeDouble(double d) {
                return this.writeLong(Double.doubleToLongBits(d));
        }

        public double readDouble() {
                return Double.longBitsToDouble(this.readLong());
        }

        // ========== strings and arrays ==========

        /**
         * VarInt-length UTF-8 string. The length prefix counts UTF-8
         * <em>bytes</em> (vanilla writes the byte length of the encoded
         * form); the 131072-char cap applies to the decoded string.
         */
        public PacketBuffer writeUtf(String s) {
                byte[] bytes = encodeUtf8(s != null ? s : "");
                if (s != null && s.length() > MAX_STRING_CHARS) {
                        throw new IllegalArgumentException(
                                        "String too long: " + s.length());
                }
                this.writeVarInt(bytes.length);
                this.writeBytes(bytes);
                return this;
        }

        public String readUtf() {
                int len = this.readVarInt();
                if (len < 0 || len > this.readableBytes()) {
                        throw new PacketDecoderException(
                                        "String length out of bounds: " + len);
                }
                String s = decodeUtf8(this.data, this.readPos, len);
                this.readPos += len;
                return s;
        }

        public PacketBuffer writeBytes(byte[] bytes) {
                this.ensure(bytes.length);
                System.arraycopy(bytes, 0, this.data, this.writePos,
                                bytes.length);
                this.writePos += bytes.length;
                return this;
        }

        public byte[] readBytes(int len) {
                if (len < 0 || len > this.readableBytes()) {
                        throw new PacketDecoderException(
                                        "Array length out of bounds: " + len);
                }
                byte[] out = new byte[len];
                System.arraycopy(this.data, this.readPos, out, 0, len);
                this.readPos += len;
                return out;
        }

        /** All remaining bytes (plugin-message payloads). */
        public byte[] readRemaining() {
                return this.readBytes(this.readableBytes());
        }

        // ========== UTF-8 (teavm-safe manual codec) ==========

        private static byte[] encodeUtf8(String s) {
                int n = s.length();
                // Size pass
                int size = 0;
                for (int i = 0; i < n; i++) {
                        char c = s.charAt(i);
                        if (c < 0x80) {
                                size += 1;
                        } else if (c < 0x800) {
                                size += 2;
                        } else {
                                size += 3;
                        }
                }
                byte[] out = new byte[size];
                int w = 0;
                for (int i = 0; i < n; i++) {
                        char c = s.charAt(i);
                        if (c < 0x80) {
                                out[w++] = (byte) c;
                        } else if (c < 0x800) {
                                out[w++] = (byte) (0xC0 | (c >> 6));
                                out[w++] = (byte) (0x80 | (c & 0x3F));
                        } else {
                                out[w++] = (byte) (0xE0 | (c >> 12));
                                out[w++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                                out[w++] = (byte) (0x80 | (c & 0x3F));
                        }
                }
                return out;
        }

        private static String decodeUtf8(byte[] src, int off, int len) {
                StringBuilder sb = new StringBuilder(len);
                int end = off + len;
                int i = off;
                while (i < end) {
                        int b = src[i++] & 0xFF;
                        if (b < 0x80) {
                                sb.append((char) b);
                        } else if ((b & 0xE0) == 0xC0 && i < end) {
                                sb.append((char) (((b & 0x1F) << 6)
                                                | (src[i++] & 0x3F)));
                        } else if ((b & 0xF0) == 0xE0 && i + 1 < end) {
                                sb.append((char) (((b & 0x0F) << 12)
                                                | ((src[i] & 0x3F) << 6)
                                                | (src[i + 1] & 0x3F)));
                                i += 2;
                        } else {
                                // Invalid byte: the vanilla decoder would
                                // throw; the gateway never sends one, so
                                // substitute U+FFFD and keep going.
                                sb.append('\uFFFD');
                        }
                }
                return sb.toString();
        }

        private void need(int n) {
                if (this.readPos + n > this.writePos) {
                        throw new PacketDecoderException(
                                        "Buffer underflow: need " + n
                                                        + ", have "
                                                        + this.readableBytes());
                }
        }

        /** Thrown when a payload doesn't match the pinned layout. */
        public static class PacketDecoderException extends RuntimeException {
                public PacketDecoderException(String message) {
                        super(message);
                }
        }
}
