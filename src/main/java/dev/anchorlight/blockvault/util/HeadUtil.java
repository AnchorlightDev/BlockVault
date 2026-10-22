package dev.anchorlight.blockvault.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.util.Objects;
import java.util.UUID;

/** Sets niche/leader head skins and (de)serialises the cached skin profile JSON. */
public final class HeadUtil {

    private HeadUtil() {}

    /**
     * Make the head at {@code loc} wear {@code profile} (null = a plain head).
     * A head that is already there keeps its built orientation and only has its
     * skin changed; a missing head is recreated from {@code wall}/{@code facing}.
     *
     * @return true if anything in the world changed
     */
    public static boolean applyHead(Location loc, boolean wall, BlockFace facing, PlayerProfile profile) {
        Block block = loc.getBlock();
        boolean changed = false;
        Material want = wall ? Material.PLAYER_WALL_HEAD : Material.PLAYER_HEAD;
        if (block.getType() != Material.PLAYER_WALL_HEAD && block.getType() != Material.PLAYER_HEAD) {
            block.setType(want, false);
            var data = block.getBlockData();
            if (data instanceof Directional dir) {
                try { dir.setFacing(facing); } catch (IllegalArgumentException ignored) { }
            } else if (data instanceof Rotatable rot) {
                try { rot.setRotation(facing); } catch (IllegalArgumentException ignored) { }
            }
            block.setBlockData(data, false);
            changed = true;
        }
        if (block.getState() instanceof Skull skull) {
            PlayerProfile have = skull.getOwnerProfile();
            UUID haveId = have == null ? null : have.getUniqueId();
            UUID wantId = profile == null ? null : profile.getUniqueId();
            if (changed || !Objects.equals(haveId, wantId)) {
                skull.setOwnerProfile(profile);
                skull.update(true, false);
                changed = true;
            }
        }
        return changed;
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

    /** Rebuild a profile from {@link #toJson}, or null if the JSON is missing/bad. */
    public static PlayerProfile fromJson(org.bukkit.Server server, String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return fromJson(server, JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            return null;
        }
    }

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
