#!/usr/bin/env node
/**
 * Real gateway protocol smoke test (docs/PROTOCOL.md, protocol 775).
 * Walks the full state machine against a running PlaceholderServer:
 *   status ping/MOTD -> login -> configuration -> play -> chunks ->
 *   dig a tree log -> block update + inventory slot.
 *
 * Usage: node scripts/gw_real_smoke.mjs [port]
 */
import net from 'node:net';

const PORT = Number(process.argv[2] || 8081);
let pass = 0;
let fail = 0;
function check(name, cond, extra = '') {
  if (cond) { pass++; console.log(`  ok  ${name}`); }
  else { fail++; console.log(`FAIL  ${name} ${extra}`); }
}

// ---- codec (mirror of the pinned PROTOCOL.md layouts) ----
class Buf {
  constructor(bytes) { this.b = bytes || []; this.r = 0; }
  varint(v) {
    while ((v & ~0x7f) !== 0) { this.b.push((v & 0x7f) | 0x80); v >>>= 7; }
    this.b.push(v);
    return this;
  }
  utf(s) {
    const e = new TextEncoder().encode(s);
    this.varint(e.length);
    for (const x of e) this.b.push(x);
    return this;
  }
  int(v) { this.b.push((v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255); return this; }
  short(v) { this.b.push((v >> 8) & 255, v & 255); return this; }
  ubyte(v) { this.b.push(v & 255); return this; }
  byte(v) { this.b.push(v & 255); return this; }
  boolean(v) { this.b.push(v ? 1 : 0); return this; }
  long(v) { // number-safe Long (BigInt)
    let bi = BigInt(v);
    for (let i = 7; i >= 0; i--) this.b.push(Number((bi >> BigInt(8 * i)) & 255n));
    return this;
  }
  double(v) {
    const d = new DataView(new ArrayBuffer(8));
    d.setFloat64(0, v);
    for (let i = 0; i < 8; i++) this.b.push(d.getUint8(i));
    return this;
  }
  float(v) {
    const d = new DataView(new ArrayBuffer(4));
    d.setFloat32(0, v);
    for (let i = 0; i < 4; i++) this.b.push(d.getUint8(i));
    return this;
  }
  pos(x, y, z) {
    const p = (BigInt(x & 0x3ffffff) << 38n) | (BigInt(z & 0x3ffffff) << 12n) | BigInt(y & 0xfff);
    return this.long(p);
  }
  toBytes() { return Uint8Array.from(this.b); }
  // readers
  readVarInt() {
    let v = 0, shift = 0;
    while (true) {
      const b = this.b[this.r++];
      v |= (b & 0x7f) << shift;
      if (!(b & 0x80)) return v;
      shift += 7;
      if (shift >= 32) throw new Error('varint too wide');
    }
  }
  readUtf() {
    const len = this.readVarInt();
    const s = new TextDecoder().decode(Uint8Array.from(this.b.slice(this.r, this.r + len)));
    this.r += len;
    return s;
  }
  readLong() {
    let v = 0n;
    for (let i = 0; i < 8; i++) v = (v << 8n) | BigInt(this.b[this.r++]);
    return Number(v);
  }
  readInt() {
    const v = (this.b[this.r] << 24) | (this.b[this.r + 1] << 16) | (this.b[this.r + 2] << 8) | this.b[this.r + 3];
    this.r += 4;
    return v;
  }
  readShort() {
    const v = (this.b[this.r] << 8) | this.b[this.r + 1];
    this.r += 2;
    return v;
  }
  readByte() { return this.b[this.r++]; }
  readUByte() { return this.b[this.r++] & 255; }
  readBoolean() { return this.b[this.r++] !== 0; }
  readDouble() {
    const d = new DataView(Uint8Array.from(this.b.slice(this.r, this.r + 8)).buffer);
    this.r += 8;
    return d.getFloat64(0);
  }
  readFloat() {
    const d = new DataView(Uint8Array.from(this.b.slice(this.r, this.r + 4)).buffer);
    this.r += 4;
    return d.getFloat32(0);
  }
  rest() { return this.b.length - this.r; }
}

const posDecode = (p) => [(Number(p >> 38n) << 38 >> 38) | 0, Number(p << 52n >> 52n), 0];
function decodePosLong(p) {
  const bi = BigInt(p);
  const x = Number(bi >> 38n) | 0;          // sign-extend via int ops
  const y = Number(BigInt.asIntN(12, (bi << 52n) >> 52n));
  const z = Number(BigInt.asIntN(26, (bi << 26n) >> 38n));
  return [x, y, z];
}

function wsFrame(port, path = '/') {
  const connRef = { closedFlag: false };
  // Minimal raw WebSocket client (no extensions, small frames only)
  const key = Buffer.from('eaglercraftSmokeTest').toString('base64').slice(0, 22) + '==';
  const sock = net.connect(port, '127.0.0.1');
  let opened = false;
  let buffer = Buffer.alloc(0);
  const listeners = [];
  sock.on('connect', () => {
    sock.write(
      `GET ${path} HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nUpgrade: websocket\r\n` +
      `Connection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`);
  });
  sock.on('data', (d) => {
    buffer = Buffer.concat([buffer, d]);
    if (!opened) {
      const idx = buffer.indexOf('\r\n\r\n');
      if (idx < 0) return;
      const head = buffer.slice(0, idx).toString();
      if (!head.includes('101')) {
        listeners.forEach((l) => l({ type: 'handshake-failed', head }));
        return;
      }
      opened = true;
      buffer = buffer.slice(idx + 4);
      listeners.forEach((l) => l({ type: 'open' }));
    }
    // parse binary frames (server->client, unmasked)
    while (buffer.length >= 2) {
      const fin = buffer[0] & 0x80;
      const opcode = buffer[0] & 0x0f;
      let len = buffer[1] & 0x7f;
      let off = 2;
      if (len === 126) { len = buffer.readUInt16BE(2); off = 4; }
      else if (len === 127) { len = Number(buffer.readBigUInt64BE(2)); off = 10; }
      if (buffer.length < off + len) return;
      const payload = buffer.slice(off, off + len);
      buffer = buffer.slice(off + len);
      if (opcode === 0x2 || opcode === 0x0) listeners.forEach((l) => l({ type: 'binary', data: payload, fin }));
      else if (opcode === 0x8) {
        connRef.closedFlag = true;
        listeners.forEach((l) => l({ type: 'close', code: payload.length >= 2 ? payload.readUInt16BE(0) : 0 }));
      }
    }
  });
  const sendBinary = (bytes) => {
    const mask = Buffer.from([1, 2, 3, 4]);
    const masked = Buffer.from(bytes);
    for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
    let header;
    const len = masked.length;
    if (len < 126) header = Buffer.from([0x82, 0x80 | len]);
    else if (len < 65536) { header = Buffer.alloc(4); header[0] = 0x82; header[1] = 0x80 | 126; header.writeUInt16BE(len, 2); }
    else { header = Buffer.alloc(10); header[0] = 0x82; header[1] = 0x80 | 127; header.writeBigUInt64BE(BigInt(len), 2); }
    sock.write(Buffer.concat([header, mask, masked]));
  };
  return { sock, sendBinary, on: (fn) => listeners.push(fn), get closedFlag() { return connRef.closedFlag; } };
}

function awaitEvent(conn, type, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout waiting ${type}`)), timeoutMs);
    conn.on((e) => {
      if (e.type === type) { clearTimeout(t); resolve(e); }
    });
  });
}

// framed packet reader over binary events: every complete frame is one
// packet; frames queue up until next() consumes them (the server may
// burst several packets inside one TCP segment)
function packetReader(conn) {
  const queue = [];
  let frag = null;
  const waiters = [];
  const drain = () => {
    while (queue.length && waiters.length) {
      const data = queue.shift();
      const buf = new Buf(data);
      try {
        const id = buf.readVarInt();
        waiters.shift()({ id, buf });
      } catch (err) {
        waiters.shift()({ id: -1, buf, err });
      }
    }
  };
  conn.on((e) => {
    if (e.type === 'binary' && process.env.SMOKE_DEBUG) {
      console.log(`  DEBUG frame: fin=${e.fin} len=${e.data.length} ${e.data.toString('hex').slice(0, 100)}`);
    }
    if (e.type !== 'binary') return;
    if ((e.fin & 0x80) !== 0 && frag === null) {
      queue.push(e.data);
    } else if ((e.fin & 0x80) !== 0) {
      frag = Buffer.concat([frag, e.data]);
      queue.push(frag);
      frag = null;
    } else {
      frag = frag ? Buffer.concat([frag, e.data]) : e.data;
    }
    drain();
  });
  return {
    next(timeoutMs = 5000) {
      if (queue.length) {
        const data = queue.shift();
        const buf = new Buf(data);
        const id = buf.readVarInt();
        return Promise.resolve({ id, buf });
      }
      return new Promise((resolve, reject) => {
        const t = setTimeout(() => reject(new Error('timeout waiting packet')), timeoutMs);
        waiters.push((p) => { clearTimeout(t); resolve(p); });
      });
    },
  };
}

// ==================== the actual smoke ====================
async function statusProbe() {
  const conn = wsFrame(PORT);
  await awaitEvent(conn, 'open');
  const p = packetReader(conn);
  conn.sendBinary(new Buf().varint(0x00).varint(775).utf('127.0.0.1').short(PORT).varint(1).toBytes());
  conn.sendBinary(new Buf().varint(0x00).toBytes()); // status request
  const resp = await p.next();
  check('status: response packet id 0x00', resp.id === 0x00);
  const json = JSON.parse(resp.buf.readUtf());
  check('status: protocol 775', json.version?.protocol === 775, JSON.stringify(json).slice(0, 120));
  check('status: version name 26.1.2', json.version?.name === '26.1.2');
  check('status: description present', typeof json.description?.text === 'string');
  const token = 0x12345678;
  conn.sendBinary(new Buf().varint(0x01).long(token).toBytes());
  const pong = await p.next();
  check('status: pong echoes token', pong.id === 0x01 && pong.buf.readLong() === token);
  conn.sock.destroy();
}

async function joinAndDig() {
  const conn = wsFrame(PORT);
  await awaitEvent(conn, 'open');
  const p = packetReader(conn);
  // handshake -> login
  conn.sendBinary(new Buf().varint(0x00).varint(775).utf('127.0.0.1').short(PORT).varint(2).toBytes());
  conn.sendBinary(new Buf().varint(0x00).long(0n).long(0n).utf('SmokeTester').varint(0).toBytes());
  const ack = await p.next();
  check('login: acknowledge 0x01', ack.id === 0x01);
  const success = await p.next();
  check('login: success 0x02', success.id === 0x02);
  success.buf.readLong(); success.buf.readLong();
  check('login: name echoed', success.buf.readUtf() === 'SmokeTester');
  check('login: zero properties', success.buf.readVarInt() === 0);
  // configuration burst
  const brand = await p.next();
  check('config: brand plugin message', brand.id === 0x00);
  const channel = brand.buf.readUtf();
  check('config: minecraft:brand channel', channel === 'minecraft:brand');
  check('config: brand string', brand.buf.readUtf().length > 0);
  const registry = await p.next();
  if (registry.id !== 0x06) console.log(`  DEBUG: unexpected packet id 0x${registry.id.toString(16)} with ${registry.buf.rest()} bytes: ${Buffer.from(registry.buf.b.slice(registry.buf.r)).toString('hex').slice(0, 80)}`);
  check('config: registry data 0x06', registry.id === 0x06);
  const regCount = registry.buf.readVarInt();
  check('config: registry count sane', regCount >= 0 && regCount < 64);
  for (let r = 0; r < regCount; r++) {
    registry.buf.readUtf();
    const n = registry.buf.readVarInt();
    for (let e = 0; e < n; e++) registry.buf.readUtf();
  }
  const flags = await p.next();
  check('config: feature flags 0x07', flags.id === 0x07 && flags.buf.readVarInt() === 0);
  const packs = await p.next();
  check('config: known packs 0x08', packs.id === 0x08 && packs.buf.readVarInt() === 0);
  const finish = await p.next();
  check('config: finish 0x02', finish.id === 0x02);
  // client replies: client info, brand, known packs, ack
  conn.sendBinary(new Buf().varint(0x00).utf('en_us').byte(8).varint(0).boolean(true).ubyte(0x7f).varint(1).boolean(false).boolean(true).toBytes());
  conn.sendBinary(new Buf().varint(0x01).utf('minecraft:brand').utf('eaglercraft-smoke').toBytes());
  conn.sendBinary(new Buf().varint(0x05).varint(0).toBytes());
  conn.sendBinary(new Buf().varint(0x02).toBytes());
  // play login
  const login = await p.next();
  check('play: login 0x2A', login.id === 0x2A);
  const entityId = login.buf.readInt();
  const gameMode = login.buf.readUByte();
  const dimension = login.buf.readUtf();
  login.buf.readLong();
  const difficulty = login.buf.readUByte();
  const hardcore = login.buf.readBoolean();
  const levelType = login.buf.readUtf();
  const viewDist = login.buf.readVarInt();
  const dayTime = login.buf.readLong();
  const gameTime = login.buf.readLong();
  const sx = login.buf.readDouble(), sy = login.buf.readDouble(), sz = login.buf.readDouble();
  check('play: survival gamemode', gameMode === 0, `got ${gameMode}`);
  check('play: overworld dimension', dimension === 'minecraft:overworld');
  check('play: flat level type', levelType === 'flat');
  check('play: dawn day time', dayTime === 1000, `got ${dayTime}`);
  check('play: spawn on grass', sy === -60, `got ${sy}`);
  check('play: normal difficulty', difficulty === 2 && !hardcore);
  // teleport
  const tp = await p.next();
  check('play: player position 0x38', tp.id === 0x38);
  tp.buf.readDouble(); tp.buf.readDouble(); tp.buf.readDouble();
  tp.buf.readFloat(); tp.buf.readFloat();
  tp.buf.readByte();
  const tpId = tp.buf.readVarInt();
  conn.sendBinary(new Buf().varint(0x00).varint(tpId).toBytes());
  // chunks: 81 columns, each one section with the base
  let chunks = 0;
  let sawTree = false;
  for (let i = 0; i < 81; i++) {
    const c = await p.next(15000);
    if (c.id !== 0x2E) { check('play: chunk packet 0x2E', false, `got 0x${c.id.toString(16)}`); break; }
    chunks++;
    const cx = c.buf.readInt(), cz = c.buf.readInt();
    const sections = c.buf.readVarInt();
    for (let s = 0; s < sections; s++) {
      const sy = c.buf.readByte();
      const palLen = c.buf.readVarInt();
      const pal = [];
      for (let k = 0; k < palLen; k++) pal.push(c.buf.readVarInt());
      const voxels = [];
      for (let k = 0; k < 4096; k++) voxels.push(c.buf.readByte());
      if (cx === 0 && cz === 0) {
        // verify the base layers: y=-64 bedrock, -63/-62 dirt, -61 grass
        const idx = (y, z, x) => (((y + 64) & 15) << 8) | ((z & 15) << 4) | (x & 15);
        check('chunk 0,0: bedrock at y=-64', pal[voxels[idx(-64, 0, 0)]] === 33);
        check('chunk 0,0: dirt at y=-63', pal[voxels[idx(-63, 0, 0)]] === 10);
        check('chunk 0,0: grass at y=-61', pal[voxels[idx(-61, 0, 0)]] === 9);
        check('chunk 0,0: air above', pal[voxels[idx(-50, 0, 0)]] === 0);
      }
      if (cx === 0 && cz === 0 && pal.includes(351)) sawTree = true; // the (0,0) column borders the tree at (6,2)? actually tree trunk col is (0,0)! x=6,z=2 -> chunk 0,0
    }
  }
  check('play: streamed 81 columns', chunks === 81, `got ${chunks}`);
  check('play: oak_log in the tree column', sawTree);
  // the join broadcast rides behind the chunk stream
  const joined = await p.next();
  check('chat: join message 0x4F', joined.id === 0x4F, `got 0x${joined.id.toString(16)}`);
  // dig the first tree's bottom log at (3, -60, 1)
  conn.sendBinary(new Buf().varint(0x1C).varint(0).pos(3, -60, 1).byte(1).toBytes()); // start
  conn.sendBinary(new Buf().varint(0x1C).varint(2).pos(3, -60, 1).byte(1).toBytes()); // finish
  const bu = await p.next();
  check('dig: block update 0x05', bu.id === 0x05);
  const [bx, by, bz] = decodePosLong(bu.buf.readLong());
  const newId = bu.buf.readVarInt();
  check('dig: the log became air at (3,-60,1)', bx === 3 && by === -60 && bz === 1 && newId === 0, `got (${bx},${by},${bz})=${newId}`);
  const slot = await p.next();
  check('dig: set container slot 0x0E', slot.id === 0x0E);
  slot.buf.readUByte();
  const slotIdx = slot.buf.readShort();
  const itemId = slot.buf.readVarInt();
  const count = slot.buf.readVarInt();
  check('dig: oak_log into the hotbar', itemId === 351 && count === 1, `slot=${slotIdx} item=${itemId} x${count}`);
  // place the log right back onto the dug cell: click the top face of
  // the grass below it ((3,-61,1), in reach) -> the log returns to
  // (3,-60,1) and the world is restored for the browser test
  conn.sendBinary(new Buf().varint(0x22).short(slotIdx).toBytes()); // carry the log
  conn.sendBinary(new Buf().varint(0x2A).pos(3, -61, 1).byte(1).toBytes());
  const bu2 = await p.next();
  check('place: block update 0x05', bu2.id === 0x05);
  const [px, py, pz] = decodePosLong(bu2.buf.readLong());
  const placedId = bu2.buf.readVarInt();
  check('place: log restored at (3,-60,1)', px === 3 && py === -60 && pz === 1 && placedId === 351, `got (${px},${py},${pz})=${placedId}`);
  const slot2 = await p.next();
  check('place: carried slot emptied', slot2.id === 0x0E && (() => { slot2.buf.readUByte(); slot2.buf.readShort(); return slot2.buf.readVarInt(); })() === 0);
  // chat round trip
  conn.sendBinary(new Buf().varint(0x07).utf('gateway protocol works!').toBytes());
  const chat = await p.next();
  check('chat: system chat 0x4F', chat.id === 0x4F && chat.buf.readUtf().includes('gateway protocol works'));
  // invalid username gets kicked at login (separate connection)
  conn.sock.destroy();
}

async function invalidUsername() {
  const conn = wsFrame(PORT);
  await awaitEvent(conn, 'open');
  const p = packetReader(conn);
  conn.sendBinary(new Buf().varint(0x00).varint(775).utf('127.0.0.1').short(PORT).varint(2).toBytes());
  conn.sendBinary(new Buf().varint(0x00).long(0n).long(0n).utf('bad name!').varint(0).toBytes());
  const disc = await p.next();
  check('login: invalid username -> disconnect 0x03', disc.id === 0x03);
  const close = await new Promise((resolve) => {
    if (conn.closedFlag || conn.sock.readyState === 'closed' || conn.sock.destroyed) { resolve({ type: 'close' }); return; }
    const t = setTimeout(() => resolve(null), 5000);
    conn.on((e) => { if (e.type === 'close') { clearTimeout(t); resolve(e); } });
  });
  check('login: socket closed after disconnect', close !== null);
  conn.sock.destroy();
}

const run = async () => {
  console.log(`real gateway protocol smoke -> 127.0.0.1:${PORT}`);
  await statusProbe();
  await joinAndDig();
  await invalidUsername();
  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail > 0 ? 1 : 0);
};
run().catch((e) => { console.error('SMOKE ERROR:', e.stack || e.message); process.exit(1); });
