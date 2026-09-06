package dev.anchorlight.blockvault.util;

import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Directional;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.util.UUID;

/** Places donor player heads and (de)serialises the cached skin profile JSON. */
public final class HeadUtil {

    private HeadUtil() {}

    /** Place a wall-mounted player head at {@code loc} facing {@code aisle}, wearing {@code profile}. */
    public static void placeHead(Location loc, BlockFace aisle, PlayerProfile profile) {
        Block block = loc.getBlock();
        block.setType(Material.PLAYER_WALL_HEAD, false);
        if (block.getBlockData() instanceof Directional dir) {
            dir.setFacing(aisle);
            block.setBlockData(dir, false);
        }
        if (block.getState() instanceof Skull skull) {
            skull.setOwnerProfile(profile);
            skull.update(true, false);
        }
    }

    /** Minimal JSON snapshot of a profile, enough to rebuild the head years later. */
    public static String toJson(PlayerProfile profile) {
        JsonObject o = new JsonObject();
        if (profile.getUniqueId() != null) o.addProperty("id", profile.getUniqueId().toString());
        if (profile.getName() != null) o.addProperty("name", profile.getName());
        URL skin = profile.getTextures().getSkin();
        if (skin != null) {
            o.addProperty("skin", skin.toString());
            o.addProperty("model", profile.getTextures().getSkinModel().name());
        }
        return o.toString();
    }

    /** Rebuild a profile from {@link #toJson}. Used by /bvrepair. */
    public static PlayerProfile fromJson(org.bukkit.Server server, JsonObject o) {
        UUID id = o.has("id") ? UUID.fromString(o.get("id").getAsString()) : null;
        String name = o.has("name") ? o.get("name").getAsString() : null;
        PlayerProfile profile = server.createPlayerProfile(id, name);
        if (o.has("skin")) {
            try {
                PlayerTextures tex = profile.getTextures();
                tex.setSkin(new URL(o.get("skin").getAsString()),
                        o.has("model") ? PlayerTextures.SkinModel.valueOf(o.get("model").getAsString())
                                       : PlayerTextures.SkinModel.CLASSIC);
                profile.setTextures(tex);
            } catch (Exception ignored) {
                // fall back to name/id lookup
            }
        }
        return profile;
    }
}
