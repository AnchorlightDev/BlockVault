package dev.anchorlight.blockvault.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The parsed vault_slots.json layout, written by {@code /bvsetup}. Immutable
 * for the season once the event has started.
 *
 * <p>Loaded from the plugin data folder if present, otherwise the bundled
 * resource. If neither exists the plugin starts in setup mode.
 *
 * <p>Only entries whose {@code edition} matches the running edition are season
 * targets; the rest (e.g. 26.3 blocks whose niches are already built) are kept
 * as {@link #reserved()} so their heads stay in place but count for nothing.
 */
public final class Manifest {

    public static final String FILE = "vault_slots.json";

    private final String version;
    private final int dataVersion;
    private final Map<String, TargetEntry> entries;
    private final List<TargetEntry> reserved;
    private final List<HeadSlot> spares;
    private final Map<Integer, List<HeadSlot>> floorLeaders;
    private final Map<String, int[]> leader;
    private final Map<Integer, int[]> seals;
    private final int[] min;
    private final int[] max;

    private Manifest(String version, int dataVersion, Map<String, TargetEntry> entries,
                     List<TargetEntry> reserved, List<HeadSlot> spares,
                     Map<Integer, List<HeadSlot>> floorLeaders,
                     Map<String, int[]> leader, Map<Integer, int[]> seals) {
        this.version = version;
        this.dataVersion = dataVersion;
        this.entries = entries;
        this.reserved = reserved;
        this.spares = spares;
        this.floorLeaders = floorLeaders;
        this.leader = leader;
        this.seals = seals;

        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        List<int[]> points = new ArrayList<>();
        entries.values().forEach(e -> points.add(e.head()));
        reserved.forEach(e -> points.add(e.head()));
        spares.forEach(s -> points.add(s.head()));
        floorLeaders.values().forEach(l -> l.forEach(s -> points.add(s.head())));
        points.addAll(seals.values());
        for (int[] p : points) {
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.min(lo[i], p[i]);
                hi[i] = Math.max(hi[i], p[i]);
            }
        }
        if (points.isEmpty()) {
            lo = new int[]{0, 0, 0};
            hi = new int[]{0, 0, 0};
        }
        this.min = lo;
        this.max = hi;
    }

    public int[] min() { return min; }
    public int[] max() { return max; }

    public String version() { return version; }
    public int dataVersion() { return dataVersion; }
    /** Season targets for the running edition, keyed by material. */
    public Map<String, TargetEntry> entries() { return entries; }
    public TargetEntry entry(String material) { return entries.get(material); }
    /** Niches built for another edition: shown as plain heads, never counted. */
    public List<TargetEntry> reserved() { return reserved; }
    /** Niches with no block assigned. */
    public List<HeadSlot> spares() { return spares; }
    /** Standing heads per floor, in rank order (#1 first). */
    public List<HeadSlot> floorLeaders(int chapter) {
        return floorLeaders.getOrDefault(chapter, List.of());
    }
    public Map<Integer, List<HeadSlot>> floorLeaders() { return floorLeaders; }
    public int[] leader(String key) { return leader.get(key); }
    public int[] seal(int chapter) { return seals.get(chapter); }

    /** True if the file exists in the data folder or the jar. */
    public static boolean exists(Path dataFolderFile, ClassLoader loader) {
        if (Files.isRegularFile(dataFolderFile)) return true;
        try (InputStream in = loader.getResourceAsStream(FILE)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    public static Manifest load(Path dataFolderFile, ClassLoader loader, String edition) throws IOException {
        if (Files.isRegularFile(dataFolderFile)) {
            try (Reader r = Files.newBufferedReader(dataFolderFile, StandardCharsets.UTF_8)) {
                return parse(JsonParser.parseReader(r).getAsJsonObject(), edition);
            }
        }
        try (InputStream in = loader.getResourceAsStream(FILE)) {
            if (in == null) {
                throw new IOException(FILE + " not found in " + dataFolderFile
                        + " or on the classpath - run /bvsetup to build it");
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(JsonParser.parseReader(r).getAsJsonObject(), edition);
            }
        }
    }

    public static Manifest parse(JsonObject root, String edition) {
        Gson gson = new Gson();

        String version = root.has("version") ? root.get("version").getAsString() : edition;
        int dataVersion = root.has("data_version") ? root.get("data_version").getAsInt() : 0;

        Map<String, TargetEntry> entries = new LinkedHashMap<>();
        List<TargetEntry> reserved = new ArrayList<>();
        if (root.has("entries")) {
            for (JsonElement el : root.getAsJsonArray("entries")) {
                JsonObject o = el.getAsJsonObject();
                TargetEntry e = new TargetEntry(
                        o.get("block").getAsString(),
                        o.get("chapter").getAsInt(),
                        o.get("rarity").getAsString(),
                        o.get("section").getAsString(),
                        o.has("edition") ? o.get("edition").getAsString() : edition,
                        triple(o.getAsJsonArray("head")),
                        o.get("facing").getAsString());
                if (e.edition().equals(edition)) entries.put(e.material(), e);
                else reserved.add(e);
            }
        }

        List<HeadSlot> spares = new ArrayList<>();
        if (root.has("spares")) {
            for (JsonElement el : root.getAsJsonArray("spares")) spares.add(slot(el.getAsJsonObject()));
        }

        Map<Integer, List<HeadSlot>> floorLeaders = new TreeMap<>();
        if (root.has("floor_leaders")) {
            JsonObject fl = root.getAsJsonObject("floor_leaders");
            for (String k : fl.keySet()) {
                List<HeadSlot> list = new ArrayList<>();
                for (JsonElement el : fl.getAsJsonArray(k)) list.add(slot(el.getAsJsonObject()));
                floorLeaders.put(Integer.parseInt(k), Collections.unmodifiableList(list));
            }
        }

        Map<String, int[]> leader = new LinkedHashMap<>();
        if (root.has("leader")) {
            JsonObject lo = root.getAsJsonObject("leader");
            for (String k : lo.keySet()) leader.put(k, gson.fromJson(lo.get(k), int[].class));
        }

        Map<Integer, int[]> seals = new LinkedHashMap<>();
        if (root.has("seals")) {
            JsonObject so = root.getAsJsonObject("seals");
            for (String k : so.keySet()) seals.put(Integer.parseInt(k), gson.fromJson(so.get(k), int[].class));
        }

        return new Manifest(version, dataVersion, entries, reserved, spares, floorLeaders, leader, seals);
    }

    private static HeadSlot slot(JsonObject o) {
        return new HeadSlot(
                o.has("chapter") ? o.get("chapter").getAsInt() : 0,
                triple(o.getAsJsonArray("head")),
                !o.has("wall") || o.get("wall").getAsBoolean(),
                o.has("facing") ? o.get("facing").getAsString() : "north");
    }

    private static int[] triple(JsonArray arr) {
        return new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()};
    }
}
