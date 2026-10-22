package dev.anchorlight.blockvault.setup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The floor assignment list (floor_assignment.tsv): which block lives on which
 * floor, its rarity, section and edition, in display order. /bvsetup hands
 * niches out in exactly this order, so sections fill continuous runs of wall.
 *
 * <p>Format: tab-separated {@code block chapter rarity section edition}; lines
 * starting with {@code #} are ignored. A copy in the data folder overrides the
 * one bundled in the jar.
 */
public final class Assignment {

    public static final String FILE = "floor_assignment.tsv";

    public record Row(String block, int chapter, String rarity, String section, String edition) {}

    private static final Set<String> RARITIES = Set.of("common", "uncommon", "rare");
    private static final Set<String> SECTIONS =
            Set.of("building", "coloured", "functional", "natural", "redstone");

    private final List<Row> rows;

    private Assignment(List<Row> rows) {
        this.rows = rows;
    }

    public List<Row> rows() {
        return rows;
    }

    public List<Row> floor(int chapter) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) if (r.chapter() == chapter) out.add(r);
        return out;
    }

    public static Assignment load(Path dataFolderFile, ClassLoader loader) throws IOException {
        if (Files.isRegularFile(dataFolderFile)) {
            try (Reader r = Files.newBufferedReader(dataFolderFile, StandardCharsets.UTF_8)) {
                return parse(r);
            }
        }
        try (InputStream in = loader.getResourceAsStream(FILE)) {
            if (in == null) throw new IOException(FILE + " not found in the data folder or the jar");
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static Assignment parse(Reader reader) throws IOException {
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        BufferedReader br = new BufferedReader(reader);
        String line;
        int n = 0;
        while ((line = br.readLine()) != null) {
            n++;
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            if (f.length < 5) throw new IOException(FILE + ":" + n + ": expected 5 tab-separated fields");
            String block = f[0].strip().toLowerCase();
            int chapter;
            try {
                chapter = Integer.parseInt(f[1].strip());
            } catch (NumberFormatException e) {
                throw new IOException(FILE + ":" + n + ": bad chapter '" + f[1] + "'");
            }
            String rarity = f[2].strip().toLowerCase();
            String section = f[3].strip().toLowerCase();
            String edition = f[4].strip();
            if (chapter < 1 || chapter > 6) throw new IOException(FILE + ":" + n + ": chapter must be 1-6");
            if (!RARITIES.contains(rarity)) throw new IOException(FILE + ":" + n + ": bad rarity '" + rarity + "'");
            if (!SECTIONS.contains(section)) throw new IOException(FILE + ":" + n + ": bad section '" + section + "'");
            if (!seen.add(block)) throw new IOException(FILE + ":" + n + ": duplicate block '" + block + "'");
            rows.add(new Row(block, chapter, rarity, section, edition));
        }
        return new Assignment(rows);
    }
}
