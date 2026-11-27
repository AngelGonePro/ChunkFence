package net.cosmoscraft.chunkfence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Compact set of chunk coordinates.
 * <p>
 * Chunks are grouped by region (32x32 chunks). Each region is a 1024-bit bitmap (16 longs),
 * stored in an open-addressing hash table keyed by region coordinates. A lookup is one hash
 * probe plus one bit test, with no allocation and no boxing, so it is cheap enough to run on
 * every chunk-boundary crossing.
 */
public final class ChunkSet {

    private static final int FILE_MAGIC = 0x43465331; // "CFS1"

    private long[] keys;
    private long[][] bits;
    private int mask;
    private int regionCount;
    private long chunkCount;

    public ChunkSet() {
        this(64);
    }

    public ChunkSet(int expectedRegions) {
        int cap = 16;
        while (cap < expectedRegions * 2) {
            cap <<= 1;
        }
        this.keys = new long[cap];
        this.bits = new long[cap][];
        this.mask = cap - 1;
    }

    static long regionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    private static int slot(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }

    public boolean contains(int chunkX, int chunkZ) {
        final long key = regionKey(chunkX >> 5, chunkZ >> 5);
        final long[] k = this.keys;
        final long[][] b = this.bits;
        final int m = this.mask;
        int i = slot(key, m);
        while (true) {
            final long[] bitmap = b[i];
            if (bitmap == null) {
                return false;
            }
            if (k[i] == key) {
                final int idx = ((chunkZ & 31) << 5) | (chunkX & 31);
                return (bitmap[idx >>> 6] & (1L << idx)) != 0;
            }
            i = (i + 1) & m;
        }
    }

    public void add(int chunkX, int chunkZ) {
        final long[] bitmap = regionBitmap(chunkX >> 5, chunkZ >> 5);
        final int idx = ((chunkZ & 31) << 5) | (chunkX & 31);
        final long bit = 1L << idx;
        if ((bitmap[idx >>> 6] & bit) == 0) {
            bitmap[idx >>> 6] |= bit;
            this.chunkCount++;
        }
    }

    /** Merges a full region bitmap (16 longs, bit index = localZ * 32 + localX). */
    public void addRegion(int rx, int rz, long[] regionBits) {
        final long[] bitmap = regionBitmap(rx, rz);
        for (int w = 0; w < 16; w++) {
            final long added = regionBits[w] & ~bitmap[w];
            if (added != 0) {
                bitmap[w] |= added;
                this.chunkCount += Long.bitCount(added);
            }
        }
    }

    private long[] regionBitmap(int rx, int rz) {
        final long key = regionKey(rx, rz);
        int i = slot(key, this.mask);
        while (true) {
            final long[] bitmap = this.bits[i];
            if (bitmap == null) {
                break;
            }
            if (this.keys[i] == key) {
                return bitmap;
            }
            i = (i + 1) & this.mask;
        }
        if ((this.regionCount + 1) * 2 > this.keys.length) {
            grow();
            return regionBitmap(rx, rz);
        }
        final long[] created = new long[16];
        this.keys[i] = key;
        this.bits[i] = created;
        this.regionCount++;
        return created;
    }

    private void grow() {
        final long[] oldKeys = this.keys;
        final long[][] oldBits = this.bits;
        final int cap = oldKeys.length << 1;
        this.keys = new long[cap];
        this.bits = new long[cap][];
        this.mask = cap - 1;
        for (int j = 0; j < oldKeys.length; j++) {
            if (oldBits[j] != null) {
                int i = slot(oldKeys[j], this.mask);
                while (this.bits[i] != null) {
                    i = (i + 1) & this.mask;
                }
                this.keys[i] = oldKeys[j];
                this.bits[i] = oldBits[j];
            }
        }
    }

    public long chunkCount() {
        return this.chunkCount;
    }

    public int regionCount() {
        return this.regionCount;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(FILE_MAGIC);
        out.writeLong(this.chunkCount);
        out.writeInt(this.regionCount);
        for (int i = 0; i < this.keys.length; i++) {
            final long[] bitmap = this.bits[i];
            if (bitmap != null) {
                out.writeLong(this.keys[i]);
                for (int w = 0; w < 16; w++) {
                    out.writeLong(bitmap[w]);
                }
            }
        }
    }

    public static ChunkSet read(DataInputStream in) throws IOException {
        if (in.readInt() != FILE_MAGIC) {
            throw new IOException("Not a ChunkFence snapshot");
        }
        final long expectedChunks = in.readLong();
        final int regions = in.readInt();
        if (regions < 0) {
            throw new IOException("Bad region count " + regions);
        }
        final ChunkSet set = new ChunkSet(regions);
        final long[] tmp = new long[16];
        for (int r = 0; r < regions; r++) {
            final long key = in.readLong();
            for (int w = 0; w < 16; w++) {
                tmp[w] = in.readLong();
            }
            set.addRegion((int) (key >> 32), (int) key, tmp);
        }
        if (set.chunkCount != expectedChunks) {
            throw new IOException("Snapshot chunk count mismatch: " + set.chunkCount + " != " + expectedChunks);
        }
        return set;
    }
}
