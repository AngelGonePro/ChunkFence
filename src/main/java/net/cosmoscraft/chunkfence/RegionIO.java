package net.cosmoscraft.chunkfence;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Whole-file helpers for Anvil region files, used by height probing and recovery (never by the server). */
final class RegionIO {

    static final int SECTOR = 4096;

    private RegionIO() {
    }

    static int rx(Path regionFile) {
        return Integer.parseInt(regionFile.getFileName().toString().split("\\.")[1]);
    }

    static int rz(Path regionFile) {
        return Integer.parseInt(regionFile.getFileName().toString().split("\\.")[2]);
    }

    static int headerEntry(byte[] file, int index) {
        final int p = index * 4;
        return (file[p] & 0xFF) << 24 | (file[p + 1] & 0xFF) << 16 | (file[p + 2] & 0xFF) << 8 | (file[p + 3] & 0xFF);
    }

    static int timestamp(byte[] file, int index) {
        return headerEntry(file, 1024 + index);
    }

    static InputStream decoder(int type, byte[] data, int off, int len) throws IOException {
        final InputStream raw = new ByteArrayInputStream(data, off, len);
        return switch (type) {
            case 1 -> new GZIPInputStream(raw, 8192);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            case 4 -> new Lz4BlockInputStream(raw);
            default -> throw new IOException("Unsupported compression " + type);
        };
    }

    /** Decodes the chunk referenced by header slot {@code index}, or returns null if the slot is empty. */
    static Nbt.Root readChunk(Path regionFile, byte[] file, int index) throws IOException {
        final int entry = headerEntry(file, index);
        if (entry == 0 || (entry >>> 8) < 2) {
            return null;
        }
        return readChunkAt(regionFile, file, entry >>> 8, index);
    }

    /** Decodes a chunk blob starting at {@code sector}; {@code index} is only needed for external (.mcc) chunks. */
    static Nbt.Root readChunkAt(Path regionFile, byte[] file, int sector, int index) throws IOException {
        final long pos = (long) sector * SECTOR;
        if (pos + 5 > file.length) {
            throw new IOException("Chunk outside file");
        }
        final int p = (int) pos;
        final int length = (file[p] & 0xFF) << 24 | (file[p + 1] & 0xFF) << 16 | (file[p + 2] & 0xFF) << 8 | (file[p + 3] & 0xFF);
        final int type = file[p + 4] & 0xFF;
        if ((type & 0x80) != 0) {
            if (index < 0) {
                throw new IOException("External chunk without slot");
            }
            final int cx = (rx(regionFile) << 5) + (index & 31);
            final int cz = (rz(regionFile) << 5) + (index >>> 5);
            final byte[] ext = Files.readAllBytes(regionFile.resolveSibling("c." + cx + "." + cz + ".mcc"));
            return parse(decoder(type & 0x7F, ext, 0, ext.length));
        }
        if (length <= 1 || pos + 4 + length > file.length) {
            throw new IOException("Bad chunk length " + length);
        }
        return parse(decoder(type, file, p + 5, length - 1));
    }

    private static Nbt.Root parse(InputStream in) throws IOException {
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(in, 16 * 1024))) {
            return Nbt.read(d);
        }
    }

    static byte[] encodeZlib(Nbt.Root root) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, deflater, 16 * 1024))) {
            Nbt.write(out, root);
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    /**
     * Writes a fresh, compact region file. {@code payloads[i]} is the compressed chunk for slot i
     * (null = empty), {@code types[i]} its compression byte, {@code timestamps[i]} the header timestamp.
     * Chunks too big for the 1 MiB in-region limit are written as external .mcc files.
     */
    static void writeRegion(Path out, byte[][] payloads, int[] types, int[] timestamps) throws IOException {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final byte[] header = new byte[2 * SECTOR];
        int sector = 2;
        final int rx = rx(out);
        final int rz = rz(out);
        for (int i = 0; i < 1024; i++) {
            final byte[] payload = payloads[i];
            if (payload == null) {
                continue;
            }
            byte[] blob;
            final int needed = 5 + payload.length;
            final int cx = (rx << 5) + (i & 31);
            final int cz = (rz << 5) + (i >>> 5);
            final Path mcc = out.resolveSibling("c." + cx + "." + cz + ".mcc");
            if ((needed + SECTOR - 1) / SECTOR >= 256) {
                Files.write(mcc, payload);
                blob = new byte[]{0, 0, 0, 1, (byte) (types[i] | 0x80)};
            } else {
                Files.deleteIfExists(mcc);
                blob = new byte[needed];
                final int len = payload.length + 1;
                blob[0] = (byte) (len >>> 24);
                blob[1] = (byte) (len >>> 16);
                blob[2] = (byte) (len >>> 8);
                blob[3] = (byte) len;
                blob[4] = (byte) types[i];
                System.arraycopy(payload, 0, blob, 5, payload.length);
            }
            final int sectors = (blob.length + SECTOR - 1) / SECTOR;
            final int entry = (sector << 8) | sectors;
            header[i * 4] = (byte) (entry >>> 24);
            header[i * 4 + 1] = (byte) (entry >>> 16);
            header[i * 4 + 2] = (byte) (entry >>> 8);
            header[i * 4 + 3] = (byte) entry;
            final int ts = timestamps[i];
            header[SECTOR + i * 4] = (byte) (ts >>> 24);
            header[SECTOR + i * 4 + 1] = (byte) (ts >>> 16);
            header[SECTOR + i * 4 + 2] = (byte) (ts >>> 8);
            header[SECTOR + i * 4 + 3] = (byte) ts;
            body.write(blob);
            body.write(new byte[sectors * SECTOR - blob.length]);
            sector += sectors;
        }
        final Path tmp = out.resolveSibling(out.getFileName() + ".cftmp");
        try (var os = Files.newOutputStream(tmp)) {
            os.write(header);
            body.writeTo(os);
        }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** Range of section Y values that carry block data ("block_states", or "Palette"/"Blocks" in older formats). */
    static int[] blockSectionRange(Nbt.Compound chunk) {
        Nbt.Compound level = chunk.getCompound("Level");
        final Nbt.Compound c = level != null ? level : chunk;
        Nbt.NbtList sections = c.getList("sections");
        if (sections == null) {
            sections = c.getList("Sections");
        }
        if (sections == null) {
            return null;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Nbt.Tag t : sections.items) {
            if (t.type() != Nbt.COMPOUND) {
                continue;
            }
            final Nbt.Compound s = t.compound();
            if (s.get("block_states") == null && s.get("Palette") == null && s.get("Blocks") == null) {
                continue;
            }
            final Integer y = s.getInt("Y");
            if (y == null) {
                continue;
            }
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        return min == Integer.MAX_VALUE ? null : new int[]{min, max};
    }
}
