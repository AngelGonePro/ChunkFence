package net.cosmoscraft.chunkfence;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Streaming NBT reader that pulls only what ChunkFence needs out of a chunk:
 * the generation status and whether any section holds a block other than air.
 * Everything else (heightmaps, block data arrays, entities, ...) is skipped without
 * being materialised. Handles 1.18+ chunks (root-level "Status"/"sections") and the
 * older "Level"-wrapped formats.
 */
final class ChunkInspector {

    private static final int TAG_END = 0, TAG_BYTE = 1, TAG_SHORT = 2, TAG_INT = 3, TAG_LONG = 4,
        TAG_FLOAT = 5, TAG_DOUBLE = 6, TAG_BYTE_ARRAY = 7, TAG_STRING = 8, TAG_LIST = 9,
        TAG_COMPOUND = 10, TAG_INT_ARRAY = 11, TAG_LONG_ARRAY = 12;

    String status;
    int terrainPopulated = -1;
    boolean nonAir;

    private final boolean wantNonAir;
    private final byte[] skipBuf = new byte[8192];

    private ChunkInspector(boolean wantNonAir) {
        this.wantNonAir = wantNonAir;
    }

    static ChunkInspector inspect(DataInputStream in, boolean wantNonAir) throws IOException {
        final ChunkInspector r = new ChunkInspector(wantNonAir);
        final int rootType = in.readUnsignedByte();
        if (rootType != TAG_COMPOUND) {
            throw new IOException("Chunk root is not a compound (" + rootType + ")");
        }
        r.skipString(in); // root name
        r.readChunkCompound(in);
        return r;
    }

    private boolean done() {
        return this.status != null && (!this.wantNonAir || this.nonAir);
    }

    /** Returns true if parsing stopped early because everything needed was found. */
    private boolean readChunkCompound(DataInputStream in) throws IOException {
        while (true) {
            final int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return false;
            }
            final String name = in.readUTF();
            switch (name) {
                case "Status" -> {
                    if (type == TAG_STRING) {
                        this.status = in.readUTF();
                    } else {
                        skipPayload(in, type);
                    }
                }
                case "TerrainPopulated" -> {
                    if (type == TAG_BYTE) {
                        this.terrainPopulated = in.readByte();
                    } else {
                        skipPayload(in, type);
                    }
                }
                case "Level" -> {
                    if (type == TAG_COMPOUND) {
                        if (readChunkCompound(in)) {
                            return true;
                        }
                    } else {
                        skipPayload(in, type);
                    }
                }
                case "sections", "Sections" -> {
                    if (type == TAG_LIST && this.wantNonAir && !this.nonAir) {
                        readSections(in);
                    } else {
                        skipPayload(in, type);
                    }
                }
                default -> skipPayload(in, type);
            }
            if (done()) {
                return true;
            }
        }
    }

    private void readSections(DataInputStream in) throws IOException {
        final int elemType = in.readUnsignedByte();
        final int len = in.readInt();
        if (elemType != TAG_COMPOUND) {
            for (int i = 0; i < len; i++) {
                skipPayload(in, elemType);
            }
            return;
        }
        for (int i = 0; i < len; i++) {
            readSection(in);
        }
    }

    private void readSection(DataInputStream in) throws IOException {
        while (true) {
            final int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return;
            }
            final String name = in.readUTF();
            if (this.nonAir) {
                skipPayload(in, type);
                continue;
            }
            if (type == TAG_COMPOUND && name.equals("block_states")) {
                readBlockStates(in);
            } else if (type == TAG_LIST && name.equals("Palette")) {
                readPalette(in); // 1.13 - 1.17
            } else if (type == TAG_BYTE_ARRAY && name.equals("Blocks")) {
                // pre-1.13: raw block ids, 0 = air
                final int n = in.readInt();
                int left = n;
                while (left > 0) {
                    final int step = Math.min(left, this.skipBuf.length);
                    in.readFully(this.skipBuf, 0, step);
                    if (!this.nonAir) {
                        for (int k = 0; k < step; k++) {
                            if (this.skipBuf[k] != 0) {
                                this.nonAir = true;
                                break;
                            }
                        }
                    }
                    left -= step;
                }
            } else {
                skipPayload(in, type);
            }
        }
    }

    private void readBlockStates(DataInputStream in) throws IOException {
        while (true) {
            final int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return;
            }
            final String name = in.readUTF();
            if (type == TAG_LIST && name.equals("palette") && !this.nonAir) {
                readPalette(in);
            } else {
                skipPayload(in, type);
            }
        }
    }

    private void readPalette(DataInputStream in) throws IOException {
        final int elemType = in.readUnsignedByte();
        final int len = in.readInt();
        for (int i = 0; i < len; i++) {
            if (elemType != TAG_COMPOUND) {
                skipPayload(in, elemType);
                continue;
            }
            // block state compound: Name + optional Properties
            while (true) {
                final int type = in.readUnsignedByte();
                if (type == TAG_END) {
                    break;
                }
                final String name = in.readUTF();
                if (type == TAG_STRING && name.equals("Name")) {
                    if (!isAir(in.readUTF())) {
                        this.nonAir = true;
                    }
                } else {
                    skipPayload(in, type);
                }
            }
        }
    }

    static boolean isAir(String id) {
        return switch (id) {
            case "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "air", "cave_air", "void_air" -> true;
            default -> false;
        };
    }

    private void skipString(DataInputStream in) throws IOException {
        skipBytes(in, in.readUnsignedShort());
    }

    private void skipBytes(DataInputStream in, long n) throws IOException {
        while (n > 0) {
            final int step = (int) Math.min(n, this.skipBuf.length);
            in.readFully(this.skipBuf, 0, step);
            n -= step;
        }
    }

    private void skipPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case TAG_BYTE -> skipBytes(in, 1);
            case TAG_SHORT -> skipBytes(in, 2);
            case TAG_INT, TAG_FLOAT -> skipBytes(in, 4);
            case TAG_LONG, TAG_DOUBLE -> skipBytes(in, 8);
            case TAG_BYTE_ARRAY -> skipBytes(in, checkedLen(in.readInt()));
            case TAG_STRING -> skipString(in);
            case TAG_LIST -> {
                final int elemType = in.readUnsignedByte();
                final int len = checkedLen(in.readInt());
                final int fixed = fixedSize(elemType);
                if (fixed >= 0) {
                    skipBytes(in, (long) fixed * len);
                } else {
                    for (int i = 0; i < len; i++) {
                        skipPayload(in, elemType);
                    }
                }
            }
            case TAG_COMPOUND -> {
                while (true) {
                    final int t = in.readUnsignedByte();
                    if (t == TAG_END) {
                        break;
                    }
                    skipString(in);
                    skipPayload(in, t);
                }
            }
            case TAG_INT_ARRAY -> skipBytes(in, 4L * checkedLen(in.readInt()));
            case TAG_LONG_ARRAY -> skipBytes(in, 8L * checkedLen(in.readInt()));
            case TAG_END -> { }
            default -> throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static int checkedLen(int len) throws IOException {
        if (len < 0) {
            throw new IOException("Negative NBT length");
        }
        return len;
    }

    private static int fixedSize(int type) {
        return switch (type) {
            case TAG_END -> 0;
            case TAG_BYTE -> 1;
            case TAG_SHORT -> 2;
            case TAG_INT, TAG_FLOAT -> 4;
            case TAG_LONG, TAG_DOUBLE -> 8;
            default -> -1;
        };
    }
}
