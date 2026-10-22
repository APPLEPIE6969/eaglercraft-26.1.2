package net.lax1dude.eaglercraft.v2_6.gui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * EPK (EaglerCraft Package) compiler for EaglerCraft 26.1.2.
 *
 * <p>Packages game assets (textures, sounds, models, lang files, ...) into a
 * single {@code assets.epk} archive consumed by the browser client.</p>
 *
 * <p>This is the tool referenced by the workspace {@code makeEPK} Gradle task:</p>
 * <pre>
 *   task makeEPK(type: JavaExec) {
 *       mainClass = 'net.lax1dude.eaglercraft.v2_6.gui.EPKCompiler'
 *       args = ['src/resources', 'output/assets.epk']
 *   }
 * </pre>
 *
 * <p>It can also be run directly without Gradle (JDK 21+ single-file source
 * launcher), which is how the repo-root build produces {@code public/assets.epk}:</p>
 * <pre>
 *   java sources/teavm/java/net/lax1dude/eaglercraft/v2_6/gui/EPKCompiler.java sources/resources public/assets.epk
 *   java sources/teavm/java/net/lax1dude/eaglercraft/v2_6/gui/EPKCompiler.java --verify public/assets.epk [sources/resources]
 * </pre>
 *
 * <h2>EPK file format (must match EPKLoader.java in the client)</h2>
 * <pre>
 * Offset  Size  Field
 * 0       8     magic field: ASCII "EAGPKG!" + NUL padding byte
 * 8       2     format version, u16 little-endian (currently 2)
 * 10      2     flags, u16 little-endian
 *                 bit 0: 0 = entries stored uncompressed (required),
 *                        1 = reserved for future per-entry compression
 *                 bits 1-15: reserved, must be 0
 * 12      4     file count, u32 little-endian
 * 16      4     reserved, u32 little-endian, must be 0
 * 20      ...   entries, back to back until EOF:
 *                 2  name length, u16 little-endian
 *                 n  name bytes, UTF-8, '/' separators, no leading '/'
 *                 4  data length, u32 little-endian
 *                 d  data bytes, stored verbatim (no compression)
 * </pre>
 *
 * <p>The {@code extractFile}/{@code listFiles} JSBody parsers in
 * {@code net.lax1dude.eaglercraft.v2_6.internal.teavm.EPKLoader} walk entries
 * with a 20-byte header offset and slice the data region directly, therefore
 * entries MUST be stored uncompressed (flags bit 0 = 0).</p>
 *
 * <p>Builds are deterministic: entries are sorted lexicographically by path,
 * and no timestamps are embedded.</p>
 */
public class EPKCompiler {

        /** ASCII magic the client's EPKLoader checks. */
        static final String EPK_MAGIC = "EAGPKG!";

        /** Current EPK format version written to the header. */
        static final int EPK_VERSION = 2;

        /** Entry payload flag: entries are stored verbatim (required by EPKLoader). */
        static final int EPK_FLAG_STORED = 0x0;

        /** Size of the fixed header, in bytes. */
        static final int EPK_HEADER_SIZE = 20;

        /** Max length of a single entry name (u16 field). */
        private static final int MAX_NAME_LEN = 0xFFFF;

        /** Max length of a single entry payload (u32 field). */
        private static final long MAX_DATA_LEN = 0xFFFFFFFFL;

        // The magic FIELD is 8 bytes: the 7 ASCII chars of "EAGPKG!" followed by a
        // NUL padding byte. EPKLoader.parseEPKHeader reads 8 bytes and checks
        // startsWith("EAGPKG!"), and expects the version field at offset 8 — so the
        // magic field MUST be padded to 8 bytes.
        private static final byte[] MAGIC_BYTES = (EPK_MAGIC + "\0").getBytes(StandardCharsets.US_ASCII);

        static {
                if (MAGIC_BYTES.length != 8) {
                        throw new AssertionError("EPK magic field must be exactly 8 bytes");
                }
        }

        /** One entry staged for writing. */
        private static final class Entry {
                final String name;
                final Path path;
                final long size;
                final long crc32;

                Entry(String name, Path path, long size, long crc32) {
                        this.name = name;
                        this.path = path;
                        this.size = size;
                        this.crc32 = crc32;
                }
        }

        public static void main(String[] args) throws IOException {
                if (args.length == 2 && !args[0].startsWith("-")) {
                        compile(Paths.get(args[0]), Paths.get(args[1]));
                        return;
                }
                if (args.length >= 2 && "--verify".equals(args[0])) {
                        Path epk = Paths.get(args[1]);
                        Path source = args.length >= 3 ? Paths.get(args[2]) : null;
                        boolean ok = verify(epk, source);
                        System.exit(ok ? 0 : 1);
                        return;
                }
                System.err.println("Usage: EPKCompiler <input-dir> <output.epk>");
                System.err.println("       EPKCompiler --verify <file.epk> [input-dir]");
                System.exit(2);
        }

        // ==================== compile ====================

        /**
         * Compiles {@code inputDir} into an EPK archive at {@code outputFile}.
         */
        static void compile(Path inputDir, Path outputFile) throws IOException {
                if (!Files.isDirectory(inputDir)) {
                        throw new IOException("Input directory not found: " + inputDir.toAbsolutePath());
                }

                List<Entry> entries = collectEntries(inputDir);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                writeHeader(out, EPK_VERSION, EPK_FLAG_STORED, entries.size());
                for (Entry e : entries) {
                        byte[] nameBytes = e.name.getBytes(StandardCharsets.UTF_8);
                        if (nameBytes.length > MAX_NAME_LEN) {
                                throw new IOException("Entry name too long (" + nameBytes.length + " bytes): " + e.name);
                        }
                        if (e.size > MAX_DATA_LEN) {
                                throw new IOException("Entry too large for u32 length: " + e.name);
                        }
                        byte[] data = Files.readAllBytes(e.path);
                        if (data.length != e.size) {
                                throw new IOException("File changed while compiling: " + e.name);
                        }
                        writeU16(out, nameBytes.length);
                        out.write(nameBytes);
                        writeU32(out, data.length);
                        out.write(data);
                }

                Path parent = outputFile.toAbsolutePath().getParent();
                if (parent != null) {
                        Files.createDirectories(parent);
                }
                Files.write(outputFile, out.toByteArray());

                long payload = out.size() - EPK_HEADER_SIZE;
                System.out.println("[EPKCompiler] " + entries.size() + " entries, "
                                + (out.size() / 1024) + " KiB total (" + (payload / 1024) + " KiB payload)");
                System.out.println("[EPKCompiler] wrote " + outputFile.toAbsolutePath());
                for (Entry e : entries) {
                        System.out.println("[EPKCompiler]   " + e.name + " (" + e.size + " B, crc32 " + hex32(e.crc32) + ")");
                }
        }

        /**
         * Recursively collects regular files under {@code inputDir}, sorted by
         * relative path for deterministic output.
         */
        private static List<Entry> collectEntries(Path inputDir) throws IOException {
                Path absRoot = inputDir.toAbsolutePath().normalize();
                List<Entry> entries = new ArrayList<>();
                try (Stream<Path> walk = Files.walk(absRoot)) {
                        walk.filter(Files::isRegularFile).forEach(p -> {
                                String rel = absRoot.relativize(p.toAbsolutePath().normalize())
                                                .toString().replace('\\', '/');
                                if (rel.isEmpty()) {
                                        return;
                                }
                                // The EPK is an asset bundle: skip editor/OS droppings.
                                String[] segs = rel.split("/");
                                boolean skip = false;
                                for (String seg : segs) {
                                        if (seg.startsWith(".") || "Thumbs.db".equals(seg)) {
                                                skip = true;
                                                break;
                                        }
                                }
                                if (skip) {
                                        System.out.println("[EPKCompiler]   skipping " + rel);
                                        return;
                                }
                                long size;
                                try {
                                        size = Files.size(p);
                                        long crc = crc32(p);
                                        entries.add(new Entry(rel, p, size, crc));
                                } catch (IOException e) {
                                        throw new UncheckedIOException("Cannot read " + p, e);
                                }
                        });
                }
                entries.sort(Comparator.comparing(e -> e.name));
                return entries;
        }

        // ==================== verify ====================

        /**
         * Verifies an EPK file using exactly the same parsing rules as the
         * client's {@code EPKLoader} JSBody parsers (parseEPKHeader0, listFiles,
         * extractFile): fixed 20-byte header, then sequential
         * {@code u16 nameLen + name + u32 dataLen + data} entries.
         *
         * <p>If {@code sourceDir} is provided, every entry is additionally
         * compared against the corresponding source file (round-trip check).</p>
         *
         * @return true if the file is structurally valid (and matches the source
         *         directory when one was given)
         */
        static boolean verify(Path epkFile, Path sourceDir) throws IOException {
                byte[] data = Files.readAllBytes(epkFile);
                System.out.println("[EPKCompiler] verifying " + epkFile.toAbsolutePath()
                                + " (" + data.length + " B)");

                if (data.length < EPK_HEADER_SIZE) {
                        System.err.println("[EPKCompiler] FAIL: file smaller than the 20-byte EPK header");
                        return false;
                }
                for (int i = 0; i < MAGIC_BYTES.length; i++) {
                        if (data[i] != MAGIC_BYTES[i]) {
                                System.err.println("[EPKCompiler] FAIL: bad magic at byte " + i
                                                + " (expected \"" + EPK_MAGIC + "\")");
                                return false;
                        }
                }
                int version = readU16(data, 8);
                int flags = readU16(data, 10);
                long fileCount = readU32(data, 12);
                long reserved = readU32(data, 16);
                System.out.println("[EPKCompiler] magic OK, version=" + version + ", flags=" + flags
                                + ", fileCount=" + fileCount + ", reserved=" + reserved);

                if (version != EPK_VERSION) {
                        System.err.println("[EPKCompiler] FAIL: unsupported version " + version);
                        return false;
                }
                if ((flags & 0x1) != 0) {
                        System.err.println("[EPKCompiler] FAIL: compressed entries (flags bit 0) are not supported by EPKLoader");
                        return false;
                }
                if (reserved != 0) {
                        System.err.println("[EPKCompiler] FAIL: reserved header field must be 0");
                        return false;
                }

                long offset = EPK_HEADER_SIZE;
                List<String> names = new ArrayList<>();
                boolean ok = true;
                for (long idx = 0; idx < fileCount; idx++) {
                        if (offset + 2 > data.length) {
                                System.err.println("[EPKCompiler] FAIL: EOF reading name length of entry " + idx);
                                return false;
                        }
                        int nameLen = readU16(data, (int) offset);
                        offset += 2;
                        if (offset + nameLen > data.length) {
                                System.err.println("[EPKCompiler] FAIL: EOF reading name of entry " + idx);
                                return false;
                        }
                        String name = new String(data, (int) offset, nameLen, StandardCharsets.UTF_8);
                        offset += nameLen;
                        if (offset + 4 > data.length) {
                                System.err.println("[EPKCompiler] FAIL: EOF reading data length of entry " + idx);
                                return false;
                        }
                        long dataLen = readU32(data, (int) offset);
                        offset += 4;
                        if (offset + dataLen > data.length) {
                                System.err.println("[EPKCompiler] FAIL: entry " + name + " overruns EOF ("
                                                + dataLen + " B claimed, " + (data.length - offset) + " B left)");
                                return false;
                        }
                        long crc = crc32(data, (int) offset, (int) dataLen);
                        offset += dataLen;
                        names.add(name);
                        System.out.println("[EPKCompiler]   [" + idx + "] " + name + " (" + dataLen + " B, crc32 " + hex32(crc) + ")");

                        if (sourceDir != null) {
                                Path src = sourceDir.toAbsolutePath().normalize().resolve(name);
                                if (!Files.isRegularFile(src)) {
                                        System.err.println("[EPKCompiler] FAIL: no source file for entry: " + name);
                                        ok = false;
                                } else if (crc32(src) != crc) {
                                        System.err.println("[EPKCompiler] FAIL: CRC mismatch vs source: " + name);
                                        ok = false;
                                } else if (Files.size(src) != dataLen) {
                                        System.err.println("[EPKCompiler] FAIL: size mismatch vs source: " + name);
                                        ok = false;
                                }
                        }
                }

                if (offset != data.length) {
                        System.err.println("[EPKCompiler] FAIL: " + (data.length - offset)
                                        + " trailing bytes after the last entry");
                        return false;
                }
                if (names.size() != fileCount) {
                        System.err.println("[EPKCompiler] FAIL: walked " + names.size() + " entries but header says " + fileCount);
                        return false;
                }

                System.out.println("[EPKCompiler] OK: " + names.size() + " entries, structure valid"
                                + (sourceDir != null && ok ? ", round-trip match" : ""));
                return ok;
        }

        // ==================== primitives ====================

        private static void writeHeader(ByteArrayOutputStream out, int version, int flags, int fileCount) {
                out.write(MAGIC_BYTES, 0, MAGIC_BYTES.length);
                writeU16(out, version);
                writeU16(out, flags);
                writeU32(out, fileCount);
                writeU32(out, 0); // reserved
        }

        private static void writeU16(ByteArrayOutputStream out, int v) {
                out.write(v & 0xFF);
                out.write((v >>> 8) & 0xFF);
        }

        private static void writeU32(ByteArrayOutputStream out, long v) {
                out.write((int) (v & 0xFF));
                out.write((int) ((v >>> 8) & 0xFF));
                out.write((int) ((v >>> 16) & 0xFF));
                out.write((int) ((v >>> 24) & 0xFF));
        }

        private static int readU16(byte[] b, int off) {
                return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
        }

        private static long readU32(byte[] b, int off) {
                return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
        }

        private static long crc32(Path p) throws IOException {
                CRC32 crc = new CRC32();
                byte[] buf = new byte[8192];
                try (var in = Files.newInputStream(p)) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                                crc.update(buf, 0, n);
                        }
                }
                return crc.getValue();
        }

        private static long crc32(byte[] data, int off, int len) {
                CRC32 crc = new CRC32();
                crc.update(data, off, len);
                return crc.getValue();
        }

        private static String hex32(long v) {
                return String.format("%08x", v);
        }
}
