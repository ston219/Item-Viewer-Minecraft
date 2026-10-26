import java.io.*;
import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class L10n {
    enum Language {
        EN("en", "English"), DE("de", "Deutsch"), TR("tr", "Türkçe"), FR("fr", "Français"),
        ES("es", "Español"), IT("it", "Italiano"), JA("ja", "日本語"), KO("ko", "한국어");
        final String code, label;
        Language(String code, String label) { this.code = code; this.label = label; }
        public String toString() { return label; }
    }
    static Language language = Language.EN;
    static final Map<Language, Properties> bundles = new EnumMap<>(Language.class);
    static Font baseFont;
    static Font font(int style, float size) {
        if (language != Language.JA && language != Language.KO) return new Font("Dialog", style, (int)size);
        return cjkFont(style, size);
    }
    static Font cjkFont(int style, float size) {
        if (baseFont == null) {
            try (InputStream in = L10n.class.getResourceAsStream("/fonts/NotoSansCJKjp-Regular.otf")) {
                if (in == null) throw new IOException("Missing bundled font");
                baseFont = Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (Exception e) { throw new IllegalStateException("Could not load bundled multilingual font", e); }
        }
        return baseFont.deriveFont(style, size);
    }
    static Path settingsPath() {
        try {
            Path location = Path.of(L10n.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return (Files.isDirectory(location) ? location : location.getParent()).resolve("viewer-settings.properties");
        } catch (Exception e) { return Path.of("viewer-settings.properties"); }
    }
    static void loadSettings() {
        // First launch always starts in English, independent of the operating system.
        language = Language.EN;
        try (Reader reader = Files.newBufferedReader(settingsPath(), StandardCharsets.UTF_8)) {
            Properties p = new Properties(); p.load(reader);
            for (Language candidate : Language.values()) if (candidate.code.equals(p.getProperty("language"))) language = candidate;
        } catch (IOException | IllegalArgumentException ignored) {}
    }
    static boolean saveLanguage(Language selected) {
        language = selected;
        try {
            Files.writeString(settingsPath(), "language=" + selected.code + "\n", StandardCharsets.UTF_8);
            return true;
        } catch (IOException | SecurityException e) { return false; }
    }
    static Properties bundle(Language lang) {
        return bundles.computeIfAbsent(lang, key -> {
            Properties p = new Properties();
            try (InputStream in = L10n.class.getResourceAsStream("/languages/" + key.code + ".properties")) {
                if (in == null) throw new IllegalStateException("Missing language: " + key.code);
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) { throw new UncheckedIOException(e); }
            return p;
        });
    }
    static String t(String key, Object... args) {
        String value = bundle(language).getProperty(key, bundle(Language.EN).getProperty(key, key));
        for (int i = 0; i < args.length; i++) value = value.replace("{" + i + "}", String.valueOf(args[i]));
        return value;
    }
    static String itemName(BedrockItemViewer.Item item) {
        return item.category().equals("Import") ? item.name() : t("item." + item.id());
    }
}
