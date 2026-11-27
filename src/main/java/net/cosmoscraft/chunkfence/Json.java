package net.cosmoscraft.chunkfence;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reader/writer. Objects keep key order and numbers keep their original text,
 * so a vanilla template can be read, have three fields changed and be written back unchanged otherwise.
 */
final class Json {

    /** A number kept exactly as written. */
    record Num(String text) {
        double doubleValue() {
            return Double.parseDouble(this.text);
        }

        static Num of(long v) {
            return new Num(Long.toString(v));
        }
    }

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) throws IOException {
        final Json p = new Json(text);
        p.ws();
        final Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IOException("Trailing data at " + p.i);
        }
        return v;
    }

    private void ws() {
        while (this.i < this.s.length()) {
            final char c = this.s.charAt(this.i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                this.i++;
            } else if (c == '/' && this.i + 1 < this.s.length() && this.s.charAt(this.i + 1) == '/') {
                while (this.i < this.s.length() && this.s.charAt(this.i) != '\n') {
                    this.i++; // tolerate // comments (some packs have them)
                }
            } else {
                break;
            }
        }
    }

    private IOException err(String m) {
        return new IOException(m + " at " + this.i);
    }

    private Object value() throws IOException {
        if (this.i >= this.s.length()) {
            throw err("Unexpected end");
        }
        final char c = this.s.charAt(this.i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private void expect(String word) throws IOException {
        if (!this.s.startsWith(word, this.i)) {
            throw err("Expected " + word);
        }
        this.i += word.length();
    }

    private Map<String, Object> object() throws IOException {
        final Map<String, Object> m = new LinkedHashMap<>();
        this.i++;
        ws();
        if (this.s.charAt(this.i) == '}') {
            this.i++;
            return m;
        }
        while (true) {
            ws();
            final String k = string();
            ws();
            if (this.s.charAt(this.i++) != ':') {
                throw err("Expected :");
            }
            ws();
            m.put(k, value());
            ws();
            final char c = this.s.charAt(this.i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw err("Expected , or }");
            }
            ws();
            if (this.s.charAt(this.i) == '}') { // trailing comma
                this.i++;
                return m;
            }
        }
    }

    private List<Object> array() throws IOException {
        final List<Object> l = new ArrayList<>();
        this.i++;
        ws();
        if (this.s.charAt(this.i) == ']') {
            this.i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            final char c = this.s.charAt(this.i++);
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw err("Expected , or ]");
            }
            ws();
            if (this.s.charAt(this.i) == ']') {
                this.i++;
                return l;
            }
        }
    }

    private String string() throws IOException {
        if (this.s.charAt(this.i) != '"') {
            throw err("Expected string");
        }
        this.i++;
        final StringBuilder b = new StringBuilder();
        while (true) {
            final char c = this.s.charAt(this.i++);
            if (c == '"') {
                return b.toString();
            }
            if (c == '\\') {
                final char e = this.s.charAt(this.i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(this.s.substring(this.i, this.i + 4), 16));
                        this.i += 4;
                    }
                    default -> b.append(e);
                }
            } else {
                b.append(c);
            }
        }
    }

    private Num number() throws IOException {
        final int start = this.i;
        while (this.i < this.s.length() && "+-0123456789.eE".indexOf(this.s.charAt(this.i)) >= 0) {
            this.i++;
        }
        if (start == this.i) {
            throw err("Unexpected character '" + this.s.charAt(this.i) + "'");
        }
        return new Num(this.s.substring(start, this.i));
    }

    static String write(Object v) {
        final StringBuilder b = new StringBuilder();
        write(b, v, 0);
        return b.append('\n').toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v, int indent) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append("{\n");
            int n = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                pad(b, indent + 2);
                quote(b, e.getKey());
                b.append(": ");
                write(b, e.getValue(), indent + 2);
                if (++n < m.size()) {
                    b.append(',');
                }
                b.append('\n');
            }
            pad(b, indent);
            b.append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append("[\n");
            for (int k = 0; k < l.size(); k++) {
                pad(b, indent + 2);
                write(b, l.get(k), indent + 2);
                if (k + 1 < l.size()) {
                    b.append(',');
                }
                b.append('\n');
            }
            pad(b, indent);
            b.append(']');
        } else if (v instanceof String str) {
            quote(b, str);
        } else if (v instanceof Num num) {
            b.append(num.text());
        } else {
            b.append(v);
        }
    }

    private static void pad(StringBuilder b, int n) {
        b.append(" ".repeat(n));
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            final char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
