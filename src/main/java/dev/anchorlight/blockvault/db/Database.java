package dev.anchorlight.blockvault.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.bonus.BlockSnapshot;
import dev.anchorlight.blockvault.bonus.FeaturedPicker;
import dev.anchorlight.blockvault.bonus.PayLine;
import dev.anchorlight.blockvault.bonus.WeeklyBonus;
import dev.anchorlight.blockvault.model.Manifest;
import dev.anchorlight.blockvault.model.TargetEntry;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the connection pool and every SQL statement. All callers run the
 * blocking methods here on the async scheduler - the main thread never enters.
 *
 * <p>The set of already-collected materials is held in memory as the read path
 * and updated only after a write commits.
 */
public final class Database {

    /** MySQL error code for a duplicate primary/unique key. */
    private static final int ER_DUP_ENTRY = 1062;

    private final BlockVault plugin;
    private final String edition;
    private final HikariDataSource ds;
    private final Set<String> collected = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Event open flag (bv_state). Read path is memory; changed only after a write commits. */
    private volatile boolean started;
    /** Terminal read-only flag (bv_state). Once true the vault cannot reopen or accept writes. */
    private volatile boolean finished;

    public enum SubmitOutcome { OK, DB_ERROR }

    /** Whoever has donated the most of one block. */
    public record TopDonor(UUID uuid, String name, long quantity, String profileJson) {}

    /**
     * Result of a donation.
     *
     * @param first        true if this donation unlocked the block
     * @param points       points awarded (rarity points on a first donation, else 0)
     * @param myQuantity   the donor's running total for this block
     * @param top          the block's top donor after this donation
     */
    public record SubmitResult(SubmitOutcome outcome, boolean first, int points,
                               long myQuantity, TopDonor top) {
        static SubmitResult error() { return new SubmitResult(SubmitOutcome.DB_ERROR, false, 0, 0, null); }
    }

    public Database(BlockVault plugin) {
        this.plugin = plugin;
        this.edition = plugin.getConfig().getString("edition", "26.2");

        var cfg = plugin.getConfig();
        String host = cfg.getString("database.host", "localhost");
        int port = cfg.getInt("database.port", 3306);
        String name = cfg.getString("database.name", "blockvault");

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("BlockVault");
        hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + name
                + "?useUnicode=true&characterEncoding=utf8&rewriteBatchedStatements=true");
        hc.setUsername(cfg.getString("database.user", "blockvault"));
        hc.setPassword(cfg.getString("database.password", ""));
        hc.setMaximumPoolSize(cfg.getInt("database.pool.maximum-pool-size", 6));
        hc.setMinimumIdle(cfg.getInt("database.pool.minimum-idle", 1));
        hc.setConnectionTimeout(cfg.getLong("database.pool.connection-timeout-ms", 8000));
        hc.setMaxLifetime(cfg.getLong("database.pool.max-lifetime-ms", 1_740_000));
        hc.setInitializationFailTimeout(-1); // don't crash enable; report on first use

        this.ds = new HikariDataSource(hc);
    }

    public String edition() {
        return edition;
    }

    /** Apply the schema, seed the target list and chapters, warm the collected set. */
    public void bootstrap(Manifest manifest) throws SQLException {
        try (Connection c = ds.getConnection()) {
            runSchema(c);
            migrate(c);
            seedTargets(c, manifest);
            seedChapters(c, manifest);
            syncChapterDates(c);
            warmCollected(c);
            loadState(c);
        }
        plugin.getLogger().info("Database ready: " + collected.size()
                + " blocks already collected for edition " + edition + ".");
    }

    private void runSchema(Connection c) throws SQLException {
        StringBuilder sql = new StringBuilder();
        try (InputStream in = plugin.getResource("blockvault_schema.sql")) {
            if (in == null) throw new SQLException("blockvault_schema.sql missing from jar");
            sql.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new SQLException("cannot read blockvault_schema.sql", e);
        }
        try (Statement st = c.createStatement()) {
            for (String raw : sql.toString().split(";\\s*\\r?\\n")) {
                String stmt = stripComments(raw).trim();
                if (!stmt.isEmpty()) st.execute(stmt);
            }
        }
    }

    /**
     * In-place upgrades for databases created before a column existed.
     * {@code CREATE TABLE IF NOT EXISTS} never alters an existing table, so each
     * added column is applied here, guarded by information_schema so it is safe
     * to run on every boot. MySQL 8 has no {@code ADD COLUMN IF NOT EXISTS}.
     */
    private void migrate(Connection c) throws SQLException {
        ensureColumn(c, "bv_state", "finished", "TINYINT(1) NOT NULL DEFAULT 0");
        ensureColumn(c, "bv_state", "finished_at", "DATETIME NULL");
        // 1.3.0 dev builds created the log without the staff-edit methods.
        ensureEnumValue(c, "bv_contribution_log", "method", "adjust",
                "ENUM('chest','hand','new','adjust','remove') NOT NULL");
    }

    /** Widen an ENUM column if {@code value} isn't one of its values yet. */
    private void ensureEnumValue(Connection c, String table, String column, String value,
                                 String definition) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT column_type FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getString(1).contains("'" + value + "'")) return;
            }
        }
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " " + definition);
        }
        plugin.getLogger().info("Migrated " + table + "." + column + ": added '" + value + "'.");
    }

    private void ensureColumn(Connection c, String table, String column, String definition) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return; // already present
            }
        }
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
        plugin.getLogger().info("Migrated " + table + ": added column " + column + ".");
    }

    private static String stripComments(String block) {
        StringBuilder out = new StringBuilder();
        for (String line : block.split("\\r?\\n")) {
            if (line.stripLeading().startsWith("--")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private void seedTargets(Connection c, Manifest manifest) throws SQLException {
        if (editionRowCount(c, "bv_target") > 0) {
            // Until the first donation the layout may still change (/bvsetup), so
            // re-seed from the current manifest. After that it is frozen.
            if (editionRowCount(c, "bv_submission") > 0 || editionRowCount(c, "bv_donation") > 0) return;
            try (PreparedStatement del = c.prepareStatement("DELETE FROM bv_target WHERE edition = ?")) {
                del.setString(1, edition);
                del.executeUpdate();
            }
        }
        String q = "INSERT INTO bv_target (material,edition,chapter,rarity,section,"
                + "head_x,head_y,head_z,facing) VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(q)) {
            for (TargetEntry e : manifest.entries().values()) {
                ps.setString(1, e.material());
                ps.setString(2, edition);
                ps.setInt(3, e.chapter());
                ps.setString(4, e.rarity());
                ps.setString(5, e.section());
                ps.setInt(6, e.head()[0]); ps.setInt(7, e.head()[1]); ps.setInt(8, e.head()[2]);
                ps.setString(9, e.facing());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        plugin.getLogger().info("Seeded " + manifest.entries().size()
                + " target rows for edition " + edition + ".");
    }

    private void seedChapters(Connection c, Manifest manifest) throws SQLException {
        if (editionRowCount(c, "bv_chapter") > 0) {
            // Seals can be (re)recorded with /bvsetup seal until the floor opens.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE bv_chapter SET seal_x = ?, seal_y = ?, seal_z = ? "
                    + "WHERE chapter = ? AND edition = ? AND opened_at IS NULL")) {
                for (int ch = 2; ch <= 6; ch++) {
                    int[] seal = manifest.seal(ch);
                    if (seal == null) continue;
                    ps.setInt(1, seal[0]); ps.setInt(2, seal[1]); ps.setInt(3, seal[2]);
                    ps.setInt(4, ch);
                    ps.setString(5, edition);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return;
        }
        String[][] meta = {
                {"1", "Foundations", "The Undercroft"},
                {"2", "Green & Growing", "The Conservatory"},
                {"3", "Into the Deep", "The Deep"},
                {"4", "Every Colour", "The Gallery"},
                {"5", "The Nether", "The Forge"},
                {"6", "The End", "The Observatory"},
        };
        String q = "INSERT INTO bv_chapter (chapter,edition,title,room,seal_x,seal_y,seal_z) "
                + "VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(q)) {
            for (String[] m : meta) {
                int ch = Integer.parseInt(m[0]);
                int[] seal = manifest.seal(ch); // null for chapter 1
                ps.setInt(1, ch);
                ps.setString(2, edition);
                ps.setString(3, m[1]);
                ps.setString(4, m[2]);
                if (seal == null) {
                    ps.setNull(5, java.sql.Types.INTEGER);
                    ps.setNull(6, java.sql.Types.INTEGER);
                    ps.setNull(7, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(5, seal[0]); ps.setInt(6, seal[1]); ps.setInt(7, seal[2]);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Push the configured open dates onto any chapter that has not opened yet.
     * Runs every start so an admin can move a date mid-season by editing config.
     */
    /** Public re-sync for /bvreload. Blocking - call async. */
    public void resyncChapterDates() {
        try (Connection c = ds.getConnection()) {
            syncChapterDates(c);
        } catch (SQLException e) {
            plugin.getLogger().severe("Chapter date re-sync failed: " + e.getMessage());
        }
    }

    private void syncChapterDates(Connection c) throws SQLException {
        var section = plugin.getConfig().getConfigurationSection("chapters");
        if (section == null) return;
        java.time.ZoneId zone = plugin.zone();
        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm z", java.util.Locale.ENGLISH);
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE bv_chapter SET opens_at = ? "
                + "WHERE chapter = ? AND edition = ? AND opened_at IS NULL")) {
            for (String key : section.getKeys(false)) {
                String raw = section.getString(key + ".opens-at");
                if (raw == null || raw.isBlank()) continue;
                java.sql.Timestamp ts;
                try {
                    // Wall-clock time in the configured zone -> an exact instant, so the
                    // drop happens at that local time whatever zone the host runs in.
                    var when = java.time.LocalDateTime.parse(raw).atZone(zone);
                    ts = java.sql.Timestamp.from(when.toInstant());
                    plugin.getLogger().info("Floor " + key + (key.equals("1") ? " (opens with /bvstart) date: " : " drops ")
                            + fmt.format(when) + " (" + zone.getId() + ")");
                } catch (Exception e) {
                    plugin.getLogger().warning("chapters." + key
                            + ".opens-at is not a valid ISO date-time: " + raw);
                    continue;
                }
                ps.setTimestamp(1, ts);
                ps.setInt(2, Integer.parseInt(key));
                ps.setString(3, edition);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void warmCollected(Connection c) throws SQLException {
        collected.clear();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT material FROM bv_submission WHERE edition = ?")) {
            ps.setString(1, edition);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) collected.add(rs.getString(1));
            }
        }
    }

    /** Row count for the current edition. Table must have an {@code edition} column. */
    private int editionRowCount(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE edition = ?")) {
            ps.setString(1, edition);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ----------------------------------------------------------------- reads

    /** In-memory read path. Never hits the database. */
    public boolean isCollected(String material) {
        return collected.contains(material);
    }

    public Set<String> collectedSnapshot() {
        return new HashSet<>(collected);
    }

    public int collectedCount() {
        return collected.size();
    }

    /** Every material recorded in bv_target for this edition. Blocking. */
    public java.util.Set<String> targetMaterials() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT material FROM bv_target WHERE edition = ?")) {
            ps.setString(1, edition);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Target material query failed: " + e.getMessage());
        }
        return out;
    }

    /** Ranking used for every "most donated" question: quantity, then who got there first. */
    private static final String TOP_ORDER = "d.quantity DESC, d.last_at ASC, d.uuid ASC";

    /**
     * material -> its top donor, for every donated block in this edition.
     * Returns null (not an empty map) if the query failed, so callers can tell
     * "nobody yet" from "database down" and leave heads alone. Blocking.
     */
    public java.util.Map<String, TopDonor> topDonors() {
        java.util.Map<String, TopDonor> out = new java.util.HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT material, uuid, last_name, quantity, profile FROM ("
                     + " SELECT d.material, d.uuid, p.last_name, d.quantity, p.profile,"
                     + "  ROW_NUMBER() OVER (PARTITION BY d.material ORDER BY " + TOP_ORDER + ") AS rn"
                     + " FROM bv_donation d JOIN bv_contributor p ON p.uuid = d.uuid"
                     + " WHERE d.edition = ? AND d.quantity > 0) ranked WHERE rn = 1")) {
            ps.setString(1, edition);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), new TopDonor(fromBytes(rs.getBytes(2)),
                            rs.getString(3), rs.getLong(4), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load top donors: " + e.getMessage());
            return null;
        }
        return out;
    }

    /** The top {@code limit} donors of one block. Blocking. */
    public java.util.List<TopDonor> topDonorsOf(String material, int limit) {
        java.util.List<TopDonor> out = new java.util.ArrayList<>();
        try (Connection c = ds.getConnection()) {
            topDonorsOf(c, material, limit, out);
        } catch (SQLException e) {
            plugin.getLogger().severe("Donor lookup failed: " + e.getMessage());
        }
        return out;
    }

    private void topDonorsOf(Connection c, String material, int limit,
                             java.util.List<TopDonor> out) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT d.uuid, p.last_name, d.quantity, p.profile "
                + "FROM bv_donation d JOIN bv_contributor p ON p.uuid = d.uuid "
                + "WHERE d.material = ? AND d.edition = ? AND d.quantity > 0 "
                + "ORDER BY " + TOP_ORDER + " LIMIT ?")) {
            ps.setString(1, material);
            ps.setString(2, edition);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TopDonor(fromBytes(rs.getBytes(1)), rs.getString(2),
                            rs.getLong(3), rs.getString(4)));
                }
            }
        }
    }

    /**
     * The top {@code limit} donors on one floor, by total items given to that
     * floor's blocks. Feeds the standing leader heads. Null on failure. Blocking.
     */
    public java.util.List<TopDonor> floorLeaders(int chapter, int limit) {
        java.util.List<TopDonor> out = new java.util.ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT d.uuid, p.last_name, SUM(d.quantity) AS items, "
                     + "(SELECT x.profile FROM bv_contributor x WHERE x.uuid = d.uuid) AS profile, "
                     + "MAX(d.last_at) AS latest "
                     + "FROM bv_donation d "
                     + "JOIN bv_target t ON t.material = d.material AND t.edition = d.edition "
                     + "JOIN bv_contributor p ON p.uuid = d.uuid "
                     + "WHERE d.edition = ? AND t.chapter = ? AND d.quantity > 0 "
                     + "GROUP BY d.uuid, p.last_name "
                     + "ORDER BY items DESC, latest ASC LIMIT ?")) {
            ps.setString(1, edition);
            ps.setInt(2, chapter);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TopDonor(fromBytes(rs.getBytes(1)), rs.getString(2),
                            rs.getLong(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Floor leader query failed: " + e.getMessage());
            return null;
        }
        return out;
    }

    // ----------------------------------------------------------------- writes

    /**
     * Record a donation of {@code quantity} items. Blocking - call on the async
     * scheduler. The items must NOT be consumed until this returns
     * {@link SubmitOutcome#OK}.
     *
     * <p>The first donation of a block inserts its bv_submission row (the
     * primary key decides who was first, so simultaneous donors race safely) and
     * earns {@code firstPoints}. Every donation, first or repeat, adds to the
     * donor's bv_donation tally, which decides who wears the niche head.
     *
     * @param profileJson    cached skin profile, or null
     * @param method         how it was submitted ('chest', 'hand' or 'new'), for the
     *                       monitoring log
     * @param featuredDouble true if this block is featured this week, doubling the
     *                       first-contributor points on an unlock
     */
    public SubmitResult submit(String material, UUID uuid, String name, int quantity,
                               int firstPoints, String profileJson, String method,
                               boolean featuredDouble) {
        byte[] id = toBytes(uuid);
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bv_contributor (uuid,last_name,profile) VALUES (?,?,?) "
                        + "ON DUPLICATE KEY UPDATE last_name=VALUES(last_name), "
                        + "profile=COALESCE(VALUES(profile), profile), last_seen=CURRENT_TIMESTAMP")) {
                    ps.setBytes(1, id);
                    ps.setString(2, name);
                    if (profileJson == null) ps.setNull(3, java.sql.Types.VARCHAR);
                    else ps.setString(3, profileJson);
                    ps.executeUpdate();
                }

                // Featured blocks pay double on the unlock. Stored on the submission
                // row as paid, so /bvrevoke refunds exactly what was given.
                int unlockPoints = featuredDouble ? firstPoints * 2 : firstPoints;
                boolean first = true;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bv_submission (material,edition,uuid,points,profile) "
                        + "VALUES (?,?,?,?,?)")) {
                    ps.setString(1, material);
                    ps.setString(2, edition);
                    ps.setBytes(3, id);
                    ps.setInt(4, unlockPoints);
                    if (profileJson == null) ps.setNull(5, java.sql.Types.VARCHAR);
                    else ps.setString(5, profileJson);
                    ps.executeUpdate();
                } catch (SQLException dup) {
                    // Already unlocked by someone: a failed statement does not end
                    // the InnoDB transaction, so carry on as a repeat donation.
                    if (dup.getErrorCode() != ER_DUP_ENTRY) throw dup;
                    first = false;
                }
                int points = first ? unlockPoints : 0;

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bv_donation (material,edition,uuid,quantity,last_at) "
                        + "VALUES (?,?,?,?,CURRENT_TIMESTAMP(3)) "
                        + "ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity), "
                        + "last_at = CURRENT_TIMESTAMP(3)")) {
                    ps.setString(1, material);
                    ps.setString(2, edition);
                    ps.setBytes(3, id);
                    ps.setInt(4, quantity);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE bv_contributor SET points = points + ?, "
                        + "blocks_given = blocks_given + ?, items_given = items_given + ? "
                        + "WHERE uuid = ?")) {
                    ps.setInt(1, points);
                    ps.setInt(2, first ? 1 : 0);
                    ps.setInt(3, quantity);
                    ps.setBytes(4, id);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bv_audit (actor,action,material,detail) "
                        + "VALUES (?,?,?,JSON_OBJECT('points',?,'quantity',?))")) {
                    ps.setBytes(1, id);
                    ps.setString(2, first ? "submit" : "donate");
                    ps.setString(3, material);
                    ps.setInt(4, points);
                    ps.setInt(5, quantity);
                    ps.executeUpdate();
                }

                // Append-only monitoring log of every contribution (same transaction,
                // so the log can never disagree with the tally). No-op if disabled.
                if (plugin.getConfig().getBoolean("monitoring.enabled", true)) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO bv_contribution_log (edition,uuid,material,amount,method,first_unlock) "
                            + "VALUES (?,?,?,?,?,?)")) {
                        ps.setString(1, edition);
                        ps.setBytes(2, id);
                        ps.setString(3, material);
                        ps.setInt(4, quantity);
                        ps.setString(5, normaliseMethod(method));
                        ps.setBoolean(6, first);
                        ps.executeUpdate();
                    }
                }

                long mine = 0;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT quantity FROM bv_donation WHERE material = ? AND edition = ? AND uuid = ?")) {
                    ps.setString(1, material);
                    ps.setString(2, edition);
                    ps.setBytes(3, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) mine = rs.getLong(1);
                    }
                }
                java.util.List<TopDonor> top = new java.util.ArrayList<>(1);
                topDonorsOf(c, material, 1, top);

                c.commit();
                collected.add(material);
                return new SubmitResult(SubmitOutcome.OK, first, points, mine,
                        top.isEmpty() ? null : top.get(0));
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Donation write failed for " + material
                    + " by " + name + ": " + e.getMessage());
            return SubmitResult.error();
        }
    }

    // ----------------------------------------------------------------- chapters

    public record ChapterRow(int chapter, String title, String room,
                             Integer sealX, Integer sealY, Integer sealZ,
                             java.sql.Timestamp opensAt, java.sql.Timestamp openedAt) {}

    /** All chapter rows for this edition, ordered. Blocking. */
    public java.util.List<ChapterRow> chapters() {
        java.util.List<ChapterRow> rows = new java.util.ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT chapter,title,room,seal_x,seal_y,seal_z,opens_at,opened_at "
                     + "FROM bv_chapter WHERE edition = ? ORDER BY chapter")) {
            ps.setString(1, edition);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ChapterRow(
                            rs.getInt("chapter"), rs.getString("title"), rs.getString("room"),
                            (Integer) rs.getObject("seal_x"),
                            (Integer) rs.getObject("seal_y"),
                            (Integer) rs.getObject("seal_z"),
                            rs.getTimestamp("opens_at"), rs.getTimestamp("opened_at")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Chapter query failed: " + e.getMessage());
        }
        return rows;
    }

    /** Stamp a chapter complete (only if not already). Blocking. Returns true if it changed. */
    public boolean markChapterComplete(int chapter, UUID by) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE bv_chapter SET completed_at = CURRENT_TIMESTAMP, completed_by = ? "
                     + "WHERE chapter = ? AND edition = ? AND completed_at IS NULL")) {
            if (by == null) ps.setNull(1, java.sql.Types.BINARY);
            else ps.setBytes(1, toBytes(by));
            ps.setInt(2, chapter);
            ps.setString(3, edition);
            boolean changed = ps.executeUpdate() > 0;
            if (changed) audit(by, "chapter_complete", null, "{\"chapter\":" + chapter + "}");
            return changed;
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not mark chapter " + chapter + " complete: " + e.getMessage());
            return false;
        }
    }

    /** Chapters with a completed_at stamp for this edition. Blocking. */
    public java.util.Set<Integer> completedChapters() {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT chapter FROM bv_chapter WHERE edition = ? AND completed_at IS NOT NULL")) {
            ps.setString(1, edition);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Completed-chapter query failed: " + e.getMessage());
        }
        return out;
    }

    /** Top contributors for a given calendar month (YYYY-MM). Blocking. */
    public java.util.List<LeaderRow> monthlyTop(String yearMonth, int limit) {
        java.util.List<LeaderRow> rows = new java.util.ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT c.uuid, c.last_name, SUM(s.points) AS pts, COUNT(*) AS blocks "
                     + "FROM bv_submission s JOIN bv_contributor c ON c.uuid = s.uuid "
                     + "WHERE s.edition = ? AND DATE_FORMAT(s.submitted_at, '%Y-%m') = ? "
                     + "GROUP BY c.uuid, c.last_name ORDER BY pts DESC, blocks DESC LIMIT ?")) {
            ps.setString(1, edition);
            ps.setString(2, yearMonth);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new LeaderRow(fromBytes(rs.getBytes(1)), rs.getString(2),
                            rs.getLong(3), rs.getLong(4)));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Monthly leaderboard query failed: " + e.getMessage());
        }
        return rows;
    }

    /** Stamp a chapter open (only if not already). Blocking. Returns true if it changed. */
    public boolean markChapterOpened(int chapter) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE bv_chapter SET opened_at = CURRENT_TIMESTAMP "
                     + "WHERE chapter = ? AND edition = ? AND opened_at IS NULL")) {
            ps.setInt(1, chapter);
            ps.setString(2, edition);
            boolean changed = ps.executeUpdate() > 0;
            if (changed) audit(null, "chapter_open", null, "{\"chapter\":" + chapter + "}");
            return changed;
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not mark chapter " + chapter + " open: " + e.getMessage());
            return false;
        }
    }

    /** Append an audit row. Blocking; {@code detailJson} must be valid JSON or null. */
    public void audit(UUID actor, String action, String material, String detailJson) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO bv_audit (actor,action,material,detail) VALUES (?,?,?,?)")) {
            if (actor == null) ps.setNull(1, java.sql.Types.BINARY);
            else ps.setBytes(1, toBytes(actor));
            ps.setString(2, action);
            ps.setString(3, material);
            ps.setString(4, detailJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Audit write failed (" + action + "): " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- event state

    private void loadState(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT started, finished FROM bv_state WHERE edition = ?")) {
            ps.setString(1, edition);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    started = rs.getBoolean(1);
                    finished = rs.getBoolean(2);
                    if (finished) plugin.getLogger().info("Vault edition " + edition
                            + " is FINISHED - running read-only.");
                    return;
                }
            }
        }
        // First boot with bv_state: carry over the old config.yml flag once.
        boolean legacy = plugin.getConfig().getBoolean("vault.started", false);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO bv_state (edition, started, started_at) "
                + "VALUES (?, ?, IF(?, CURRENT_TIMESTAMP, NULL))")) {
            ps.setString(1, edition);
            ps.setBoolean(2, legacy);
            ps.setBoolean(3, legacy);
            ps.executeUpdate();
        }
        started = legacy;
        if (legacy) plugin.getLogger().info("Carried vault.started=true over from config.yml into bv_state.");
    }

    /** In-memory read path. Never hits the database. */
    public boolean isStarted() {
        return started;
    }

    /** In-memory read path. True once the season has been finished (read-only). */
    public boolean isFinished() {
        return finished;
    }

    /** Outcome of {@link #finish}. */
    public record FinishResult(boolean ok, boolean alreadyFinished,
                               int blocksLed, int leaders, long pointsAwarded) {
        static FinishResult error() { return new FinishResult(false, false, 0, 0, 0); }
    }

    /**
     * End the season: mark the vault finished (terminal, read-only) and pay each
     * block's current leader a flat {@code bonusPerBlock} points, once. All in one
     * transaction, with the bv_state row locked, so a double {@code /bvfinish}
     * (or a race) can never pay the bonus twice. Blocking - call async.
     *
     * <p>"Leader" is the top of {@link #TOP_ORDER} for a block (most given, then
     * who reached it first). A player leading N blocks earns {@code N * bonusPerBlock}.
     */
    public FinishResult finish(UUID actor, int bonusPerBlock) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Lock the state row so concurrent finishes serialise on it.
                boolean already = false;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT finished FROM bv_state WHERE edition = ? FOR UPDATE")) {
                    ps.setString(1, edition);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) already = rs.getBoolean(1);
                    }
                }
                if (already) {
                    c.rollback();
                    finished = true;
                    return new FinishResult(true, true, 0, 0, 0);
                }

                int blocksLed = 0, leaders = 0;
                long pointsAwarded = 0;
                if (bonusPerBlock > 0) {
                    // Blocks each player leads, then award bonusPerBlock for each.
                    java.util.Map<byte[], Integer> leads = new java.util.LinkedHashMap<>();
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT r.uuid, COUNT(*) AS leads FROM ("
                            + " SELECT d.uuid, ROW_NUMBER() OVER "
                            + "  (PARTITION BY d.material ORDER BY " + TOP_ORDER + ") AS rn"
                            + " FROM bv_donation d WHERE d.edition = ? AND d.quantity > 0) r"
                            + " WHERE r.rn = 1 GROUP BY r.uuid")) {
                        ps.setString(1, edition);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) leads.put(rs.getBytes(1), rs.getInt(2));
                        }
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE bv_contributor SET points = points + ? WHERE uuid = ?")) {
                        for (var e : leads.entrySet()) {
                            int award = e.getValue() * bonusPerBlock;
                            ps.setInt(1, award);
                            ps.setBytes(2, e.getKey());
                            ps.addBatch();
                            leaders++;
                            blocksLed += e.getValue();
                            pointsAwarded += award;
                        }
                        ps.executeBatch();
                    }
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bv_state (edition, started, started_at, finished, finished_at) "
                        + "VALUES (?, 0, started_at, 1, CURRENT_TIMESTAMP) "
                        + "ON DUPLICATE KEY UPDATE started = 0, finished = 1, "
                        + "finished_at = CURRENT_TIMESTAMP")) {
                    ps.setString(1, edition);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bv_audit (actor,action,detail) "
                        + "VALUES (?,?,JSON_OBJECT('bonusPerBlock',?,'blocksLed',?,'leaders',?,'points',?))")) {
                    if (actor == null) ps.setNull(1, java.sql.Types.BINARY);
                    else ps.setBytes(1, toBytes(actor));
                    ps.setString(2, "event_finish");
                    ps.setInt(3, bonusPerBlock);
                    ps.setInt(4, blocksLed);
                    ps.setInt(5, leaders);
                    ps.setLong(6, pointsAwarded);
                    ps.executeUpdate();
                }

                c.commit();
                started = false;
                finished = true;
                return new FinishResult(true, false, blocksLed, leaders, pointsAwarded);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Finish failed: " + e.getMessage());
            return FinishResult.error();
        }
    }

    /**
     * Open or close the event. Blocking - call async. The in-memory flag only
     * changes once the write has committed.
     *
     * @return false if the write failed (nothing changed)
     */
    public boolean setStarted(boolean value, UUID actor) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO bv_state (edition, started, started_at) "
                     + "VALUES (?, ?, IF(?, CURRENT_TIMESTAMP, NULL)) "
                     + "ON DUPLICATE KEY UPDATE started = ?, "
                     + "started_at = IF(?, CURRENT_TIMESTAMP, started_at)")) {
            ps.setString(1, edition);
            ps.setBoolean(2, value);
            ps.setBoolean(3, value);
            ps.setBoolean(4, value);
            ps.setBoolean(5, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not " + (value ? "open" : "close")
                    + " the vault: " + e.getMessage());
            return false;
        }
        started = value;
        audit(actor, value ? "event_open" : "event_close", null, null);
        return true;
    }

    // ----------------------------------------------------------------- lookups

    public record SubmissionRow(String material, UUID uuid, String name,
                                int points, java.sql.Timestamp submittedAt) {}

    /** Who donated {@code material} and when, or null if still outstanding. Blocking. */
    public SubmissionRow submission(String material) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT s.material, s.uuid, c.last_name, s.points, s.submitted_at "
                     + "FROM bv_submission s JOIN bv_contributor c ON c.uuid = s.uuid "
                     + "WHERE s.material = ? AND s.edition = ?")) {
            ps.setString(1, material);
            ps.setString(2, edition);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new SubmissionRow(rs.getString(1), fromBytes(rs.getBytes(2)),
                        rs.getString(3), rs.getInt(4), rs.getTimestamp(5));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Submission lookup failed: " + e.getMessage());
            return null;
        }
    }

    public record MyStats(long points, long blocks, long items, int rank) {}

    /** Personal totals for {@code uuid}. Blocking. */
    public MyStats myStats(UUID uuid) {
        long points = 0, blocks = 0, items = 0;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT points, blocks_given, items_given FROM bv_contributor WHERE uuid = ?")) {
            ps.setBytes(1, toBytes(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { points = rs.getLong(1); blocks = rs.getLong(2); items = rs.getLong(3); }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Stats query failed: " + e.getMessage());
        }
        return new MyStats(points, blocks, items, rankOf(uuid));
    }

    // ----------------------------------------------------------------- admin

    public record RevokeResult(boolean ok, UUID donor, int pointsRefunded) {}

    /**
     * Reverse a block entirely: delete its unlock and every donation tally,
     * refund the first donor's points and take the items back off every
     * donor's total, audit. Blocking.
     */
    public RevokeResult revoke(String material, UUID actor) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID donor;
                int points;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, points FROM bv_submission WHERE material = ? AND edition = ?")) {
                    ps.setString(1, material);
                    ps.setString(2, edition);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return new RevokeResult(false, null, 0); }
                        donor = fromBytes(rs.getBytes(1));
                        points = rs.getInt(2);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE bv_contributor p JOIN bv_donation d ON d.uuid = p.uuid "
                        + "SET p.items_given = GREATEST(0, CAST(p.items_given AS SIGNED) - d.quantity) "
                        + "WHERE d.material = ? AND d.edition = ?")) {
                    ps.setString(1, material);
                    ps.setString(2, edition);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM bv_donation WHERE material = ? AND edition = ?")) {
                    ps.setString(1, material);
                    ps.setString(2, edition);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM bv_submission WHERE material = ? AND edition = ?")) {
                    ps.setString(1, material);
                    ps.setString(2, edition);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE bv_contributor SET points = GREATEST(0, CAST(points AS SIGNED) - ?), "
                        + "blocks_given = GREATEST(0, CAST(blocks_given AS SIGNED) - 1) WHERE uuid = ?")) {
                    ps.setInt(1, points);
                    ps.setBytes(2, toBytes(donor));
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bv_audit (actor,action,material,detail) VALUES (?,?,?,?)")) {
                    if (actor == null) ps.setNull(1, java.sql.Types.BINARY); // console
                    else ps.setBytes(1, toBytes(actor));
                    ps.setString(2, "revoke");
                    ps.setString(3, material);
                    ps.setString(4, "{\"points\":" + points + "}");
                    ps.executeUpdate();
                }
                c.commit();
                collected.remove(material);
                return new RevokeResult(true, donor, points);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Revoke failed for " + material + ": " + e.getMessage());
            return new RevokeResult(false, null, 0);
        }
    }

    public record LeaderRow(UUID uuid, String name, long points, long blocks) {}

    /** Top {@code limit} contributors, then the viewer's own row if outside it. Blocking. */
    public java.util.List<LeaderRow> topContributors(int limit) {
        java.util.List<LeaderRow> rows = new java.util.ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid, last_name, points, blocks FROM bv_leaderboard LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new LeaderRow(fromBytes(rs.getBytes(1)), rs.getString(2),
                            rs.getLong(3), rs.getLong(4)));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Leaderboard query failed: " + e.getMessage());
        }
        return rows;
    }

    /** 1-based rank of {@code uuid} on the leaderboard, or -1 if they have no row. Blocking. */
    public int rankOf(UUID uuid) {
        byte[] id = toBytes(uuid);
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT points FROM bv_contributor WHERE uuid = ?")) {
                ps.setBytes(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return -1; // never contributed
                    long myPoints = rs.getLong(1);
                    if (myPoints <= 0) return -1; // not on the leaderboard yet
                    try (PreparedStatement r = c.prepareStatement(
                            "SELECT COUNT(*) + 1 FROM bv_contributor WHERE points > ?")) {
                        r.setLong(1, myPoints);
                        try (ResultSet rr = r.executeQuery()) {
                            return rr.next() ? rr.getInt(1) : -1;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Rank query failed: " + e.getMessage());
            return -1;
        }
    }

    // ----------------------------------------------------------------- weekly payout

    /** One paid line, with the player's name resolved for announcements. */
    public record PayoutLine(UUID uuid, String name, String material, int points,
                             boolean featured, long margin) {}

    /**
     * Result of a weekly payout. {@code alreadyRan} means this week was already
     * paid (the run is idempotent). {@code featuredChosen} is next week's featured
     * block(s). {@code lines} drive the in-game and Discord announcements.
     */
    public record PayoutResult(boolean ok, boolean alreadyRan, String weekKey,
                               java.util.List<PayoutLine> lines, java.util.List<String> featuredChosen,
                               java.util.Set<UUID> cappedPlayers, int totalPoints, int players) {
        static PayoutResult error() {
            return new PayoutResult(false, false, null, java.util.List.of(), java.util.List.of(),
                    java.util.Set.of(), 0, 0);
        }
        static PayoutResult already(String weekKey) {
            return new PayoutResult(true, true, weekKey, java.util.List.of(), java.util.List.of(),
                    java.util.Set.of(), 0, 0);
        }
    }

    /**
     * Run the weekly top-contributor payout for {@code weekKey}, then rotate the
     * featured block into {@code nextWeekKey}. One transaction, serialised on the
     * bv_state row like {@link #finish}. A row in bv_payout_run for {@code weekKey}
     * means this week was already paid, so a restart or a double trigger is a
     * no-op - the payout is never applied twice. Blocking - call async.
     *
     * @param openChapters the unlocked floors (from {@link dev.anchorlight.blockvault.chapter.ChapterService},
     *                     not the database, so chapter 1 is included)
     * @param rng          featured-pick randomness; injected for tests
     */
    public PayoutResult runWeeklyPayout(String weekKey, String nextWeekKey, Set<Integer> openChapters,
                                        int pointsPerBlock, int capBlocks, int featuredPoints,
                                        int featuredCount, Random rng) {
        if (openChapters == null || openChapters.isEmpty()) {
            return new PayoutResult(true, false, weekKey, java.util.List.of(), java.util.List.of(),
                    java.util.Set.of(), 0, 0);
        }
        String inClause = intInClause(openChapters);
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Serialise payouts on the state row, like finish().
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT started FROM bv_state WHERE edition = ? FOR UPDATE")) {
                    ps.setString(1, edition);
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); }
                }
                // Idempotency: has this week already been paid?
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM bv_payout_run WHERE edition = ? AND week_key = ?")) {
                    ps.setString(1, edition);
                    ps.setString(2, weekKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) { c.rollback(); return PayoutResult.already(weekKey); }
                    }
                }

                // Featured block(s) active over the week being paid = the most
                // recently set batch (by set_at), read before we rotate below.
                Set<String> featuredNow = new HashSet<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT material FROM bv_featured WHERE edition = ? AND set_at = "
                        + "(SELECT MAX(set_at) FROM bv_featured WHERE edition = ?)")) {
                    ps.setString(1, edition);
                    ps.setString(2, edition);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) featuredNow.add(rs.getString(1));
                    }
                }

                // Snapshot: each unlocked block's top contributor + second place's quantity.
                List<BlockSnapshot> snaps = new ArrayList<>();
                Map<UUID, String> names = new LinkedHashMap<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT material, uuid, last_name, quantity, second_qty FROM ("
                        + " SELECT d.material, d.uuid, p.last_name, d.quantity,"
                        + "  ROW_NUMBER() OVER (PARTITION BY d.material ORDER BY " + TOP_ORDER + ") AS rn,"
                        + "  LEAD(d.quantity) OVER (PARTITION BY d.material ORDER BY " + TOP_ORDER + ") AS second_qty"
                        + " FROM bv_donation d"
                        + " JOIN bv_target t ON t.material = d.material AND t.edition = d.edition"
                        + " JOIN bv_contributor p ON p.uuid = d.uuid"
                        + " WHERE d.edition = ? AND d.quantity > 0 AND t.chapter IN " + inClause
                        + ") ranked WHERE rn = 1")) {
                    ps.setString(1, edition);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String material = rs.getString(1);
                            UUID uuid = fromBytes(rs.getBytes(2));
                            names.put(uuid, rs.getString(3));
                            long qty = rs.getLong(4);
                            long second = rs.getLong(5); // 0 when SQL NULL (single donor)
                            snaps.add(new BlockSnapshot(material, uuid, qty, second, featuredNow.contains(material)));
                        }
                    }
                }

                WeeklyBonus.Result awarded =
                        WeeklyBonus.award(snaps, pointsPerBlock, featuredPoints, capBlocks);

                Map<UUID, Integer> perPlayer = new LinkedHashMap<>();
                for (PayLine l : awarded.lines()) perPlayer.merge(l.uuid(), l.points(), Integer::sum);

                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE bv_contributor SET points = points + ? WHERE uuid = ?")) {
                    for (var e : perPlayer.entrySet()) {
                        ps.setInt(1, e.getValue());
                        ps.setBytes(2, toBytes(e.getKey()));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                List<PayoutLine> lines = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bv_weekly_payout (edition,week_key,uuid,material,points,featured,lead_margin) "
                        + "VALUES (?,?,?,?,?,?,?)")) {
                    for (PayLine l : awarded.lines()) {
                        ps.setString(1, edition);
                        ps.setString(2, weekKey);
                        ps.setBytes(3, toBytes(l.uuid()));
                        ps.setString(4, l.material());
                        ps.setInt(5, l.points());
                        ps.setBoolean(6, l.featured());
                        ps.setLong(7, l.margin());
                        ps.addBatch();
                        lines.add(new PayoutLine(l.uuid(), names.get(l.uuid()), l.material(),
                                l.points(), l.featured(), l.margin()));
                    }
                    ps.executeBatch();
                }

                int totalPoints = perPlayer.values().stream().mapToInt(Integer::intValue).sum();
                int players = perPlayer.size();

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bv_payout_run (edition,week_key,total_points,players) VALUES (?,?,?,?)")) {
                    ps.setString(1, edition);
                    ps.setString(2, weekKey);
                    ps.setInt(3, totalPoints);
                    ps.setInt(4, players);
                    ps.executeUpdate();
                }

                // Rotate: next week's featured block(s), favouring the least contributed,
                // excluding this week's pick so it actually changes.
                List<String> nextFeatured =
                        pickFeatured(c, inClause, featuredNow, featuredCount, rng);
                if (!nextFeatured.isEmpty()) {
                    // One shared set_at so the whole pick is a single "set" and
                    // becomes current the moment this transaction commits.
                    java.sql.Timestamp setAt = new java.sql.Timestamp(System.currentTimeMillis());
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO bv_featured (edition,week_key,material,set_at,set_by) "
                            + "VALUES (?,?,?,?,NULL)")) {
                        for (String m : nextFeatured) {
                            ps.setString(1, edition);
                            ps.setString(2, nextWeekKey);
                            ps.setString(3, m);
                            ps.setTimestamp(4, setAt);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bv_audit (actor,action,detail) "
                        + "VALUES (NULL,'weekly_payout',JSON_OBJECT('week',?,'points',?,'players',?))")) {
                    ps.setString(1, weekKey);
                    ps.setInt(2, totalPoints);
                    ps.setInt(3, players);
                    ps.executeUpdate();
                }

                c.commit();
                return new PayoutResult(true, false, weekKey, lines, nextFeatured,
                        awarded.cappedPlayers(), totalPoints, players);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Weekly payout failed for " + weekKey + ": " + e.getMessage());
            return PayoutResult.error();
        }
    }

    /** Candidate featured blocks on the open floors, weighted by donor count. Same connection/transaction. */
    private List<String> pickFeatured(Connection c, String inClause, Set<String> exclude,
                                      int count, Random rng) throws SQLException {
        if (count < 1) return java.util.List.of();
        List<FeaturedPicker.Candidate> candidates = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT t.material, COUNT(d.uuid) AS donors "
                + "FROM bv_target t "
                + "LEFT JOIN bv_donation d ON d.material = t.material AND d.edition = t.edition AND d.quantity > 0 "
                + "WHERE t.edition = ? AND t.chapter IN " + inClause + " "
                + "GROUP BY t.material")) {
            ps.setString(1, edition);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new FeaturedPicker.Candidate(rs.getString(1), rs.getLong(2)));
                }
            }
        }
        return FeaturedPicker.pick(candidates, exclude, count, rng == null ? new Random() : rng);
    }

    /** `(1,2,3)` from a set of chapter numbers - values are ints we control, so safe to inline. */
    private static String intInClause(Set<Integer> values) {
        StringJoiner sj = new StringJoiner(",", "(", ")");
        for (int v : values) sj.add(Integer.toString(v));
        return sj.toString();
    }

    public record PayoutRunRow(String weekKey, java.sql.Timestamp runAt, int totalPoints, int players) {}

    /** The most recent weekly payout run, or null if none yet. Blocking. */
    public PayoutRunRow lastPayoutRun() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT week_key, run_at, total_points, players FROM bv_payout_run "
                     + "WHERE edition = ? ORDER BY run_at DESC LIMIT 1")) {
            ps.setString(1, edition);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PayoutRunRow(rs.getString(1), rs.getTimestamp(2), rs.getInt(3), rs.getInt(4));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Payout-run lookup failed: " + e.getMessage());
        }
        return null;
    }

    /** True if a payout has already run for {@code weekKey}. Fails safe (true) on error. Blocking. */
    public boolean hasPayoutRun(String weekKey) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM bv_payout_run WHERE edition = ? AND week_key = ?")) {
            ps.setString(1, edition);
            ps.setString(2, weekKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Payout-run check failed: " + e.getMessage());
            return true; // fail safe: assume done rather than risk a double pay
        }
    }

    // ----------------------------------------------------------------- featured block

    /** The current featured block(s): the most recently set batch. Blocking. */
    public java.util.List<String> currentFeatured() {
        java.util.List<String> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT material FROM bv_featured WHERE edition = ? AND set_at = "
                     + "(SELECT MAX(set_at) FROM bv_featured WHERE edition = ?) ORDER BY material")) {
            ps.setString(1, edition);
            ps.setString(2, edition);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Featured lookup failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Staff override: make {@code materials} the current featured block(s) now.
     * They share one set_at, so they become the latest batch and take effect at
     * once. {@code weekKey} is recorded for the audit. {@code by} = null for auto.
     * Blocking.
     */
    public boolean setFeatured(String weekKey, java.util.List<String> materials, UUID by) {
        if (materials == null || materials.isEmpty()) return false;
        java.sql.Timestamp setAt = new java.sql.Timestamp(System.currentTimeMillis());
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO bv_featured (edition,week_key,material,set_at,set_by) VALUES (?,?,?,?,?) "
                     + "ON DUPLICATE KEY UPDATE set_at=VALUES(set_at), set_by=VALUES(set_by)")) {
            for (String m : materials) {
                ps.setString(1, edition);
                ps.setString(2, weekKey);
                ps.setString(3, m);
                ps.setTimestamp(4, setAt);
                if (by == null) ps.setNull(5, java.sql.Types.BINARY);
                else ps.setBytes(5, toBytes(by));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            plugin.getLogger().severe("Featured set failed: " + e.getMessage());
            return false;
        }
        audit(by, "featured_set", materials.get(0),
                "{\"week\":\"" + weekKey + "\",\"count\":" + materials.size() + "}");
        return true;
    }

    /** Remove the current featured batch, so the previous one (if any) is current again. Blocking. */
    public boolean clearFeatured(UUID by) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM bv_featured WHERE edition = ? AND set_at = "
                     + "(SELECT s FROM (SELECT MAX(set_at) AS s FROM bv_featured WHERE edition = ?) x)")) {
            ps.setString(1, edition);
            ps.setString(2, edition);
            int n = ps.executeUpdate();
            if (n > 0) audit(by, "featured_clear", null, "{\"removed\":" + n + "}");
            return n > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("Featured clear failed: " + e.getMessage());
            return false;
        }
    }

    // ----------------------------------------------------------------- monitoring

    public record ContribRow(UUID uuid, String name, String material, int amount,
                             String method, boolean firstUnlock, java.sql.Timestamp ts) {}

    /** A player's most recent contributions, newest first. Blocking. */
    public java.util.List<ContribRow> contributionHistory(UUID uuid, int limit) {
        java.util.List<ContribRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT l.uuid, p.last_name, l.material, l.amount, l.method, l.first_unlock, l.ts "
                     + "FROM bv_contribution_log l JOIN bv_contributor p ON p.uuid = l.uuid "
                     + "WHERE l.edition = ? AND l.uuid = ? ORDER BY l.ts DESC LIMIT ?")) {
            ps.setString(1, edition);
            ps.setBytes(2, toBytes(uuid));
            ps.setInt(3, limit);
            readContribRows(ps, out);
        } catch (SQLException e) {
            plugin.getLogger().severe("Contribution history failed: " + e.getMessage());
        }
        return out;
    }

    /** The most recent contributions across all players, newest first. Blocking. */
    public java.util.List<ContribRow> recentContributions(int limit) {
        java.util.List<ContribRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT l.uuid, p.last_name, l.material, l.amount, l.method, l.first_unlock, l.ts "
                     + "FROM bv_contribution_log l JOIN bv_contributor p ON p.uuid = l.uuid "
                     + "WHERE l.edition = ? ORDER BY l.ts DESC LIMIT ?")) {
            ps.setString(1, edition);
            ps.setInt(2, limit);
            readContribRows(ps, out);
        } catch (SQLException e) {
            plugin.getLogger().severe("Recent contributions failed: " + e.getMessage());
        }
        return out;
    }

    private void readContribRows(PreparedStatement ps, java.util.List<ContribRow> out) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new ContribRow(fromBytes(rs.getBytes(1)), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getString(5), rs.getBoolean(6), rs.getTimestamp(7)));
            }
        }
    }

    /**
     * Adjust one player's donation tally for a block by {@code delta} (floored at
     * 0), and their items_given to match. Does not touch the unlock or its points
     * (use {@link #revoke}). Returns false if they have no donation of that block.
     * Blocking.
     */
    public boolean adjustDonation(UUID uuid, String material, int delta, UUID actor) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                long current;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT quantity FROM bv_donation WHERE material=? AND edition=? AND uuid=? FOR UPDATE")) {
                    ps.setString(1, material);
                    ps.setString(2, edition);
                    ps.setBytes(3, toBytes(uuid));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return false; }
                        current = rs.getLong(1);
                    }
                }
                long updated = Math.max(0, current + delta);
                long applied = updated - current; // real change after flooring
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE bv_donation SET quantity = ? WHERE material=? AND edition=? AND uuid=?")) {
                    ps.setLong(1, updated);
                    ps.setString(2, material);
                    ps.setString(3, edition);
                    ps.setBytes(4, toBytes(uuid));
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE bv_contributor SET items_given = GREATEST(0, CAST(items_given AS SIGNED) + ?) "
                        + "WHERE uuid = ?")) {
                    ps.setLong(1, applied);
                    ps.setBytes(2, toBytes(uuid));
                    ps.executeUpdate();
                }
                logContribution(c, uuid, material, applied, "adjust");
                c.commit();
                audit(actor, "contrib_adjust", material,
                        "{\"delta\":" + delta + ",\"from\":" + current + ",\"to\":" + updated + "}");
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Adjust donation failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Remove one player's whole donation tally for a block and take those items
     * off their items_given. Leaves the unlock/points alone (use {@link #revoke}
     * to reverse an unlock). Returns false if there was nothing to remove. Blocking.
     */
    public boolean removeDonation(UUID uuid, String material, UUID actor) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                long qty;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT quantity FROM bv_donation WHERE material=? AND edition=? AND uuid=? FOR UPDATE")) {
                    ps.setString(1, material);
                    ps.setString(2, edition);
                    ps.setBytes(3, toBytes(uuid));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return false; }
                        qty = rs.getLong(1);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM bv_donation WHERE material=? AND edition=? AND uuid=?")) {
                    ps.setString(1, material);
                    ps.setString(2, edition);
                    ps.setBytes(3, toBytes(uuid));
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE bv_contributor SET items_given = GREATEST(0, CAST(items_given AS SIGNED) - ?) "
                        + "WHERE uuid = ?")) {
                    ps.setLong(1, qty);
                    ps.setBytes(2, toBytes(uuid));
                    ps.executeUpdate();
                }
                logContribution(c, uuid, material, -qty, "remove");
                c.commit();
                audit(actor, "contrib_remove", material, "{\"quantity\":" + qty + "}");
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Remove donation failed: " + e.getMessage());
            return false;
        }
    }

    private static String normaliseMethod(String method) {
        if (method == null) return "chest";
        return switch (method) {
            case "hand", "new", "chest", "adjust", "remove" -> method;
            default -> "chest";
        };
    }

    /** Monitoring-log row for a staff tally edit (same transaction). No-op if monitoring is off. */
    private void logContribution(Connection c, UUID uuid, String material, long amount,
                                 String method) throws SQLException {
        if (!plugin.getConfig().getBoolean("monitoring.enabled", true) || amount == 0) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO bv_contribution_log (edition,uuid,material,amount,method,first_unlock) "
                + "VALUES (?,?,?,?,?,0)")) {
            ps.setString(1, edition);
            ps.setBytes(2, toBytes(uuid));
            ps.setString(3, material);
            ps.setLong(4, amount);
            ps.setString(5, normaliseMethod(method));
            ps.executeUpdate();
        }
    }

    /**
     * A contributor's UUID by the name they last donated under (case-insensitive,
     * most recently seen first), or null. Blocking.
     */
    public UUID uuidByName(String name) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid FROM bv_contributor WHERE LOWER(last_name) = LOWER(?) "
                     + "ORDER BY last_seen DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? fromBytes(rs.getBytes(1)) : null;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Name lookup failed: " + e.getMessage());
            return null;
        }
    }

    /** material -> how many of it {@code uuid} has given, for this edition. Null on failure. Blocking. */
    public java.util.Map<String, Long> myDonations(UUID uuid) {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT material, quantity FROM bv_donation WHERE edition = ? AND uuid = ? AND quantity > 0")) {
            ps.setString(1, edition);
            ps.setBytes(2, toBytes(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Donation lookup failed: " + e.getMessage());
            return null;
        }
        return out;
    }

    /** When the vault was last opened with /bvstart, or null if never. Blocking. */
    public java.time.Instant startedAt() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT started_at FROM bv_state WHERE edition = ?")) {
            ps.setString(1, edition);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getTimestamp(1) != null) return rs.getTimestamp(1).toInstant();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Start-time lookup failed: " + e.getMessage());
        }
        return null;
    }

    public void close() {
        if (ds != null && !ds.isClosed()) ds.close();
    }

    static byte[] toBytes(UUID u) {
        return ByteBuffer.allocate(16)
                .putLong(u.getMostSignificantBits())
                .putLong(u.getLeastSignificantBits())
                .array();
    }

    static UUID fromBytes(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        return new UUID(bb.getLong(), bb.getLong());
    }
}
