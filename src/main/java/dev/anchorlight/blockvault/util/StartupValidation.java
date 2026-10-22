package dev.anchorlight.blockvault.util;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.model.TargetEntry;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Cross-checks the three sources of truth on startup: the manifest
 * (vault_slots.json), the rarity list (vault_items.yml) and bv_target.
 * Silent drift between them is how you discover in month eight that four
 * blocks have no niche (brief section 8, Operations).
 *
 * <p>Logs findings; never aborts. Also flags blocks the running game has
 * that the frozen edition does not cover.
 */
public final class StartupValidation {

    /** Blocks that exist in the registry but cannot be obtained in survival (brief section 9). */
    private static final Set<String> UNOBTAINABLE = Set.of(
            "air", "barrier", "bedrock", "budding_amethyst", "chain_command_block", "chorus_plant",
            "command_block", "dirt_path", "end_portal_frame", "farmland", "frogspawn", "jigsaw", "light", "petrified_oak_slab", "player_head",
            "infested_chiseled_stone_bricks", "infested_cobblestone", "infested_cracked_stone_bricks",
            "infested_deepslate", "infested_mossy_stone_bricks", "infested_stone", "infested_stone_bricks",
            "reinforced_deepslate", "repeating_command_block", "spawner", "structure_block",
            "structure_void", "suspicious_gravel", "suspicious_sand", "test_block",
            "test_instance_block", "trial_spawner", "vault");

    private StartupValidation() {}

    public static void run(BlockVault plugin) {
        Logger log = plugin.getLogger();

        Map<String, String> manifest = new TreeMap<>();
        for (TargetEntry e : plugin.manifest().entries().values()) {
            manifest.put(e.material(), e.rarity());
        }

        Map<String, String> items = loadItems(plugin);
        Set<String> target = plugin.database().targetMaterials();

        int problems = 0;

        // Two blocks must never share a niche - the second head would hide the first.
        Map<String, String> byCell = new java.util.HashMap<>();
        for (TargetEntry e : plugin.manifest().entries().values()) {
            String cell = e.head()[0] + "," + e.head()[1] + "," + e.head()[2];
            String prev = byCell.putIfAbsent(cell, e.material());
            if (prev != null) {
                log.warning("[validation] niche " + cell + " is used by both "
                        + prev + " and " + e.material() + " - one will be unreachable");
                problems++;
            }
        }

        // Blocks that can never be donated make 100% impossible.
        for (String k : manifest.keySet()) {
            if (UNOBTAINABLE.contains(k)) {
                log.warning("[validation] " + k + " is in the target list but cannot be obtained in survival");
                problems++;
            }
        }

        // Every floor that has blocks should have its seal recorded (floor 1 has none).
        for (int ch = 2; ch <= 6; ch++) {
            final int c = ch;
            boolean hasBlocks = plugin.manifest().entries().values().stream().anyMatch(e -> e.chapter() == c);
            if (hasBlocks && plugin.manifest().seal(ch) == null) {
                log.warning("[validation] floor " + ch + " has no seal recorded - /bvsetup seal " + ch);
                problems++;
            }
        }

        // manifest <-> vault_items.yml (optional legacy cross-check; the layout
        // built by /bvsetup carries rarities itself)
        if (!items.isEmpty()) for (var entry : manifest.entrySet()) {
            String r = items.get(entry.getKey());
            if (r == null) {
                log.warning("[validation] " + entry.getKey() + " is in the manifest but missing from vault_items.yml");
                problems++;
            } else if (!r.equalsIgnoreCase(entry.getValue())) {
                log.warning("[validation] " + entry.getKey() + " rarity disagrees: manifest="
                        + entry.getValue() + " vault_items.yml=" + r);
                problems++;
            }
        }
        if (!items.isEmpty()) for (String k : items.keySet()) {
            if (!manifest.containsKey(k)) {
                log.warning("[validation] " + k + " is in vault_items.yml but not the manifest");
                problems++;
            }
        }

        // manifest <-> bv_target
        for (String k : manifest.keySet()) {
            if (!target.contains(k)) {
                log.warning("[validation] " + k + " is in the manifest but not bv_target (edition mismatch?)");
                problems++;
            }
        }
        for (String k : target) {
            if (!manifest.containsKey(k)) {
                log.warning("[validation] bv_target has " + k + " which the manifest does not (stale edition row)");
                problems++;
            }
        }

        // manifest <-> live registry
        Set<String> newBlocks = new LinkedHashSet<>();
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isBlock() || !m.isItem()) continue;
            String key = m.getKey().getKey();
            if (UNOBTAINABLE.contains(key)) continue;
            if (!manifest.containsKey(key)) newBlocks.add(key);
        }
        for (String k : manifest.keySet()) {
            Material m = Material.matchMaterial(k);
            if (m == null) {
                log.warning("[validation] manifest block " + k + " is not a Material on this server version");
                problems++;
            }
        }
        if (!newBlocks.isEmpty()) {
            log.warning("[validation] " + newBlocks.size() + " obtainable block(s) exist that edition "
                    + plugin.database().edition() + " does not cover - candidates for the next edition:");
            log.warning("[validation]   " + String.join(", ", newBlocks));
        }

        if (problems == 0) {
            log.info("[validation] " + manifest.size()
                    + " blocks matched across the layout and bv_target ("
                    + plugin.manifest().reserved().size() + " reserved for other editions, "
                    + plugin.manifest().spares().size() + " spare niches).");
        } else {
            log.warning("[validation] " + problems + " discrepancy/ies found - see above.");
        }
    }

    private static Map<String, String> loadItems(BlockVault plugin) {
        Map<String, String> out = new TreeMap<>();
        java.io.File override = new java.io.File(plugin.getDataFolder(), "vault_items.yml");
        YamlConfiguration yaml;
        if (override.isFile()) {
            yaml = YamlConfiguration.loadConfiguration(override);
        } else {
            try (InputStream in = plugin.getResource("vault_items.yml")) {
                if (in == null) return out; // optional: rarities live in the layout now
                yaml = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (Exception e) {
                plugin.getLogger().warning("[validation] could not read vault_items.yml: " + e.getMessage());
                return out;
            }
        }
        var section = yaml.getConfigurationSection("items");
        if (section == null) return out;
        for (String k : section.getKeys(false)) {
            out.put(k.toLowerCase(), String.valueOf(section.get(k)));
        }
        return out;
    }
}
