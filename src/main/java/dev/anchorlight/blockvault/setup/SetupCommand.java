package dev.anchorlight.blockvault.setup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.model.Manifest;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /bvsetup} - builds vault_slots.json from the world instead of by hand.
 *
 * <pre>
 *   /bvsetup pos1 | pos2         corners of one floor (your feet position)
 *   /bvsetup floor &lt;1-6&gt;       scan, assign and preview that floor
 *   /bvsetup confirm | cancel    save or discard the preview
 *   /bvsetup seal &lt;2-6&gt;        record the iron-bar seal you're looking at
 *   /bvsetup lobby [head|name|count|title]   record the lobby leader panel
 *   /bvsetup status              what's been recorded so far
 * </pre>
 *
 * Wall heads inside the selection are niches; they are ordered clockwise from
 * north around the floor's centre, top to bottom, and handed blocks from
 * floor_assignment.tsv in list order. Standing heads are the floor's leader
 * pedestals, ranked #1, #2... in the same clockwise order.
 *
 * <p>Only allowed before {@code /bvstart}: the layout is frozen for the season.
 * Works even when no layout exists yet (the plugin starts in setup mode).
 */
public final class SetupCommand implements CommandExecutor, TabCompleter {

    private static final int MAX_VOLUME = 4_000_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private record Niche(int[] abs, boolean wall, String facing) {}
    private record Placement(Assignment.Row row, Niche niche) {}
    private record Pending(int chapter, List<Placement> placed, List<Niche> spares,
                           List<Niche> leaders, List<Entity> preview) {}

    private final BlockVault plugin;
    private final VaultUtil vaultUtil;
    private final NamespacedKey previewKey;
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();

    public SetupCommand(BlockVault plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
        this.previewKey = new NamespacedKey(plugin, "setup_preview");
    }

    private Path layoutFile() {
        return plugin.getDataFolder().toPath().resolve(Manifest.FILE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blockvault.setup")) {
            plugin.tell(sender, "§cYou don't have permission to use this command.");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("status")) {
            status(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.tell(sender, "§cOnly players can run that - it uses where you're standing or looking.");
            return true;
        }
        if (!sub.equals("help") && !sub.equals("cancel")
                && plugin.getConfig().getBoolean("vault.started", false)) {
            plugin.tell(player, "§cThe vault is open - the layout is frozen for the season. "
                    + "§7(/bvstart stop first if this is a test server.)");
            return true;
        }
        switch (sub) {
            case "pos1" -> setPos(player, 0);
            case "pos2" -> setPos(player, 1);
            case "floor" -> floor(player, args);
            case "confirm" -> confirm(player);
            case "cancel" -> cancel(player, true);
            case "seal" -> seal(player, args);
            case "lobby" -> lobby(player, args);
            default -> help(player);
        }
        return true;
    }

    private void help(CommandSender s) {
        plugin.tell(s, "§6Vault setup");
        s.sendMessage("§7 1. Stand at one corner of a floor: §f/bvsetup pos1§7, the opposite corner: §f/bvsetup pos2");
        s.sendMessage("§7 2. §f/bvsetup floor <1-6> §7- scans and previews the floating items");
        s.sendMessage("§7 3. §f/bvsetup confirm §7(or §f/bvsetup cancel§7)");
        s.sendMessage("§7 4. Look at each iron-bar seal: §f/bvsetup seal <2-6>");
        s.sendMessage("§7 5. Look at the lobby leader head: §f/bvsetup lobby");
        s.sendMessage("§7 6. §f/bvsetup status§7, then restart the server");
    }

    // ------------------------------------------------------------------ selection

    private void setPos(Player p, int i) {
        Location[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        sel[i] = p.getLocation().getBlock().getLocation();
        plugin.tell(p, "§aPosition " + (i + 1) + " set to §f" + sel[i].getBlockX() + " "
                + sel[i].getBlockY() + " " + sel[i].getBlockZ());
    }

    // ------------------------------------------------------------------ floor scan

    private void floor(Player p, String[] args) {
        int chapter = args.length > 1 ? parseInt(args[1]) : -1;
        if (chapter < 1 || chapter > 6) {
            plugin.tell(p, "§cUsage: /bvsetup floor <1-6>");
            return;
        }
        Location[] sel = selections.get(p.getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null) {
            plugin.tell(p, "§cSet both corners first: /bvsetup pos1 and /bvsetup pos2");
            return;
        }
        World world = sel[0].getWorld();
        if (world == null || !world.equals(sel[1].getWorld())) {
            plugin.tell(p, "§cBoth corners must be in the same world.");
            return;
        }
        if (!world.getName().equals(plugin.getConfig().getString("origin.world", "world"))) {
            plugin.tell(p, "§cThe selection is in '" + world.getName() + "' but origin.world is '"
                    + plugin.getConfig().getString("origin.world") + "'. Fix config.yml first.");
            return;
        }
        int x1 = Math.min(sel[0].getBlockX(), sel[1].getBlockX()), x2 = Math.max(sel[0].getBlockX(), sel[1].getBlockX());
        int y1 = Math.min(sel[0].getBlockY(), sel[1].getBlockY()), y2 = Math.max(sel[0].getBlockY(), sel[1].getBlockY());
        int z1 = Math.min(sel[0].getBlockZ(), sel[1].getBlockZ()), z2 = Math.max(sel[0].getBlockZ(), sel[1].getBlockZ());
        long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (volume > MAX_VOLUME) {
            plugin.tell(p, "§cThat selection is " + volume + " blocks - select one floor at a time.");
            return;
        }

        Assignment assignment;
        try {
            assignment = Assignment.load(plugin.getDataFolder().toPath().resolve(Assignment.FILE),
                    plugin.getClass().getClassLoader());
        } catch (IOException e) {
            plugin.tell(p, "§c" + e.getMessage());
            return;
        }
        List<Assignment.Row> rows = assignment.floor(chapter);
        if (rows.isEmpty()) {
            plugin.tell(p, "§cfloor_assignment.tsv has no blocks for floor " + chapter + ".");
            return;
        }

        // Heads already claimed by another floor (overlapping selections) or the lobby.
        JsonObject root = readLayout();
        Set<String> taken = claimedElsewhere(root, chapter);

        int[] origin = origin();
        List<Niche> niches = new ArrayList<>();
        List<Niche> leaders = new ArrayList<>();
        int skipped = 0;
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    Block b = world.getBlockAt(x, y, z);
                    Material t = b.getType();
                    if (t != Material.PLAYER_WALL_HEAD && t != Material.PLAYER_HEAD) continue;
                    int[] rel = {x - origin[0], y - origin[1], z - origin[2]};
                    if (taken.contains(key(rel))) { skipped++; continue; }
                    if (t == Material.PLAYER_WALL_HEAD) {
                        BlockFace f = b.getBlockData() instanceof Directional d ? d.getFacing() : BlockFace.NORTH;
                        niches.add(new Niche(new int[]{x, y, z}, true, f.name().toLowerCase(Locale.ROOT)));
                    } else {
                        BlockFace f = b.getBlockData() instanceof Rotatable r ? r.getRotation() : BlockFace.NORTH;
                        leaders.add(new Niche(new int[]{x, y, z}, false, f.name().toLowerCase(Locale.ROOT)));
                    }
                }
            }
        }

        if (niches.size() < rows.size()) {
            plugin.tell(p, "§cFloor " + chapter + " needs " + rows.size() + " wall heads but the selection has "
                    + niches.size() + (skipped > 0 ? " §7(" + skipped + " skipped - already used by another floor)" : "")
                    + "§c. Check the corners cover the whole floor.");
            return;
        }

        sortClockwise(niches);
        sortClockwise(leaders);

        cancel(p, false); // drop any earlier preview
        List<Placement> placed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) placed.add(new Placement(rows.get(i), niches.get(i)));
        List<Niche> spares = new ArrayList<>(niches.subList(rows.size(), niches.size()));

        List<Entity> preview = spawnPreview(world, placed, spares, leaders);
        pending.put(p.getUniqueId(), new Pending(chapter, placed, spares, leaders, preview));

        plugin.tell(p, "§6Floor " + chapter + " preview: §f" + niches.size() + " §7wall heads, §f"
                + rows.size() + " §7blocks, §f" + spares.size() + " §7spare, §f" + leaders.size()
                + " §7leader pedestals" + (skipped > 0 ? ", " + skipped + " skipped (other floors)" : "") + ".");
        Map<String, List<Placement>> bySection = new LinkedHashMap<>();
        for (Placement pl : placed) bySection.computeIfAbsent(pl.row().section(), k -> new ArrayList<>()).add(pl);
        bySection.forEach((section, list) -> p.sendMessage("§7  " + section + " §f" + list.size()
                + " §7(" + list.get(0).row().block() + " → " + list.get(list.size() - 1).row().block() + ")"));
        plugin.tell(p, "§7Walk the floor and check it, then §f/bvsetup confirm §7or §f/bvsetup cancel§7.");
    }

    /** Clockwise from north around the group's centre, then top to bottom. */
    private static void sortClockwise(List<Niche> list) {
        if (list.isEmpty()) return;
        double cx = 0, cz = 0;
        for (Niche n : list) { cx += n.abs()[0] + 0.5; cz += n.abs()[2] + 0.5; }
        final double mx = cx / list.size(), mz = cz / list.size();
        list.sort(Comparator
                .comparingDouble((Niche n) -> {
                    double a = Math.atan2(n.abs()[0] + 0.5 - mx, -(n.abs()[2] + 0.5 - mz));
                    if (a < 0) a += 2 * Math.PI;
                    return Math.round(a * 1e6) / 1e6;
                })
                .thenComparingInt((Niche n) -> -n.abs()[1]));
    }

    private List<Entity> spawnPreview(World world, List<Placement> placed, List<Niche> spares, List<Niche> leaders) {
        List<Entity> out = new ArrayList<>();
        int[] origin = origin();
        for (Placement pl : placed) {
            Material m = Material.matchMaterial(pl.row().block());
            int[] rel = rel(pl.niche().abs(), origin);
            Location at = vaultUtil.itemLocation(rel, true, faceOf(pl.niche().facing()));
            if (m != null && m.isItem()) {
                var d = vaultUtil.spawnItem(at, "preview", new ItemStack(m), previewKey);
                d.setPersistent(false);
                out.add(d);
            } else {
                // Other-edition block this server doesn't know yet: label it instead.
                out.add(label(at, "§d" + pl.row().block() + "\n§7" + pl.row().edition()));
            }
        }
        for (Niche n : spares) {
            out.add(label(vaultUtil.itemLocation(rel(n.abs(), origin), true, faceOf(n.facing())), "§8spare"));
        }
        for (int i = 0; i < leaders.size(); i++) {
            Niche n = leaders.get(i);
            Location at = new Location(world, n.abs()[0] + 0.5, n.abs()[1] + 0.9, n.abs()[2] + 0.5);
            out.add(label(at, "§6#" + (i + 1)));
        }
        return out;
    }

    private TextDisplay label(Location at, String text) {
        return at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.getPersistentDataContainer().set(previewKey, org.bukkit.persistence.PersistentDataType.STRING, "preview");
            d.setText(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
        });
    }

    /** Remove every preview entity (plugin disable). */
    public void cancelAll() {
        pending.values().forEach(pd -> pd.preview().forEach(Entity::remove));
        pending.clear();
    }

    private void cancel(Player p, boolean announce) {
        Pending old = pending.remove(p.getUniqueId());
        if (old != null) old.preview().forEach(Entity::remove);
        if (announce) plugin.tell(p, old == null ? "§7Nothing to cancel." : "§7Preview discarded.");
    }

    // ------------------------------------------------------------------ save

    private void confirm(Player p) {
        Pending pend = pending.get(p.getUniqueId());
        if (pend == null) {
            plugin.tell(p, "§cNo preview to confirm - run /bvsetup floor <n> first.");
            return;
        }
        int chapter = pend.chapter();
        int[] origin = origin();
        JsonObject root = readLayout();

        JsonArray entries = new JsonArray();
        for (JsonElement el : root.getAsJsonArray("entries")) {
            if (el.getAsJsonObject().get("chapter").getAsInt() != chapter) entries.add(el);
        }
        for (Placement pl : pend.placed()) {
            JsonObject o = new JsonObject();
            o.addProperty("block", pl.row().block());
            o.addProperty("chapter", chapter);
            o.addProperty("rarity", pl.row().rarity());
            o.addProperty("section", pl.row().section());
            o.addProperty("edition", pl.row().edition());
            o.add("head", arr(rel(pl.niche().abs(), origin)));
            o.addProperty("facing", pl.niche().facing());
            entries.add(o);
        }
        root.add("entries", entries);

        JsonArray spares = new JsonArray();
        for (JsonElement el : root.getAsJsonArray("spares")) {
            if (el.getAsJsonObject().get("chapter").getAsInt() != chapter) spares.add(el);
        }
        for (Niche n : pend.spares()) spares.add(slot(chapter, n, origin));
        root.add("spares", spares);

        JsonArray leaders = new JsonArray();
        for (Niche n : pend.leaders()) leaders.add(slot(chapter, n, origin));
        root.getAsJsonObject("floor_leaders").add(String.valueOf(chapter), leaders);

        if (!writeLayout(p, root)) return;
        cancel(p, false);
        plugin.tell(p, "§aFloor " + chapter + " saved: " + pend.placed().size() + " blocks, "
                + pend.spares().size() + " spare, " + pend.leaders().size() + " leader pedestals.");
        plugin.tell(p, "§7Restart the server once every floor, seal and the lobby are done. §f/bvsetup status");
    }

    private void seal(Player p, String[] args) {
        int chapter = args.length > 1 ? parseInt(args[1]) : -1;
        if (chapter < 2 || chapter > 6) {
            plugin.tell(p, "§cUsage: /bvsetup seal <2-6> §7(while looking at that floor's iron bars)");
            return;
        }
        Block b = p.getTargetBlockExact(8);
        if (b == null) {
            plugin.tell(p, "§cLook at the seal block (within 8 blocks).");
            return;
        }
        if (b.getType() != Material.IRON_BARS) {
            plugin.tell(p, "§eThat's " + b.getType().getKey().getKey()
                    + ", not iron bars - recording it anyway (it's set to air when the floor opens).");
        }
        JsonObject root = readLayout();
        root.getAsJsonObject("seals").add(String.valueOf(chapter), arr(relOf(b, origin())));
        if (writeLayout(p, root)) plugin.tell(p, "§aSeal for floor " + chapter + " recorded.");
    }

    private void lobby(Player p, String[] args) {
        String part = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "head";
        String key = switch (part) {
            case "head" -> "head";
            case "name" -> "name_sign";
            case "count" -> "count_sign";
            case "title" -> "title";
            default -> null;
        };
        if (key == null) {
            plugin.tell(p, "§cUsage: /bvsetup lobby [head|name|count|title]");
            return;
        }
        Block b = p.getTargetBlockExact(8);
        if (b == null) {
            plugin.tell(p, "§cLook at the block (within 8 blocks).");
            return;
        }
        JsonObject root = readLayout();
        root.getAsJsonObject("leader").add(key, arr(relOf(b, origin())));
        if (writeLayout(p, root)) plugin.tell(p, "§aLobby " + part + " recorded.");
    }

    private void status(CommandSender s) {
        JsonObject root = readLayout();
        Map<Integer, int[]> counts = new HashMap<>(); // chapter -> {season, other edition, spare, leaders}
        String edition = plugin.getConfig().getString("edition", "26.2");
        for (JsonElement el : root.getAsJsonArray("entries")) {
            JsonObject o = el.getAsJsonObject();
            int[] c = counts.computeIfAbsent(o.get("chapter").getAsInt(), k -> new int[4]);
            boolean season = !o.has("edition") || edition.equals(o.get("edition").getAsString());
            c[season ? 0 : 1]++;
        }
        for (JsonElement el : root.getAsJsonArray("spares")) {
            counts.computeIfAbsent(el.getAsJsonObject().get("chapter").getAsInt(), k -> new int[4])[2]++;
        }
        JsonObject fl = root.getAsJsonObject("floor_leaders");
        for (String k : fl.keySet()) {
            counts.computeIfAbsent(Integer.parseInt(k), x -> new int[4])[3] = fl.getAsJsonArray(k).size();
        }
        JsonObject seals = root.getAsJsonObject("seals");
        plugin.tell(s, "§6Vault layout §7(" + layoutFile().getFileName() + ")");
        for (int ch = 1; ch <= 6; ch++) {
            int[] c = counts.get(ch);
            String sealTxt = ch == 1 ? "" : seals.has(String.valueOf(ch)) ? " §a· seal ✔" : " §c· seal missing";
            s.sendMessage(c == null
                    ? "§7  Floor " + ch + ": §cnot scanned" + sealTxt
                    : "§7  Floor " + ch + ": §f" + c[0] + " §7blocks"
                      + (c[1] > 0 ? " + §d" + c[1] + " §7other edition" : "")
                      + ", §f" + c[2] + " §7spare, §f" + c[3] + " §7leaders" + sealTxt);
        }
        s.sendMessage("§7  Lobby leader head: "
                + (root.getAsJsonObject("leader").has("head") ? "§a✔" : "§eoptional, not set"));
    }

    // ------------------------------------------------------------------ file

    private JsonObject readLayout() {
        JsonObject root = null;
        Path f = layoutFile();
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(r).getAsJsonObject();
            } catch (Exception e) {
                plugin.getLogger().warning("Could not read " + f + ": " + e.getMessage());
            }
        }
        if (root == null) root = new JsonObject();
        root.addProperty("version", plugin.getConfig().getString("edition", "26.2"));
        if (!root.has("data_version")) root.addProperty("data_version", dataVersion());
        for (String k : new String[]{"entries", "spares"}) if (!root.has(k)) root.add(k, new JsonArray());
        for (String k : new String[]{"floor_leaders", "leader", "seals"}) if (!root.has(k)) root.add(k, new JsonObject());
        return root;
    }

    private boolean writeLayout(CommandSender s, JsonObject root) {
        Path f = layoutFile();
        try {
            Files.createDirectories(f.getParent());
            if (Files.isRegularFile(f)) {
                Files.copy(f, f.resolveSibling(Manifest.FILE + ".bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = f.resolveSibling(Manifest.FILE + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            plugin.tell(s, "§cCould not write " + f.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static int dataVersion() {
        try {
            return Bukkit.getUnsafe().getDataVersion();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ helpers

    private Set<String> claimedElsewhere(JsonObject root, int chapter) {
        Set<String> out = new HashSet<>();
        for (JsonElement el : root.getAsJsonArray("entries")) {
            JsonObject o = el.getAsJsonObject();
            if (o.get("chapter").getAsInt() != chapter) out.add(keyOf(o.getAsJsonArray("head")));
        }
        for (JsonElement el : root.getAsJsonArray("spares")) {
            JsonObject o = el.getAsJsonObject();
            if (o.get("chapter").getAsInt() != chapter) out.add(keyOf(o.getAsJsonArray("head")));
        }
        JsonObject fl = root.getAsJsonObject("floor_leaders");
        for (String k : fl.keySet()) {
            if (Integer.parseInt(k) == chapter) continue;
            for (JsonElement el : fl.getAsJsonArray(k)) out.add(keyOf(el.getAsJsonObject().getAsJsonArray("head")));
        }
        JsonObject lobby = root.getAsJsonObject("leader");
        if (lobby.has("head")) out.add(keyOf(lobby.getAsJsonArray("head")));
        return out;
    }

    private static JsonObject slot(int chapter, Niche n, int[] origin) {
        JsonObject o = new JsonObject();
        o.addProperty("chapter", chapter);
        o.add("head", arr(rel(n.abs(), origin)));
        o.addProperty("wall", n.wall());
        o.addProperty("facing", n.facing());
        return o;
    }

    private int[] origin() {
        return new int[]{
                plugin.getConfig().getInt("origin.x"),
                plugin.getConfig().getInt("origin.y"),
                plugin.getConfig().getInt("origin.z")};
    }

    private static int[] rel(int[] abs, int[] origin) {
        return new int[]{abs[0] - origin[0], abs[1] - origin[1], abs[2] - origin[2]};
    }

    private static int[] relOf(Block b, int[] origin) {
        return rel(new int[]{b.getX(), b.getY(), b.getZ()}, origin);
    }

    private static JsonArray arr(int[] v) {
        JsonArray a = new JsonArray();
        for (int i : v) a.add(i);
        return a;
    }

    private static String key(int[] v) {
        return v[0] + "," + v[1] + "," + v[2];
    }

    private static String keyOf(JsonArray a) {
        return a.get(0).getAsInt() + "," + a.get(1).getAsInt() + "," + a.get(2).getAsInt();
    }

    private static BlockFace faceOf(String facing) {
        return dev.anchorlight.blockvault.model.HeadSlot.parseFace(facing);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("pos1", "pos2", "floor", "confirm", "cancel", "seal", "lobby", "status")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "floor" -> List.of("1", "2", "3", "4", "5", "6");
                case "seal" -> List.of("2", "3", "4", "5", "6");
                case "lobby" -> List.of("head", "name", "count", "title");
                default -> List.of();
            };
        }
        return List.of();
    }
}
