package net.lax1dude.eaglercraft.v2_6.placeholder_server.mc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * EaglerCraft 26.1.2 - Minecraft protocol TCP bridge from a WebSocket Eagler
 * session into a backend server.
 *
 * <p>This is gateway milestone #2: the bridge is the MC-protocol half of
 * forwarding an Eagler player into the proxy's server registry. One bridge
 * instance owns one vanilla TCP connection to a registered backend
 * {@code ServerInfo} address and performs the login dance (handshake with
 * next-state 2, Login Start, Set Compression, Login Success) and then a
 * minimal play-state relay:</p>
 *
 * <ul>
 *   <li>server keepalive is answered immediately;</li>
 *   <li>system chat is decoded (NBT) and handed to the {@link Listener};</li>
 *   <li>unsigned client chat is forwarded to the backend;</li>
 *   <li>disconnect packets (login + play) end the bridge with a reason;</li>
 *   <li>login plugin requests are politely refused;</li>
 *   <li>encryption requests abort the bridge (offline-mode gateway);</li>
 *   <li>every other server packet is skipped by length framing.</li>
 * </ul>
 *
 * <p>The class is pure JDK (no netty, no slf4j) so both proxy plugins and
 * standalone harnesses can embed it. All public methods are safe to call
 * from any thread; I/O happens on dedicated daemon threads.</p>
 */
public final class McBridge {

    /** Bridge lifecycle states, reported through {@link Listener#onStateChange}. */
    public enum State {
        NEW, CONNECTING, LOGGING_IN, CONNECTED, CLOSED
    }

    /** Callbacks from the bridge; invoked on the bridge I/O threads. */
    public interface Listener {
        void onStateChange(McBridge bridge, State newState, String detail);

        /** A decoded system-chat text arrived from the backend. */
        void onChat(McBridge bridge, String text);

        /** The backend disconnected the player (login or play state). */
        void onKicked(McBridge bridge, String reason);

        default void onError(McBridge bridge, String message) {
        }
    }

    private final InetSocketAddress target;
    private final McPacketIds ids;
    private final String virtualHost;
    private final String username;
    private final UUID uuid;
    private final int connectTimeoutMs;
    private final Listener listener;

    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final Object writeLock = new Object();

    private volatile Socket socket;
    private volatile DataInputStream in;
    private volatile OutputStream out;
    private volatile int compressionThreshold = -1; // -1 = uncompressed

    public McBridge(InetSocketAddress target, McPacketIds ids, String virtualHost,
            String username, UUID uuid, int connectTimeoutMs, Listener listener) {
        this.target = target;
        this.ids = ids.copy();
        this.virtualHost = virtualHost;
        this.username = username;
        this.uuid = uuid;
        this.connectTimeoutMs = connectTimeoutMs;
        this.listener = listener;
    }

    // ==================== lifecycle ====================

    /** Starts the connection + login on a daemon thread. */
    public void start() {
        Thread t = new Thread(this::runLogin, "eagler-mcbridge-" + username);
        t.setDaemon(true);
        t.start();
    }

    public State getState() {
        return state.get();
    }

    public boolean isConnected() {
        return state.get() == State.CONNECTED;
    }

    public String getUsername() {
        return username;
    }

    public UUID getUuid() {
        return uuid;
    }

    public InetSocketAddress getTarget() {
        return target;
    }

    /** Closes the bridge; safe to call from any thread, idempotent. */
    public void close(String reason) {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        transition(State.CLOSED, reason);
    }

    // ==================== login ====================

    private void runLogin() {
        transition(State.CONNECTING, "connecting " + target);
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(target.getAddress(), target.getPort()),
                    Math.max(1, connectTimeoutMs));
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30_000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 15));
            out = new BufferedOutputStream(socket.getOutputStream(), 1 << 15);
        } catch (Exception e) {
            fail("connect failed: " + e.getMessage());
            return;
        }

        transition(State.LOGGING_IN, "login handshake");
        try {
            // handshake (state 0): protocol, addr, port, nextState=2
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            McVarInt.writeVarInt(hs, 0x00);
            McVarInt.writeVarInt(hs, ids.protocolVersion);
            McVarInt.writeString(hs, virtualHost, 255);
            hs.write((target.getPort() >>> 8) & 0xFF);
            hs.write(target.getPort() & 0xFF);
            McVarInt.writeVarInt(hs, 2);
            sendFrame(hs.toByteArray());

            // login start: name + optional profile uuid (1.20.5+ layout)
            ByteArrayOutputStream ls = new ByteArrayOutputStream();
            McVarInt.writeVarInt(ls, 0x00);
            McVarInt.writeString(ls, username, 16);
            ls.write(1); // uuid present
            writeUuid(ls, uuid);
            sendFrame(ls.toByteArray());
        } catch (Exception e) {
            fail("login write failed: " + e.getMessage());
            return;
        }

        try {
            while (state.get() == State.LOGGING_IN) {
                DataInputStream payload = readPacket();
                if (payload == null) {
                    return; // closed
                }
                int id = McVarInt.readVarInt(payload);
                if (id == ids.loginSetCompression) {
                    int threshold = McVarInt.readVarInt(payload);
                    compressionThreshold = threshold;
                } else if (id == ids.loginSuccess) {
                    byte[] rawUuid = new byte[16];
                    payload.readFully(rawUuid);
                    String name = McVarInt.readString(payload, 16);
                    int props = McVarInt.readVarInt(payload);
                    if (props != 0) {
                        fail("backend sent properties (online mode?) - refusing");
                        return;
                    }
                    transition(State.CONNECTED, "login success: " + name);
                    runPlay();
                    return;
                } else if (id == ids.loginDisconnect) {
                    kicked(McNbt.readChatText(payload));
                    return;
                } else if (id == ids.loginEncryptionRequest) {
                    kicked("backend requires online-mode encryption; the Eagler gateway is offline-mode");
                    return;
                } else if (id == ids.loginPluginRequest) {
                    int msgId = McVarInt.readVarInt(payload);
                    ByteArrayOutputStream resp = new ByteArrayOutputStream();
                    McVarInt.writeVarInt(resp, ids.loginPluginResponse);
                    McVarInt.writeVarInt(resp, msgId);
                    resp.write(0); // successful=false
                    sendFrame(resp.toByteArray());
                } else {
                    // skip: already fully read into payload
                }
            }
        } catch (IOException e) {
            fail("login read failed: " + e.getMessage());
        }
    }

    // ==================== play relay ====================

    private void runPlay() {
        try {
            while (state.get() == State.CONNECTED) {
                DataInputStream payload = readPacket();
                if (payload == null) {
                    return;
                }
                int id = McVarInt.readVarInt(payload);
                if (id == ids.playKeepAlive) {
                    long cookie = payload.readLong();
                    ByteArrayOutputStream ka = new ByteArrayOutputStream();
                    McVarInt.writeVarInt(ka, ids.playKeepAliveResponse);
                    ka.write((int) (cookie >>> 56));
                    ka.write((int) (cookie >>> 48));
                    ka.write((int) (cookie >>> 40));
                    ka.write((int) (cookie >>> 32));
                    ka.write((int) (cookie >>> 24));
                    ka.write((int) (cookie >>> 16));
                    ka.write((int) (cookie >>> 8));
                    ka.write((int) cookie);
                    sendFrame(ka.toByteArray());
                } else if (id == ids.playSystemChat) {
                    String text = McNbt.readChatText(payload);
                    payload.readUnsignedByte(); // overlay flag
                    try {
                        listener.onChat(this, text);
                    } catch (Exception e) {
                        listener.onError(this, "chat listener error: " + e);
                    }
                } else if (id == ids.playDisconnect) {
                    kicked(McNbt.readChatText(payload));
                    return;
                } else {
                    // unknown packet: payload already buffered, nothing to do
                }
            }
        } catch (IOException e) {
            fail("play read failed: " + e.getMessage());
        }
    }

    // ==================== outbound ====================

    /**
     * Sends an unsigned chat message to the backend. Returns false when the
     * bridge is not in the play state or the write failed.
     */
    public boolean sendChat(String message) {
        if (state.get() != State.CONNECTED) {
            return false;
        }
        if (message == null || message.isEmpty() || message.length() > 256) {
            return false;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        McVarInt.writeVarInt(buf, ids.playChat);
        McVarInt.writeString(buf, message, 256);
        // signature-less chat: timestamp, salt, no signature, empty acknowledgements
        writeLong(buf, System.currentTimeMillis());
        writeLong(buf, 0L);
        buf.write(0); // has signature = false
        McVarInt.writeVarInt(buf, 0); // acknowledgement list size
        try {
            sendFrame(buf.toByteArray());
            return true;
        } catch (IOException e) {
            listener.onError(this, "chat write failed: " + e.getMessage());
            return false;
        }
    }

    // ==================== framing ====================

    /**
     * Reads one packet payload with compression handling. Returns null when
     * the connection closed. The returned stream contains exactly one packet
     * body (packet id already included, NOT yet consumed).
     */
    private DataInputStream readPacket() throws IOException {
        DataInputStream i = in;
        int length = McVarInt.readVarInt(i);
        if (length <= 0 || length > (1 << 21)) {
            fail("backend frame length insane: " + length);
            return null;
        }
        if (compressionThreshold >= 0) {
            int dataLen = McVarInt.readVarInt(i);
            int payloadLen = length - McVarInt.varIntSize(dataLen);
            if (payloadLen < 0) {
                fail("compressed frame framing broken");
                return null;
            }
            byte[] compressed = new byte[payloadLen];
            i.readFully(compressed);
            if (dataLen == 0) {
                return new DataInputStream(new ByteArrayInputStream(compressed));
            }
            if (dataLen > (1 << 21)) {
                fail("uncompressed size insane: " + dataLen);
                return null;
            }
            Inflater inf = new Inflater();
            inf.setInput(compressed);
            byte[] raw = new byte[dataLen];
            try {
                int produced = 0;
                while (!inf.finished() && produced < dataLen) {
                    produced += inf.inflate(raw, produced, dataLen - produced);
                    if (produced == 0 && inf.needsInput()) {
                        fail("zlib stream truncated");
                        return null;
                    }
                }
                if (produced != dataLen) {
                    fail("zlib size mismatch: " + produced + " != " + dataLen);
                    return null;
                }
            } catch (DataFormatException e) {
                fail("zlib corrupt: " + e.getMessage());
                return null;
            } finally {
                inf.end();
            }
            return new DataInputStream(new ByteArrayInputStream(raw));
        }
        byte[] raw = new byte[length];
        i.readFully(raw);
        return new DataInputStream(new ByteArrayInputStream(raw));
    }

    /** Frames and writes one packet (compression applied when active). */
    private void sendFrame(byte[] packet) throws IOException {
        synchronized (writeLock) {
            OutputStream o = out;
            if (o == null || state.get() == State.CLOSED) {
                throw new IOException("bridge closed");
            }
            ByteArrayOutputStream frame = new ByteArrayOutputStream(packet.length + 16);
            if (compressionThreshold >= 0) {
                if (packet.length >= compressionThreshold) {
                    Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
                    def.setInput(packet);
                    def.finish();
                    ByteArrayOutputStream zipped = new ByteArrayOutputStream(packet.length / 2 + 32);
                    byte[] tmp = new byte[8192];
                    while (!def.finished()) {
                        int n = def.deflate(tmp);
                        zipped.write(tmp, 0, n);
                    }
                    def.end();
                    byte[] z = zipped.toByteArray();
                    McVarInt.writeVarInt(frame, McVarInt.varIntSize(packet.length) + z.length);
                    McVarInt.writeVarInt(frame, packet.length);
                    frame.write(z, 0, z.length);
                } else {
                    McVarInt.writeVarInt(frame, McVarInt.varIntSize(0) + packet.length);
                    McVarInt.writeVarInt(frame, 0);
                    frame.write(packet, 0, packet.length);
                }
            } else {
                McVarInt.writeVarInt(frame, packet.length);
                frame.write(packet, 0, packet.length);
            }
            o.write(frame.toByteArray());
            o.flush();
        }
    }

    // ==================== helpers ====================

    private void transition(State newState, String detail) {
        State old = state.getAndSet(newState);
        if (old == newState) {
            return; // never notify the same transition twice
        }
        try {
            listener.onStateChange(this, newState, detail);
        } catch (Exception ignored) {
        }
    }

    private void fail(String detail) {
        transition(State.CLOSED, detail);
        try {
            listener.onError(this, detail);
        } catch (Exception ignored) {
        }
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void kicked(String reason) {
        try {
            listener.onKicked(this, reason);
        } catch (Exception ignored) {
        }
        close("kicked: " + reason);
    }

    private static void writeUuid(ByteArrayOutputStream out, UUID u) {
        writeLong(out, u.getMostSignificantBits());
        writeLong(out, u.getLeastSignificantBits());
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        out.write((int) (v >>> 56));
        out.write((int) (v >>> 48));
        out.write((int) (v >>> 40));
        out.write((int) (v >>> 32));
        out.write((int) (v >>> 24));
        out.write((int) (v >>> 16));
        out.write((int) (v >>> 8));
        out.write((int) v);
    }
}
