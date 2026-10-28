#!/usr/bin/env python3
"""EPK v2 round-trip tool for the 26.1.2 asset bundle.

Layout (verified against public/assets.epk: 10464 entries, ends at EOF):
  header (20 bytes): "EAGPKG!" + NUL | u16 version=2 (LE) | u32 fileCount (LE) | u32 0
  entries:           u16 nameLen (LE) | name bytes | u32 dataLen (LE) | data
  entry 0 is always _epk_manifest.json (the JSON index of every file).

Usage:
  python3 scripts/epk_tool.py replace public/assets.epk \
      assets/minecraft/textures/gui/title/background/panorama_0.png=path.png [...]
  python3 scripts/epk_tool.py extract public/assets.epk <name> <out>
  python3 scripts/epk_tool.py verify public/assets.epk
"""
import json
import struct
import sys
import zlib

MAGIC = b'EAGPKG!\x00'
MANIFEST = '_epk_manifest.json'


def parse(data):
    if data[:8] != MAGIC:
        raise SystemExit('not an EPK (bad magic)')
    version = struct.unpack_from('<H', data, 8)[0]
    count = struct.unpack_from('<I', data, 12)[0]
    if version != 2:
        raise SystemExit(f'unsupported EPK version {version}')
    off = 20
    entries = []
    while off < len(data):
        (nl,) = struct.unpack_from('<H', data, off)
        off += 2
        name = data[off:off + nl].decode()
        off += nl
        (dl,) = struct.unpack_from('<I', data, off)
        off += 4
        entries.append((name, data[off:off + dl]))
        off += dl
    if off != len(data):
        raise SystemExit(f'trailing bytes: {off} != {len(data)}')
    if len(entries) != count:
        raise SystemExit(f'entry count mismatch: {len(entries)} != {count}')
    return entries


def build(entries):
    out = bytearray()
    out += MAGIC
    out += struct.pack('<H', 2)      # version @8
    out += b'\x00\x00'               # padding @10 (the game reads count @12!)
    out += struct.pack('<I', len(entries))  # fileCount @12
    out += struct.pack('<I', 0)      # flags @16 -> entries start at 20
    for name, blob in entries:
        nb = name.encode()
        out += struct.pack('<H', len(nb))
        out += nb
        out += struct.pack('<I', len(blob))
        out += blob
    return bytes(out)


def refresh_manifest(entries):
    """Rewrites _epk_manifest.json so sizes/crc32 match the new payloads.
    The manifest lists its own size too - iterate until self-consistent."""
    def build_manifest(self_size):
        files = []
        for name, blob in entries:
            if name == MANIFEST:
                # self-referential: nothing consumes this crc (the loader
                # walks raw entries) - a stable placeholder keeps it honest
                files.append({'name': name, 'size': self_size, 'crc32': '00000000'})
            else:
                files.append({'name': name, 'size': len(blob),
                              'crc32': f'{zlib.crc32(blob) & 0xFFFFFFFF:08x}'})
        return json.dumps({
            'clientVersion': '26.1.2',
            'epkVersion': 2,
            'fileCount': len(files),
            'files': files,
        }, indent=2) + '\n'
    # two passes: the self-referential size settles immediately
    size = 0
    for _ in range(4):
        blob = build_manifest(size).encode()
        if len(blob) == size:
            break
        size = len(blob)
    out = [(MANIFEST, blob)]
    for name, b in entries:
        if name != MANIFEST:
            out.append((name, b))
    return out


def main():
    cmd = sys.argv[1]
    epk = sys.argv[2]
    data = open(epk, 'rb').read()
    if cmd == 'verify':
        entries = parse(data)
        mf = json.loads(dict(entries)[MANIFEST])
        sizes = {f['name']: f['size'] for f in mf['files']}
        bad = [n for n, b in entries if sizes.get(n) not in (None, len(b))]
        print(f'{len(entries)} entries; manifest size mismatches: {len(bad)}')
        for n in bad[:8]:
            print('  MISMATCH', n)
        return
    if cmd == 'extract':
        entries = dict((n, b) for n, b in parse(data))
        name = sys.argv[3]
        open(sys.argv[4], 'wb').write(entries[name])
        print(f'extracted {name} ({len(entries[name])} bytes)')
        return
    if cmd == 'replace':
        entries = parse(data)
        table = dict(entries)
        for spec in sys.argv[3:]:
            epk_name, _, path = spec.partition('=')
            blob = open(path, 'rb').read()
            if epk_name not in table:
                raise SystemExit(f'no such EPK entry: {epk_name}')
            print(f'replace {epk_name}: {len(table[epk_name])} -> {len(blob)} bytes')
            table[epk_name] = blob
        rebuilt = refresh_manifest([(n, table[n]) for n, _ in entries])
        open(epk, 'wb').write(build(rebuilt))
        print(f'rewrote {epk} ({len(rebuilt)} entries)')
        return
    raise SystemExit(__doc__)


if __name__ == '__main__':
    main()
