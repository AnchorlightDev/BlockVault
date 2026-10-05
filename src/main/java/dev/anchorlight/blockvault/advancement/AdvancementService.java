package dev.anchorlight.blockvault.advancement;

import dev.anchorlight.blockvault.BlockVault;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Ships a bundled datapack ({@code src/main/resources/datapack}) with one
 * advancement per chapter, granted by the plugin on completion. Paper has no
 * runtime advancement-registration API, so the datapack is written into the
 * world folder during {@link #installDatapack()} (called from onLoad, before
 * worlds load) and picked up automatically.
 */
public final class AdvancementService {

    private static final int CHAPTERS = 6;
    private static final String[] FILES = {
            "pack.mcmeta",
            "data/blockvault/advancement/chapter_1.json",
            "data/blockvault/advancement/chapter_2.json",
            "data/blockvault/advancement/chapter_3.json",
            "data/blockvault/advancement/chapter_4.json",
            "data/blockvault/advancement/chapter_5.json",
            "data/blockvault/advancement/chapter_6.json",
    };

    private final BlockVault plugin;

    public AdvancementService(BlockVault plugin) {
        this.plugin = plugin;
    }

    /** Write the datapack into &lt;worldContainer&gt;/&lt;level-name&gt;/datapacks/blockvault. */
    public void installDatapack() {
        try {
            String level = levelName();
            Path root = plugin.getServer().getWorldContainer().toPath()
                    .resolve(level).resolve("datapacks").resolve("blockvault");
            String stamp = plugin.getPluginMeta().getVersion();
            Path marker = root.resolve(".plugin-version");

            if (Files.isRegularFile(marker) && stamp.equals(Files.readString(marker).trim())) {
                return; // already current
            }
            for (String rel : FILES) {
                Path target = root.resolve(rel);
                Files.createDirectories(target.getParent());
                try (InputStream in = plugin.getResource("datapack/" + rel)) {
                    if (in == null) {
                        plugin.getLogger().warning("Datapack resource missing: " + rel);
                        continue;
                    }
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.writeString(marker, stamp);
            plugin.getLogger().info("Installed chapter-advancement datapack for level '" + level
                    + "'. A world reload may be needed on first install.");
        } catch (Exception e) {
            plugin.getLogger().warning("Could not install advancement datapack: " + e.getMessage());
        }
    }

    private String levelName() {
        File props = new File("server.properties");
        if (props.isFile()) {
            try {
                var p = new java.util.Properties();
                try (var r = Files.newBufferedReader(props.toPath())) { p.load(r); }
                String name = p.getProperty("level-name");
                if (name != null && !name.isBlank()) return name.trim();
            } catch (Exception ignored) {
                // fall through
            }
        }
        return "world";
    }

    /** Award the chapter advancement to everyone online. Main thread. */
    public void grantAll(int chapter) {
        Advancement adv = lookup(chapter);
        if (adv == null) return;
        for (Player p : plugin.getServer().getOnlinePlayers()) award(p, adv);
    }

    /** On join, reconcile a player against the chapters already completed. */
    public void syncPlayer(Player player) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<Integer> done = plugin.database().completedChapters();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (int ch : done) {
                    Advancement adv = lookup(ch);
                    if (adv != null) award(player, adv);
                }
            });
        });
    }

    private Advancement lookup(int chapter) {
        if (chapter < 1 || chapter > CHAPTERS) return null;
        return plugin.getServer().getAdvancement(
                new NamespacedKey("blockvault", "chapter_" + chapter));
    }

    private void award(Player player, Advancement adv) {
        var progress = player.getAdvancementProgress(adv);
        if (!progress.isDone()) progress.awardCriteria("granted");
    }
}
