package dev.anchorlight.blockvault.util;

import dev.anchorlight.blockvault.BlockVault;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fire-and-forget Discord webhook for rare submissions and chapter unlocks.
 * Doubles as an off-server recovery log. No-op when no URL is configured.
 */
public final class Webhook {

    private final BlockVault plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public Webhook(BlockVault plugin) {
        this.plugin = plugin;
    }

    private String url() {
        String u = plugin.getConfig().getString("discord.webhook-url", "");
        return (u == null || u.isBlank()) ? null : u;
    }

    public void rareSubmission(String material, String player, int points) {
        send("💎 **" + escape(player) + "** donated a rare block: `"
                + escape(material) + "` (+" + points + " points)");
    }

    public void chapterOpened(int chapter, String title) {
        send("🔓 **Chapter " + chapter + " — " + escape(title) + "** is now open!");
    }

    private void send(String content) {
        String url = url();
        if (url == null) return;
        String body = "{\"content\":\"" + content.replace("\"", "\\\"") + "\","
                + "\"allowed_mentions\":{\"parse\":[]}}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Discord webhook failed: " + ex.getMessage());
                    return null;
                });
    }

    /** Strip formatting/control characters so display names can't inject into the payload. */
    private static String escape(String s) {
        return s.replaceAll("[\\p{Cntrl}`*_~|\\\\@]", "");
    }
}
