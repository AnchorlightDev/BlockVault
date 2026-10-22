package dev.anchorlight.blockvault.listener;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.util.VaultUtil;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Nudges players toward blocks the vault still needs: an actionbar hint while
 * holding one, and a progress line on join. Highest-value feature for actually
 * finishing the collection.
 */
public final class PlayerGuidanceListener implements Listener {
    private final BlockVault plugin;
    private final VaultUtil vaultUtil;

    public PlayerGuidanceListener(BlockVault plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
    }

    /** Called from onEnable: refresh the held-block actionbar hint on a short loop. */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!vaultUtil.hasStarted()) return;
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                ItemStack held = p.getInventory().getItemInMainHand();
                Material type = held.getType();
                if (type == Material.AIR) continue;
                String material = type.getKey().getKey();
                if (plugin.manifest().entry(material) == null) continue;
                if (plugin.database().isCollected(material)) continue;
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        new TextComponent("§eThe vault still needs "
                                + VaultUtil.formatMaterialName(type) + " — §7/bvsubmit"));
            }
        }, 40L, 30L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.advancements().syncPlayer(e.getPlayer());
        if (!vaultUtil.hasStarted()) return;
        int done = plugin.database().collectedCount();
        int total = plugin.manifest().entries().size();
        int pct = total == 0 ? 0 : (int) Math.round(100.0 * done / total);
        plugin.tell(e.getPlayer(), "§7Vault progress: §e" + done + "§7/§e" + total
                + " §7(" + pct + "%). Chapter " + plugin.chapters().current()
                + " is the current floor. §f/bvprogress");
    }
}
