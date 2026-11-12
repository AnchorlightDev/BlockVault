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

    /** The season has been finished (read-only) and leader bonuses paid. */
    public void seasonFinished(int leaders, int blocksLed, long points) {
        String paid = points > 0
                ? " Paid **" + points + "** bonus points to **" + leaders + "** block leaders across **"
                  + blocksLed + "** blocks."
                : "";
        send("🏁 **The vault is finished.** It is now read-only." + paid
                + " Thanks to everyone who took part!");
    }

    /**
     * The once-a-day standings post. {@code heading} names the scope (e.g. a
     * date), {@code rows} are the top contributors in order. No-op if empty.
     */
    public void dailyLeaderboard(String heading, java.util.List<dev.anchorlight.blockvault.db.Database.LeaderRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        StringBuilder sb = new StringBuilder("🏆 **" + escape(heading) + "**");
        int i = 1;
        for (dev.anchorlight.blockvault.db.Database.LeaderRow r : rows) {
            sb.append('\n').append(medal(i++)).append(' ').append(escape(r.name()))
                    .append(" — ").append(r.points()).append(" pts, ")
                    .append(r.blocks()).append(" blocks");
        }
        send(sb.toString());
    }

    /**
     * The weekly payout summary. {@code bodyLines} are already plain text (no
     * Discord markdown); they are escaped and posted under the heading.
     */
    public void weeklyPayout(String heading, java.util.List<String> bodyLines) {
        StringBuilder sb = new StringBuilder("📅 **" + escape(heading) + "**");
        if (bodyLines != null) {
            for (String line : bodyLines) sb.append('\n').append(escape(line));
        }
        send(sb.toString());
    }

    private static String medal(int rank) {
        return switch (rank) {
            case 1 -> "🥇";
            case 2 -> "🥈";
            case 3 -> "🥉";
            default -> "`#" + rank + "`";
        };
    }

    private void send(String content) {
        String url = url();
        if (url == null) return;
        String body = "{\"content\":\"" + jsonEscape(content) + "\","
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

    /** Escape a string for a JSON string literal (quotes, backslashes, newlines). */
    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
