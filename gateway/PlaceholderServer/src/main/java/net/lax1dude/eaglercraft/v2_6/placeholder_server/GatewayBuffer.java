package net.lax1dude.eaglercraft.v2_6.placeholder_server;

import java.nio.charset.StandardCharsets;

/**
 * EaglerCraft 26.1.2 gateway - the wire codec, mirroring the client's
 * {@code net.minecraft.network.PacketBuffer} byte for byte
 * (docs/PROTOCOL.md): big-endian, VarInt/VarLong with 7-bit
 * continuation groups, VarInt-length UTF-8 strings. The backing
 * storage grows on demand, so write sites never hand-size buffers.
 */
public final class GatewayBuffer {

        private byte[] data;
        private int readPos;
        private int writePos;

        private GatewayBuffer(byte[] data) {
                this.data = data;
                this.writePos = data.length;
        }

        // ==== construction ====

        public static GatewayBuffer wrap(byte[] bytes) {
                return new GatewayBuffer(bytes);
        }

        /** An empty buffer for writing. */
        public static GatewayBuffer create() {
                return new GatewayBuffer(new byte[0]);
        }

        private void ensure(int extra) {
                if (this.writePos + extra <= this.data.length) {
                        return;
                }
                int grown = Math.max(16, this.data.length * 2);
                while (grown < this.writePos + extra) {
                        grown *= 2;
                }
                byte[] next = new byte[grown];
                System.arraycopy(this.data, 0, next, 0, this.writePos);
                this.data = next;
        }

        private void need(int n) {
                if (this.readPos + n > this.writePos) {
                        throw new IllegalArgumentException(
                                        "buffer underflow: need " + n + ", have "
                                                        + (this.writePos - this.readPos));
                }
        }

        // ==== reading ====

        public int readVarInt() {
                int value = 0;
                int shift = 0;
                while (true) {
                        need(1);
                        byte b = data[readPos++];
                        value |= (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) {
                                return value;
                        }
                        shift += 7;
                        if (shift >= 32) {
                                throw new IllegalArgumentException("VarInt too wide");
                        }
                }
        }

        public long readVarLong() {
                long value = 0;
                int shift = 0;
                while (true) {
                        need(1);
                        byte b = data[readPos++];
                        value |= (long) (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) {
                                return value;
                        }
                        shift += 7;
                        if (shift >= 64) {
                                throw new IllegalArgumentException("VarLong too wide");
                        }
                }
        }

        public long readLong() {
                need(8);
                long v = 0L;
                for (int i = 0; i < 8; i++) {
                        v = (v << 8) | (data[readPos++] & 0xFFL);
                }
                return v;
        }

        public int readInt() {
                need(4);
                int v = ((data[readPos] & 0xFF) << 24)
                                | ((data[readPos + 1] & 0xFF) << 16)
                                | ((data[readPos + 2] & 0xFF) << 8)
                                | (data[readPos + 3] & 0xFF);
                readPos += 4;
                return v;
        }

        public int readShort() {
                need(2);
                int v = ((data[readPos] & 0xFF) << 8) | (data[readPos + 1] & 0xFF);
                readPos += 2;
                return (short) v;
        }

        public int readUShort() {
                need(2);
                int v = ((data[readPos] & 0xFF) << 8) | (data[readPos + 1] & 0xFF);
                readPos += 2;
                return v;
        }

        public int readByte() {
                need(1);
                return data[readPos++];
        }

        public int readUByte() {
                need(1);
                return data[readPos++] & 0xFF;
        }

        public boolean readBoolean() {
                return readByte() != 0;
        }

        public double readDouble() {
                return Double.longBitsToDouble(readLong());
        }

        public float readFloat() {
                return Float.intBitsToFloat(readInt());
        }

        public String readUtf() {
                int len = readVarInt();
                if (len < 0 || len > writePos - readPos) {
                        throw new IllegalArgumentException(
                                        "string length out of bounds: " + len);
                }
                String s = new String(data, readPos, len, StandardCharsets.UTF_8);
                readPos += len;
                return s;
        }

        public byte[] readBytes(int len) {
                if (len < 0 || len > writePos - readPos) {
                        throw new IllegalArgumentException(
                                        "array length out of bounds: " + len);
                }
                byte[] b = new byte[len];
                System.arraycopy(data, readPos, b, 0, len);
                readPos += len;
                return b;
        }

        public byte[] readRemaining() {
                return readBytes(writePos - readPos);
        }

        // ==== writing ====

        public GatewayBuffer writeVarInt(int value) {
                while ((value & ~0x7F) != 0) {
                        ensure(1);
                        data[writePos++] = (byte) ((value & 0x7F) | 0x80);
                        value >>>= 7;
                }
                ensure(1);
                data[writePos++] = (byte) value;
                return this;
        }

        public GatewayBuffer writeVarLong(long value) {
                while ((value & ~0x7FL) != 0) {
                        ensure(1);
                        data[writePos++] = (byte) ((value & 0x7F) | 0x80);
                        value >>>= 7;
                }
                ensure(1);
                data[writePos++] = (byte) value;
                return this;
        }

        public GatewayBuffer writeLong(long v) {
                ensure(8);
                for (int i = 7; i >= 0; i--) {
                        data[writePos++] = (byte) (v >> (8 * i));
                }
                return this;
        }

        public GatewayBuffer writeInt(int v) {
                ensure(4);
                data[writePos++] = (byte) (v >> 24);
                data[writePos++] = (byte) (v >> 16);
                data[writePos++] = (byte) (v >> 8);
                data[writePos++] = (byte) v;
                return this;
        }

        public GatewayBuffer writeShort(int v) {
                ensure(2);
                data[writePos++] = (byte) (v >> 8);
                data[writePos++] = (byte) v;
                return this;
        }

        public GatewayBuffer writeByte(int v) {
                ensure(1);
                data[writePos++] = (byte) v;
                return this;
        }

        public GatewayBuffer writeBoolean(boolean v) {
                return writeByte(v ? 1 : 0);
        }

        public GatewayBuffer writeDouble(double v) {
                return writeLong(Double.doubleToLongBits(v));
        }

        public GatewayBuffer writeFloat(float v) {
                return writeInt(Float.floatToIntBits(v));
        }

        public GatewayBuffer writeUtf(String s) {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                writeVarInt(b.length);
                writeBytes(b);
                return this;
        }

        public GatewayBuffer writeBytes(byte[] b) {
                ensure(b.length);
                System.arraycopy(b, 0, data, writePos, b.length);
                writePos += b.length;
                return this;
        }

        /** The written payload as an exact-size array (one WS frame). */
        public byte[] toArray() {
                byte[] out = new byte[writePos];
                System.arraycopy(data, 0, out, 0, writePos);
                return out;
        }

        // ==== position codec (vanilla long form: x26 z26 y12) ====

        public static long encodePos(int x, int y, int z) {
                return ((long) (x & 0x3FFFFFF) << 38)
                                | ((long) (z & 0x3FFFFFF) << 12)
                                | (long) (y & 0xFFF);
        }

        public static int decodePosX(long pos) {
                return (int) (pos >> 38);
        }

        public static int decodePosY(long pos) {
                return (int) (pos << 52 >> 52);
        }

        public static int decodePosZ(long pos) {
                return (int) (pos << 26 >> 38);
        }
}
