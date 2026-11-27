package org.bukkit.configuration.file;
import java.util.*;
/** Tiny YAML subset reader good enough for ChunkFence's config.yml. */
public class FileConfiguration {
  public final Map<String,Object> values = new HashMap<>();
  public void load(java.nio.file.Path p) throws java.io.IOException {
    values.clear(); Deque<String[]> stack = new ArrayDeque<>(); // {indent, key}
    String listKey = null;
    for (String raw : java.nio.file.Files.readAllLines(p)) {
      String line = raw.replaceAll("\\s+#.*$", ""); if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
      int ind = line.length() - line.stripLeading().length(); String t = line.trim();
      if (t.startsWith("- ") && listKey != null) { ((List<String>)values.get(listKey)).add(unq(t.substring(2))); continue; }
      while (!stack.isEmpty() && Integer.parseInt(stack.peek()[0]) >= ind) stack.pop();
      int c = t.indexOf(':'); String k = t.substring(0,c).trim(); String v = t.substring(c+1).trim();
      StringBuilder full = new StringBuilder(); List<String[]> l = new ArrayList<>(stack); Collections.reverse(l); for (String[] s : l) full.append(s[1]).append('.'); full.append(k);
      if (v.isEmpty()) { stack.push(new String[]{""+ind,k}); values.put(full.toString(), new ArrayList<String>()); listKey = full.toString(); continue; }
      listKey = null;
      if (v.startsWith("[")) { List<String> li = new ArrayList<>(); String in = v.substring(1, v.length()-1).trim(); if(!in.isEmpty()) for (String s : in.split(",")) li.add(unq(s.trim())); values.put(full.toString(), li); }
      else values.put(full.toString(), unq(v));
    }
  }
  static String unq(String s){ if ((s.startsWith("\"")&&s.endsWith("\""))||(s.startsWith("'")&&s.endsWith("'"))) return s.substring(1,s.length()-1); return s; }
  public void set(String k, Object v){ String[] ps = k.split("\\."); String pre = ""; for (int i = 0; i < ps.length - 1; i++) { pre = pre.isEmpty() ? ps[i] : pre + "." + ps[i]; values.remove(pre); } values.put(k, v instanceof Number ? String.valueOf(v) : v); }
  /** Writes the values back as nested YAML (comments are not kept by this test double). */
  public void save(java.nio.file.Path p) throws java.io.IOException {
    java.util.TreeMap<String,Object> sorted = new java.util.TreeMap<>(values);
    StringBuilder b = new StringBuilder(); String[] prev = new String[0];
    for (var e : sorted.entrySet()) {
      String[] parts = e.getKey().split("\\.");
      int common = 0; while (common < prev.length && common < parts.length - 1 && prev[common].equals(parts[common])) common++;
      for (int i = common; i < parts.length - 1; i++) b.append("  ".repeat(i)).append(parts[i]).append(":\n");
      b.append("  ".repeat(parts.length - 1)).append(parts[parts.length - 1]).append(": ");
      Object v = e.getValue();
      if (v instanceof java.util.List<?> l) b.append(l.isEmpty() ? "[]" : "[" + String.join(", ", l.stream().map(Object::toString).toList()) + "]");
      else b.append('"').append(v).append('"');
      b.append("\n"); prev = parts;
    }
    java.nio.file.Files.writeString(p, b.toString());
  }
  public String getString(String p, String d) { Object o = values.get(p); return o instanceof String s ? s : d; }
  @SuppressWarnings("unchecked") public List<String> getStringList(String p) { Object o = values.get(p); return o instanceof List ? (List<String>)o : new ArrayList<>(); }
  public boolean getBoolean(String p, boolean d) { Object o = values.get(p); return o instanceof String s ? Boolean.parseBoolean(s) : d; }
  public int getInt(String p, int d) { Object o = values.get(p); try { return o instanceof String s ? Integer.parseInt(s) : d; } catch(NumberFormatException e){ return d; } }
}
