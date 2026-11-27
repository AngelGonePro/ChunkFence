package net.cosmoscraft.chunkfence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal lossless NBT tree reader/writer (used by height probing and chunk recovery). */
final class Nbt {

    static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7,
        STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    /** A tag: type plus value (Byte, Short, Integer, Long, Float, Double, byte[], String, NbtList, Compound, int[], long[]). */
    record Tag(byte type, Object value) {
        Compound compound() {
            return (Compound) this.value;
        }

        NbtList list() {
            return (NbtList) this.value;
        }

        int intValue() {
            return ((Number) this.value).intValue();
        }

        long longValue() {
            return ((Number) this.value).longValue();
        }
    }

    static final class NbtList {
        byte elementType;
        final List<Tag> items = new ArrayList<>();

        NbtList(byte elementType) {
            this.elementType = elementType;
        }
    }

    static final class Compound {
        final LinkedHashMap<String, Tag> map = new LinkedHashMap<>();

        Tag get(String key) {
            return this.map.get(key);
        }

        Compound getCompound(String key) {
            final Tag t = this.map.get(key);
            return t != null && t.type == COMPOUND ? t.compound() : null;
        }

        NbtList getList(String key) {
            final Tag t = this.map.get(key);
            return t != null && t.type == LIST ? t.list() : null;
        }

        String getString(String key) {
            final Tag t = this.map.get(key);
            return t != null && t.type == STRING ? (String) t.value : null;
        }

        Integer getInt(String key) {
            final Tag t = this.map.get(key);
            return t != null && t.value instanceof Number n ? n.intValue() : null;
        }

        Long getLong(String key) {
            final Tag t = this.map.get(key);
            return t != null && t.value instanceof Number n ? n.longValue() : null;
        }

        void put(String key, Tag tag) {
            this.map.put(key, tag);
        }
    }

    /** Root compound with its name. */
    record Root(String name, Compound compound) {
    }

    private Nbt() {
    }

    static Root read(DataInputStream in) throws IOException {
        final byte type = in.readByte();
        if (type != COMPOUND) {
            throw new IOException("Root tag is not a compound (" + type + ")");
        }
        final String name = in.readUTF();
        return new Root(name, readCompound(in, 0));
    }

    static void write(DataOutputStream out, Root root) throws IOException {
        out.writeByte(COMPOUND);
        out.writeUTF(root.name());
        writeCompound(out, root.compound());
    }

    private static Compound readCompound(DataInputStream in, int depth) throws IOException {
        if (depth > 512) {
            throw new IOException("NBT too deep");
        }
        final Compound c = new Compound();
        while (true) {
            final byte type = in.readByte();
            if (type == END) {
                return c;
            }
            final String name = in.readUTF();
            c.map.put(name, new Tag(type, readPayload(in, type, depth + 1)));
        }
    }

    private static int len(DataInputStream in) throws IOException {
        final int n = in.readInt();
        if (n < 0 || n > 64 * 1024 * 1024) {
            throw new IOException("Bad NBT length " + n);
        }
        return n;
    }

    private static Object readPayload(DataInputStream in, byte type, int depth) throws IOException {
        switch (type) {
            case BYTE:
                return in.readByte();
            case SHORT:
                return in.readShort();
            case INT:
                return in.readInt();
            case LONG:
                return in.readLong();
            case FLOAT:
                return in.readFloat();
            case DOUBLE:
                return in.readDouble();
            case BYTE_ARRAY: {
                final byte[] b = new byte[len(in)];
                in.readFully(b);
                return b;
            }
            case STRING:
                return in.readUTF();
            case LIST: {
                final byte et = in.readByte();
                final int n = len(in);
                final NbtList l = new NbtList(et);
                for (int i = 0; i < n; i++) {
                    l.items.add(new Tag(et, readPayload(in, et, depth + 1)));
                }
                return l;
            }
            case COMPOUND:
                return readCompound(in, depth);
            case INT_ARRAY: {
                final int[] a = new int[len(in)];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readInt();
                }
                return a;
            }
            case LONG_ARRAY: {
                final long[] a = new long[len(in)];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readLong();
                }
                return a;
            }
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static void writeCompound(DataOutputStream out, Compound c) throws IOException {
        for (Map.Entry<String, Tag> e : c.map.entrySet()) {
            out.writeByte(e.getValue().type());
            out.writeUTF(e.getKey());
            writePayload(out, e.getValue());
        }
        out.writeByte(END);
    }

    private static void writePayload(DataOutputStream out, Tag t) throws IOException {
        switch (t.type()) {
            case BYTE -> out.writeByte((Byte) t.value());
            case SHORT -> out.writeShort((Short) t.value());
            case INT -> out.writeInt((Integer) t.value());
            case LONG -> out.writeLong((Long) t.value());
            case FLOAT -> out.writeFloat((Float) t.value());
            case DOUBLE -> out.writeDouble((Double) t.value());
            case BYTE_ARRAY -> {
                final byte[] b = (byte[]) t.value();
                out.writeInt(b.length);
                out.write(b);
            }
            case STRING -> out.writeUTF((String) t.value());
            case LIST -> {
                final NbtList l = t.list();
                out.writeByte(l.items.isEmpty() && l.elementType == END ? END : l.elementType);
                out.writeInt(l.items.size());
                for (Tag item : l.items) {
                    writePayload(out, item);
                }
            }
            case COMPOUND -> writeCompound(out, t.compound());
            case INT_ARRAY -> {
                final int[] a = (int[]) t.value();
                out.writeInt(a.length);
                for (int v : a) {
                    out.writeInt(v);
                }
            }
            case LONG_ARRAY -> {
                final long[] a = (long[]) t.value();
                out.writeInt(a.length);
                for (long v : a) {
                    out.writeLong(v);
                }
            }
            default -> throw new IOException("Unknown NBT tag type " + t.type());
        }
    }
}
