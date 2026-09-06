package dev.anchorlight.blockvault.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The parsed vault_slots.json manifest. Immutable for the season.
 *
 * <p>Loaded from the plugin data folder if present, otherwise the bundled
 * resource. If neither exists the caller must fail loudly - the plugin never
 * regenerates the target list (brief section 7).
 */
public final class Manifest {
    private final String version;
    private final int dataVersion;
    private final Map<String, TargetEntry> entries;
    private final Map<String, int[]> leader;
    private final Map<Integer, int[]> seals;
    private final int[] min;
    private final int[] max;

    private Manifest(String version, int dataVersion, Map<String, TargetEntry> entries,
                     Map<String, int[]> leader, Map<Integer, int[]> seals) {
        this.version = version;
        this.dataVersion = dataVersion;
        this.entries = entries;
        this.leader = leader;
        this.seals = seals;

        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (TargetEntry e : entries.values()) {
            for (int[] p : new int[][]{e.sign(), e.head()}) {
                for (int i = 0; i < 3; i++) {
                    lo[i] = Math.min(lo[i], p[i]);
                    hi[i] = Math.max(hi[i], p[i]);
                }
            }
        }
        this.min = lo;
        this.max = hi;
    }

    /** Origin-relative bounding box of every shelf cell, inclusive. */
    public int[] min() { return min; }
    public int[] max() { return max; }

    public String version() { return version; }
    public int dataVersion() { return dataVersion; }
    public Map<String, TargetEntry> entries() { return entries; }
    public TargetEntry entry(String material) { return entries.get(material); }
    public int[] leader(String key) { return leader.get(key); }
    public int[] seal(int chapter) { return seals.get(chapter); }

    public static Manifest load(Path dataFolderFile, ClassLoader loader) throws IOException {
        if (Files.isRegularFile(dataFolderFile)) {
            try (Reader r = Files.newBufferedReader(dataFolderFile, StandardCharsets.UTF_8)) {
                return parse(r);
            }
        }
        try (InputStream in = loader.getResourceAsStream("vault_slots.json")) {
            if (in == null) {
                throw new IOException("vault_slots.json not found in " + dataFolderFile
                        + " or on the classpath - generate it with tools/gen_artifacts.py");
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(r);
            }
        }
    }

    private static Manifest parse(Reader reader) {
        Gson gson = new Gson();
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

        String version = root.get("version").getAsString();
        int dataVersion = root.get("data_version").getAsInt();

        Map<String, TargetEntry> entries = new LinkedHashMap<>();
        root.getAsJsonArray("entries").forEach(el -> {
            JsonObject o = el.getAsJsonObject();
            String block = o.get("block").getAsString();
            entries.put(block, new TargetEntry(
                    block,
                    o.get("chapter").getAsInt(),
                    o.get("rarity").getAsString(),
                    o.get("section").getAsString(),
                    triple(o, "sign"),
                    triple(o, "frame"),
                    triple(o, "head"),
                    o.get("facing").getAsString()
            ));
        });

        Map<String, int[]> leader = new LinkedHashMap<>();
        JsonObject lo = root.getAsJsonObject("leader");
        for (String k : lo.keySet()) {
            leader.put(k, gson.fromJson(lo.get(k), int[].class));
        }

        Map<Integer, int[]> seals = new LinkedHashMap<>();
        JsonObject so = root.getAsJsonObject("seals");
        for (String k : so.keySet()) {
            seals.put(Integer.parseInt(k), gson.fromJson(so.get(k), int[].class));
        }

        return new Manifest(version, dataVersion, entries, leader, seals);
    }

    private static int[] triple(JsonObject o, String key) {
        var arr = o.getAsJsonArray(key);
        return new int[]{ arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt() };
    }
}
