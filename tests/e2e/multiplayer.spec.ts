import { spawn, type ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import net from 'node:net';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test } from './fixtures';

/**
 * Multiplayer smoke test: drives the production gateway stack end to end
 * from a real browser:
 *
 *   Playwright page - WS - EaglerWSListener - EaglerPlayerSession
 *     - TCP - McBridge - McProtocolStub (chat echo backend)
 *
 * The gateway runs through GatewaySmokeMain — the exact same listener,
 * session and bridge classes the BungeeCord plugin uses, minus the proxy
 * runtime. Wire protocol is documented in gateway/PlaceholderServer/PROTOCOL.md:
 * 0x01 handshake/accept, 0x03 login, 0x04 login result, 0x05 chat, 0xFF kick.
 */

const GW_PORT = 28321;
const MC_PORT = 28322;
const BOOT_TIMEOUT = 30_000;

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));
const PLACEHOLDER_JAR = join(REPO_ROOT, 'gateway', 'PlaceholderServer', 'build', 'libs', 'PlaceholderServer-1.0.0.jar');
const BUNGEE_JAR = join(REPO_ROOT, 'gateway', 'EaglercraftXBungee', 'build', 'libs', 'EaglercraftXBungee-1.3.2.jar');

const jarsBuilt = existsSync(PLACEHOLDER_JAR) && existsSync(BUNGEE_JAR);

let mcStub: ChildProcess | undefined;
let gateway: ChildProcess | undefined;
const procLog: Record<string, string[]> = { 'mc-stub': [], gateway: [] };

function tailLog(proc: ChildProcess | undefined, name: string): string {
  const captured = procLog[name]?.join('');
  if (captured) {
    return `[${name}] ${captured}`.trim();
  }
  if (!proc) {
    return `(not started)`;
  }
  const out = (proc.stdout?.read() as Buffer | null) || Buffer.alloc(0);
  const err = (proc.stderr?.read() as Buffer | null) || Buffer.alloc(0);
  return `[${name}] ${out.toString()} ${err.toString()}`.trim();
}

async function waitForPort(port: number, timeoutMs: number, label: string): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const ok = await new Promise<boolean>((resolve) => {
      const socket = net.connect(port, '127.0.0.1');
      socket.once('connect', () => {
        socket.destroy();
        resolve(true);
      });
      socket.once('error', () => resolve(false));
      setTimeout(() => {
        socket.destroy();
        resolve(false);
      }, 1000);
    });
    if (ok) {
      return;
    }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(
    `${label} did not open port ${port} within ${timeoutMs}ms\n`
    + tailLog(mcStub, 'mc-stub') + '\n'
    + tailLog(gateway, 'gateway'),
  );
}

test.describe('multiplayer gateway smoke', () => {
  test.skip(!jarsBuilt, 'gateway jars are not built (run ./gradlew :EaglercraftXBungee:build :PlaceholderServer:build first)');

  test.beforeAll(async () => {
    const stdio: ('pipe' | 'ignore')[] = ['ignore', 'pipe', 'pipe'];
    mcStub = spawn('java', [
      '-cp', PLACEHOLDER_JAR,
      'net.lax1dude.eaglercraft.v2_6.placeholder_server.mc.McProtocolStub',
      '--port', String(MC_PORT), '--interval-ms', '2000', '--verbose',
    ], { stdio });
    gateway = spawn('java', [
      '-cp', BUNGEE_JAR,
      'net.lax1dude.eaglercraft.v2_6.plugin.gateway_bungee.GatewaySmokeMain',
      '--port', String(GW_PORT), '--server', `lobby=127.0.0.1:${MC_PORT}`, '--verbose',
    ], { stdio });
    for (const [label, proc] of [['mc-stub', mcStub], ['gateway', gateway]] as const) {
      proc.on('error', (e) => console.log(`[${label}] spawn error:`, e.message));
      proc.on('exit', (code, signal) => console.log(`[${label}] exited code=${code} signal=${signal}`));
      proc.stdout?.on('data', (d: Buffer) => procLog[label].push(d.toString()));
      proc.stderr?.on('data', (d: Buffer) => procLog[label].push(d.toString()));
    }
    await waitForPort(MC_PORT, BOOT_TIMEOUT, 'McProtocolStub');
    await waitForPort(GW_PORT, BOOT_TIMEOUT, 'GatewaySmokeMain');
  });

  test.afterAll(async () => {
    for (const proc of [gateway, mcStub]) {
      if (proc && proc.exitCode === null && !proc.killed) {
        proc.kill('SIGTERM');
      }
    }
    // Give both processes a moment to exit, then force-kill stragglers.
    await new Promise((r) => setTimeout(r, 500));
    for (const proc of [gateway, mcStub]) {
      if (proc && proc.exitCode === null && !proc.killed) {
        proc.kill('SIGKILL');
      }
    }
  });

  interface Frame {
    id: number;
    status?: number;
    parts: string[];
  }

  test('logs an Eagler player in and relays chat through the MC bridge', async ({ page }) => {
    const result = await page.evaluate(({ gwPort }): Promise<{ closeCode: number; echoText: string | null }> => {
      const u16str = (s: string): Uint8Array => {
        const b = new TextEncoder().encode(s);
        const out = new Uint8Array(2 + b.length);
        out[0] = b.length >> 8;
        out[1] = b.length & 255;
        out.set(b, 2);
        return out;
      };
      const frame = (id: number, ...strs: string[]): Uint8Array => {
        const parts = strs.map(u16str);
        const total = 1 + parts.reduce((a, p) => a + p.length, 0);
        const out = new Uint8Array(total);
        out[0] = id;
        let off = 1;
        for (const p of parts) {
          out.set(p, off);
          off += p.length;
        }
        return out;
      };
      const decode = (buf: ArrayBuffer): Frame => {
        const dv = new DataView(buf);
        const id = dv.getUint8(0);
        const f: Frame = { id, parts: [] };
        if (id === 0x04 && dv.byteLength >= 2) {
          f.status = dv.getUint8(1);
        }
        let off = 1;
        if (id === 0x04) {
          off = 2; // status byte consumed
        }
        while (off + 2 <= dv.byteLength) {
          const len = dv.getUint16(off);
          off += 2;
          if (off + len > dv.byteLength) {
            break;
          }
          f.parts.push(new TextDecoder().decode(new Uint8Array(buf, off, len)));
          off += len;
        }
        return f;
      };

      return new Promise((resolve, reject) => {
        const ws = new WebSocket(`ws://127.0.0.1:${gwPort}/`);
        ws.binaryType = 'arraybuffer';
        const queue: Frame[] = [];
        const waiters: ((f: Frame) => void)[] = [];
        const timer = setTimeout(
          () => reject(new Error(`gateway protocol timed out; frames so far: ${JSON.stringify(queue)}`)),
          15_000,
        );

        const pump = (ev: MessageEvent) => {
          if (!(ev.data instanceof ArrayBuffer)) {
            return; // text frames would be a protocol violation
          }
          const f = decode(ev.data);
          const w = waiters.shift();
          if (w) {
            w(f);
          } else {
            queue.push(f);
          }
        };
        const nextFrame = (): Promise<Frame> =>
          queue.length > 0 ? Promise.resolve(queue.shift()!) : new Promise((res) => waiters.push(res));

        let echoText: string | null = null;
        ws.onmessage = pump;
        ws.onerror = () => reject(new Error('websocket error'));
        ws.onclose = (ev) => {
          clearTimeout(timer);
          resolve({ closeCode: ev.code, echoText: echoText });
        };

        ws.onopen = async () => {
          try {
            // 1. handshake -> accept
            ws.send(frame(0x01, 'e2e-multiplayer', 'E2EPlayer'));
            const accept = await nextFrame();
            if (accept.id !== 0x01) {
              throw new Error(`expected handshake accept, got ${JSON.stringify(accept)}`);
            }
            if (!accept.parts.some((p) => p.includes('Eaglercraft'))) {
              throw new Error(`accept brand unexpected: ${JSON.stringify(accept)}`);
            }

            // 2. login -> result status 0 (session + MC bridge established)
            ws.send(frame(0x03, 'E2EPlayer', ''));
            const login = await nextFrame();
            if (login.id !== 0x04 || login.status !== 0) {
              throw new Error(`expected login result status 0, got ${JSON.stringify(login)}`);
            }

            // 3. chat round-trip: browser -> gateway -> McBridge -> stub -> back
            ws.send(frame(0x05, 'hello from e2e'));
            let chat: Frame | undefined;
            for (let i = 0; i < 5; i++) {
              const f = await nextFrame();
              if (f.id === 0x05 && f.parts[0]?.includes('hello from e2e')) {
                chat = f;
                break;
              }
              if (f.id === 0xFF) {
                throw new Error(`kicked during chat: ${JSON.stringify(f)}`);
              }
              // keepalives from the stub are answered gateway-side; any other
              // frames (e.g. "Connected to server" notice) are tolerated.
            }
            if (!chat) {
              throw new Error('chat echo never arrived');
            }
            echoText = chat.parts[0] ?? null;

            // 4. ping/pong still works while logged in
            ws.send(frame(0x02, 'lat'));
            const pong = await nextFrame();
            if (pong.id !== 0x02) {
              throw new Error(`expected pong, got ${JSON.stringify(pong)}`);
            }

            ws.close(1000, 'done');
          } catch (err) {
            clearTimeout(timer);
            reject(err as Error);
            ws.close();
          }
        };
      });
    }, { gwPort: GW_PORT });

    // The stub labels its echo "<player> echoed: <text>"; seeing that exact
    // string in the browser proves the full path (WS -> listener -> session
    // -> McBridge -> backend -> back).
    expect(result.echoText).toContain('E2EPlayer echoed: hello from e2e');
    expect(result.closeCode).toBe(1000);
  });

  test('rejects logins with invalid usernames (status 1)', async ({ page }) => {
    const result = await page.evaluate(({ gwPort }): Promise<{ id: number; status?: number; parts: string[] }> => {
      const u16str = (s: string): Uint8Array => {
        const b = new TextEncoder().encode(s);
        const out = new Uint8Array(2 + b.length);
        out[0] = b.length >> 8;
        out[1] = b.length & 255;
        out.set(b, 2);
        return out;
      };
      return new Promise((resolve, reject) => {
        const ws = new WebSocket(`ws://127.0.0.1:${gwPort}/`);
        ws.binaryType = 'arraybuffer';
        const timer = setTimeout(() => reject(new Error('rejection test timed out')), 15_000);
        ws.onmessage = (ev) => {
          if (!(ev.data instanceof ArrayBuffer)) {
            return;
          }
          const dv = new DataView(ev.data);
          const id = dv.getUint8(0);
          if (id === 0x01) {
            return; // handshake accept; the login result follows the 0x03
          }
          const status = id === 0x04 && dv.byteLength >= 2 ? dv.getUint8(1) : undefined;
          const parts: string[] = [];
          let off = id === 0x04 ? 2 : 1;
          while (off + 2 <= dv.byteLength) {
            const len = dv.getUint16(off);
            off += 2;
            if (off + len > dv.byteLength) {
              break;
            }
            parts.push(new TextDecoder().decode(new Uint8Array(ev.data, off, len)));
            off += len;
          }
          clearTimeout(timer);
          resolve({ id, status, parts });
          ws.close();
        };
        ws.onerror = () => {
          clearTimeout(timer);
          reject(new Error('websocket error'));
        };
        ws.onopen = () => {
          ws.send(new Uint8Array([0x01, ...(() => {
            const b = u16str('e2e-multiplayer');
            const u = u16str('bad name!');
            return [...b, ...u];
          })()]));
          setTimeout(() => ws.send(new Uint8Array([0x03, ...(() => {
            const u = u16str('bad name!');
            const t = u16str('');
            return [...u, ...t];
          })()])), 250);
        };
      });
    }, { gwPort: GW_PORT });

    expect(result.id).toBe(0x04);
    expect(result.status).toBe(1);
  });
});
