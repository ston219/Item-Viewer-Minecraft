import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Reads visual assets only. Never extracts or executes package code. */
final class ModTextureImporter {
    static final int MAX_ARCHIVE = 128 * 1024 * 1024, MAX_FILE = 8 * 1024 * 1024;
    static final int MAX_ENTRIES = 25000, MAX_ITEMS = 2048, MAX_DEPTH = 4;
    static final long MAX_EXPANDED = 128L * 1024 * 1024, MAX_PIXELS = 16_777_216;
    record Notice(String key, String detail) {}
    record Report(List<BedrockItemViewer.Item> items, List<Notice> notices, int archives,List<RecipeBook.Recipe> recipes) {
        Report(List<BedrockItemViewer.Item> items,List<Notice> notices,int archives){this(items,notices,archives,List.of());}
    }
    record Entry(String path, byte[] data) {}
    static final class ImportFailure extends IOException {
        final String key;
        ImportFailure(String key) { super(key); this.key = key; }
    }
    static final class Budget {
        int entries, archives;
        long expanded;
        void add(int bytes) throws IOException {
            expanded += bytes;
            if (expanded > MAX_EXPANDED) throw new ImportFailure("error.expanded");
        }
    }
    final Budget budget = new Budget();
    final Map<String, byte[]> images = new LinkedHashMap<>();
    final List<Entry> atlases = new ArrayList<>(), definitions = new ArrayList<>(), recipeFiles=new ArrayList<>();
    final List<Notice> notices = new ArrayList<>();

    static Report load(Path file, boolean allTextures) throws IOException {
        ModTextureImporter reader = new ModTextureImporter();
        if (Files.size(file) > MAX_ARCHIVE) throw new ImportFailure("error.archiveSize");
        if (file.toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
            byte[] data;
            try (InputStream in = Files.newInputStream(file)) { data = limited(in, MAX_FILE, null); }
            BufferedImage image = decode(data);
            String name = file.getFileName().toString();
            return new Report(List.of(new BedrockItemViewer.Item(name, BedrockItemViewer.pretty(name), "Import", name, image)), List.of(), 0);
        }
        try (InputStream in = Files.newInputStream(file)) { reader.archive(in, "", 0); }
        return reader.finish(allTextures);
    }

    static byte[] limited(InputStream in, int limit, Budget budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (budget != null) budget.add(n);
            if ((long) out.size() + n > limit) throw new ImportFailure("error.entrySize");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    static BufferedImage decode(byte[] data) throws IOException {
        try { return BedrockItemViewer.decode(data); }
        catch (IOException | RuntimeException e) { throw new ImportFailure("error.image"); }
    }
    static boolean nested(String name) {
        return name.endsWith(".mcpack") || name.endsWith(".mcaddon") || name.endsWith(".zip") || name.endsWith(".jar");
    }
    void archive(InputStream input, String prefix, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new ImportFailure("error.depth");
        PushbackInputStream checked = new PushbackInputStream(input, 4);
        byte[] signature = checked.readNBytes(4);
        if (signature.length != 4 || signature[0] != 'P' || signature[1] != 'K' ||
            !((signature[2] == 3 && signature[3] == 4) || (signature[2] == 5 && signature[3] == 6)))
            throw new ImportFailure("error.archive");
        checked.unread(signature);
        budget.archives++;
        try (ZipInputStream zip = new ZipInputStream(checked)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++budget.entries > MAX_ENTRIES) throw new ImportFailure("error.entries");
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.indexOf('\0') >= 0 || Arrays.asList(name.split("/")).contains("..")) {
                    throw new ImportFailure("error.path");
                }
                while (name.startsWith("./")) name = name.substring(2);
                String lower = name.toLowerCase(Locale.ROOT), full = prefix + name;
                boolean image = lower.endsWith(".png");
                boolean atlas = lower.equals("textures/item_texture.json") || lower.endsWith("/textures/item_texture.json");
                boolean definition = lower.endsWith(".json") && (lower.startsWith("items/") || lower.contains("/items/"));
                boolean recipe=lower.endsWith(".json")&&(lower.startsWith("recipes/")||lower.contains("/recipes/")||lower.matches("(?:.*/)?data/[^/]+/recipe/.+\\.json"));
                if (nested(lower)) {
                    byte[] bytes = limited(zip, MAX_ARCHIVE, budget);
                    archive(new ByteArrayInputStream(bytes), full + "!/", depth + 1);
                } else if (image || atlas || definition || recipe) {
                    byte[] bytes = limited(zip, MAX_FILE, budget);
                    if (image) {
                        if (images.putIfAbsent(full, bytes) != null) notices.add(new Notice("notice.duplicate", full));
                    } else if (atlas) atlases.add(new Entry(full, bytes));
                    else if(recipe)recipeFiles.add(new Entry(full,bytes));
                    else definitions.add(new Entry(full, bytes));
                } else {
                    byte[] buffer = new byte[16384]; int n;
                    while ((n = zip.read(buffer)) != -1) budget.add(n);
                }
            }
        } catch (ZipException e) { throw new ImportFailure("error.archive"); }
    }

    static Map<?, ?> object(Object value) { return value instanceof Map<?, ?> m ? m : Map.of(); }
    static String string(Object value) { return value instanceof String s ? s : ""; }
    static void texturePaths(Object value, List<String> result) {
        if (value instanceof String s) result.add(s);
        else if (value instanceof List<?> a) a.forEach(v -> texturePaths(v, result));
        else if (value instanceof Map<?, ?> m) {
            if (m.containsKey("path")) texturePaths(m.get("path"), result);
            else if (m.containsKey("textures")) texturePaths(m.get("textures"), result);
        }
    }
    Report finish(boolean allTextures) throws IOException {
        Map<String, Set<String>> identifiers = new HashMap<>();
        for (Entry entry : definitions) {
            try {
                Map<?, ?> item = object(object(Json.parse(entry.data())).get("minecraft:item"));
                String id = string(object(item.get("description")).get("identifier"));
                Object icon = object(item.get("components")).get("minecraft:icon");
                if (icon instanceof Map<?, ?> m) {
                    icon = m.containsKey("texture") ? m.get("texture") : object(m.get("textures")).get("default");
                }
                String alias = string(icon);
                if (!id.isEmpty() && !alias.isEmpty()) identifiers.computeIfAbsent(alias, k -> new LinkedHashSet<>()).add(id);
            } catch (IOException e) { notices.add(new Notice("notice.json", entry.path())); }
        }
        Map<String, Set<String>> aliases = new LinkedHashMap<>();
        for (Entry entry : atlases) {
            try {
                Map<?, ?> atlas = object(object(Json.parse(entry.data())).get("texture_data"));
                String prefix = entry.path().substring(0, entry.path().length() - "textures/item_texture.json".length());
                for (var mapping : atlas.entrySet()) {
                    String alias = String.valueOf(mapping.getKey());
                    List<String> paths = new ArrayList<>();
                    texturePaths(object(mapping.getValue()).get("textures"), paths);
                    for (String path : paths) {
                        path = path.replace('\\', '/');
                        while (path.startsWith("./")) path = path.substring(2);
                        String resolved = prefix + path + (path.toLowerCase(Locale.ROOT).endsWith(".png") ? "" : ".png");
                        if (!images.containsKey(resolved)) { notices.add(new Notice("notice.missing", resolved)); continue; }
                        Set<String> ids = identifiers.get(alias);
                        // A short texture key is not necessarily a game item identifier.
                        String label = ids != null && ids.size() == 1 ? ids.iterator().next() : alias;
                        aliases.computeIfAbsent(resolved, k -> new LinkedHashSet<>()).add(label);
                    }
                }
            } catch (IOException e) { notices.add(new Notice("notice.json", entry.path())); }
        }
        List<BedrockItemViewer.Item> result = new ArrayList<>();
        long pixels = 0;
        for (var entry : images.entrySet()) {
            String path = entry.getKey(), lower = path.toLowerCase(Locale.ROOT);
            boolean bedrock = lower.startsWith("textures/items/") || lower.contains("/textures/items/");
            boolean javaItem = lower.matches("(?:.*/)?assets/[^/]+/textures/items?/.+\\.png");
            boolean auxiliary = lower.matches(".*_(normal|mer|mers|height)\\.png$");
            if (!allTextures && (!(bedrock || javaItem || aliases.containsKey(path)) || auxiliary)) continue;
            BufferedImage image;
            try { image = decode(entry.getValue()); }
            catch (IOException e) { notices.add(new Notice("notice.image", path)); continue; }
            if (result.size() >= MAX_ITEMS) throw new ImportFailure("error.items");
            pixels += (long) image.getWidth() * image.getHeight();
            if (pixels > MAX_PIXELS) throw new ImportFailure("error.pixels");
            String id = aliases.containsKey(path) ? String.join(" | ", aliases.get(path)) : path;
            if (javaItem && !aliases.containsKey(path)) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|/)assets/([^/]+)/textures/items?/(.+)\\.png$").matcher(path);
                if (m.find()) id = m.group(1) + ":" + m.group(2);
            }
            result.add(new BedrockItemViewer.Item(id, BedrockItemViewer.pretty(path), "Import", path, image));
        }
        result.sort(Comparator.comparing(BedrockItemViewer.Item::path));
        if (result.isEmpty()) throw new ImportFailure(allTextures ? "error.noPng" : "error.noItems");
        List<RecipeBook.Recipe> recipes=new ArrayList<>();
        for(Entry entry:recipeFiles){
            try{
                List<RecipeBook.Recipe> parsed=RecipeBook.parse(entry.data(),"import:"+entry.path());
                if(parsed.isEmpty())notices.add(new Notice("notice.recipe",entry.path()));else recipes.addAll(parsed);
            }catch(IOException|IllegalArgumentException e){notices.add(new Notice("notice.recipe",entry.path()));}
        }
        return new Report(List.copyOf(result), List.copyOf(notices), budget.archives,List.copyOf(recipes));
    }

    /** Small bounded JSON/JSONC reader: BOM, comments and trailing commas supported. */
    static final class Json {
        final String text; int pos;
        Json(String text) { this.text = text; }
        static Object parse(byte[] bytes) throws IOException {
            Json parser = new Json(new String(bytes, StandardCharsets.UTF_8));
            Object value = parser.value(0); parser.space();
            if (parser.pos != parser.text.length()) throw new IOException("JSON trailing data");
            return value;
        }
        void space() throws IOException {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isWhitespace(c) || c == '\uFEFF') { pos++; continue; }
                if (text.startsWith("//", pos)) { while (pos < text.length() && text.charAt(pos) != '\n') pos++; continue; }
                if (text.startsWith("/*", pos)) {
                    int end = text.indexOf("*/", pos + 2); if (end < 0) throw new IOException("JSON comment"); pos = end + 2; continue;
                }
                break;
            }
        }
        boolean eat(char c) throws IOException { space(); if (pos < text.length() && text.charAt(pos) == c) { pos++; return true; } return false; }
        void need(char c) throws IOException { if (!eat(c)) throw new IOException("JSON expected " + c); }
        Object value(int depth) throws IOException {
            if (depth > 64) throw new IOException("JSON depth");
            space(); if (pos >= text.length()) throw new IOException("JSON end");
            char c = text.charAt(pos);
            if (c == '"') return quoted();
            if (eat('{')) {
                Map<String, Object> map = new LinkedHashMap<>();
                if (eat('}')) return map;
                do { space(); String key = quoted(); need(':'); map.put(key, value(depth + 1)); if (eat('}')) return map; need(','); } while (!eat('}'));
                return map;
            }
            if (eat('[')) {
                List<Object> list = new ArrayList<>();
                if (eat(']')) return list;
                do { list.add(value(depth + 1)); if (eat(']')) return list; need(','); } while (!eat(']'));
                return list;
            }
            for (String literal : List.of("true", "false", "null")) if (text.startsWith(literal, pos)) {
                pos += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
            int start = pos;
            while (pos < text.length() && "-+0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
            String number = text.substring(start, pos);
            if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IOException("JSON number");
            try { return Double.valueOf(number); } catch (NumberFormatException e) { throw new IOException("JSON number", e); }
        }
        String quoted() throws IOException {
            need('"'); StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++); if (c == '"') return out.toString();
                if (c < 32) throw new IOException("JSON string");
                if (c == '\\') {
                    if (pos >= text.length()) throw new IOException("JSON escape");
                    c = text.charAt(pos++);
                    switch (c) {
                        case '"', '\\', '/' -> out.append(c);
                        case 'b' -> out.append('\b'); case 'f' -> out.append('\f'); case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) throw new IOException("JSON unicode");
                            try { out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16)); } catch (NumberFormatException e) { throw new IOException("JSON unicode", e); }
                            pos += 4;
                        }
                        default -> throw new IOException("JSON escape");
                    }
                } else out.append(c);
            }
            throw new IOException("JSON unclosed string");
        }
    }
}
