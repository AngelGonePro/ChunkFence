package net.cosmoscraft.chunkfence;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Minimal reader for the lz4-java "LZ4Block" stream format, which vanilla uses when
 * region-file-compression is set to lz4. Checksums are not verified: the data is only
 * inspected, never written back.
 */
final class Lz4BlockInputStream extends InputStream {

    private static final byte[] MAGIC = {'L', 'Z', '4', 'B', 'l', 'o', 'c', 'k'};
    private static final int METHOD_RAW = 0x10;
    private static final int METHOD_LZ4 = 0x20;
    private static final int MAX_BLOCK = 64 * 1024 * 1024;

    private final InputStream in;
    private byte[] compressed = new byte[0];
    private byte[] buffer = new byte[0];
    private int pos;
    private int limit;
    private boolean finished;

    Lz4BlockInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public int read() throws IOException {
        if (this.pos >= this.limit && !refill()) {
            return -1;
        }
        return this.buffer[this.pos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (this.pos >= this.limit && !refill()) {
            return -1;
        }
        final int n = Math.min(len, this.limit - this.pos);
        System.arraycopy(this.buffer, this.pos, b, off, n);
        this.pos += n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = 0;
        while (skipped < n) {
            if (this.pos >= this.limit && !refill()) {
                break;
            }
            final int step = (int) Math.min(n - skipped, this.limit - this.pos);
            this.pos += step;
            skipped += step;
        }
        return skipped;
    }

    private boolean refill() throws IOException {
        while (!this.finished) {
            final byte[] header = new byte[21];
            if (!readFully(header, true)) {
                this.finished = true;
                return false;
            }
            for (int i = 0; i < 8; i++) {
                if (header[i] != MAGIC[i]) {
                    throw new IOException("Bad LZ4Block magic");
                }
            }
            final int method = header[8] & 0xF0;
            final int compressedLen = le(header, 9);
            final int originalLen = le(header, 13);
            if (originalLen == 0 && compressedLen == 0) {
                this.finished = true;
                return false;
            }
            if (compressedLen < 0 || originalLen < 0 || compressedLen > MAX_BLOCK || originalLen > MAX_BLOCK) {
                throw new IOException("Bad LZ4Block lengths");
            }
            if (this.buffer.length < originalLen) {
                this.buffer = new byte[originalLen];
            }
            if (method == METHOD_RAW) {
                if (compressedLen != originalLen) {
                    throw new IOException("Bad raw LZ4Block");
                }
                readFully(this.buffer, originalLen);
            } else if (method == METHOD_LZ4) {
                if (this.compressed.length < compressedLen) {
                    this.compressed = new byte[compressedLen];
                }
                readFully(this.compressed, compressedLen);
                decompress(this.compressed, compressedLen, this.buffer, originalLen);
            } else {
                throw new IOException("Unknown LZ4Block method " + method);
            }
            this.pos = 0;
            this.limit = originalLen;
            if (originalLen > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean readFully(byte[] b, boolean eofAllowed) throws IOException {
        int off = 0;
        while (off < b.length) {
            final int n = this.in.read(b, off, b.length - off);
            if (n < 0) {
                if (eofAllowed && off == 0) {
                    return false;
                }
                throw new EOFException();
            }
            off += n;
        }
        return true;
    }

    private void readFully(byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            final int n = this.in.read(b, off, len - off);
            if (n < 0) {
                throw new EOFException();
            }
            off += n;
        }
    }

    private static int le(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    /** Decodes one raw LZ4 block. */
    static void decompress(byte[] src, int srcLen, byte[] dst, int dstLen) throws IOException {
        int s = 0;
        int d = 0;
        while (s < srcLen) {
            final int token = src[s++] & 0xFF;
            int literals = token >>> 4;
            if (literals == 15) {
                int b;
                do {
                    if (s >= srcLen) {
                        throw new IOException("LZ4 literal length overrun");
                    }
                    b = src[s++] & 0xFF;
                    literals += b;
                } while (b == 255);
            }
            if (s + literals > srcLen || d + literals > dstLen) {
                throw new IOException("LZ4 literal overrun");
            }
            System.arraycopy(src, s, dst, d, literals);
            s += literals;
            d += literals;
            if (s >= srcLen) {
                break; // last sequence has literals only
            }
            if (s + 2 > srcLen) {
                throw new IOException("LZ4 offset overrun");
            }
            final int offset = (src[s] & 0xFF) | (src[s + 1] & 0xFF) << 8;
            s += 2;
            if (offset == 0 || offset > d) {
                throw new IOException("LZ4 bad offset");
            }
            int matchLen = token & 0x0F;
            if (matchLen == 15) {
                int b;
                do {
                    if (s >= srcLen) {
                        throw new IOException("LZ4 match length overrun");
                    }
                    b = src[s++] & 0xFF;
                    matchLen += b;
                } while (b == 255);
            }
            matchLen += 4;
            if (d + matchLen > dstLen) {
                throw new IOException("LZ4 match overrun");
            }
            int from = d - offset;
            if (offset >= matchLen) {
                System.arraycopy(dst, from, dst, d, matchLen);
                d += matchLen;
            } else {
                for (int i = 0; i < matchLen; i++) {
                    dst[d++] = dst[from++];
                }
            }
        }
        if (d != dstLen) {
            throw new IOException("LZ4 size mismatch " + d + " != " + dstLen);
        }
    }
}
