package dev.anchorlight.blockvault.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.anchorlight.blockvault.BlockVault;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
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

    public enum SubmitOutcome { OK, ALREADY_TAKEN, DB_ERROR }

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
            seedTargets(c, manifest);
            seedChapters(c, manifest);
            warmCollected(c);
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

    private static String stripComments(String block) {
        StringBuilder out = new StringBuilder();
        for (String line : block.split("\\r?\\n")) {
            if (line.stripLeading().startsWith("--")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private void seedTargets(Connection c, Manifest manifest) throws SQLException {
        if (rowCount(c, "bv_target") > 0) return;
        String q = "INSERT INTO bv_target (material,edition,chapter,rarity,section,"
                + "sign_x,sign_y,sign_z,frame_x,frame_y,frame_z,head_x,head_y,head_z,facing) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(q)) {
            for (TargetEntry e : manifest.entries().values()) {
                ps.setString(1, e.material());
                ps.setString(2, edition);
                ps.setInt(3, e.chapter());
                ps.setString(4, e.rarity());
                ps.setString(5, e.section());
                ps.setInt(6, e.sign()[0]);  ps.setInt(7, e.sign()[1]);  ps.setInt(8, e.sign()[2]);
                ps.setInt(9, e.frame()[0]); ps.setInt(10, e.frame()[1]); ps.setInt(11, e.frame()[2]);
                ps.setInt(12, e.head()[0]); ps.setInt(13, e.head()[1]); ps.setInt(14, e.head()[2]);
                ps.setString(15, e.facing());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        plugin.getLogger().info("Seeded " + manifest.entries().size()
                + " target rows for edition " + edition + ".");
    }

    private void seedChapters(Connection c, Manifest manifest) throws SQLException {
        if (rowCount(c, "bv_chapter") > 0) return;
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

    private int rowCount(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
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

    /** material -> cached profile JSON (may be null) for this edition. Blocking. */
    public java.util.Map<String, String> allProfiles() {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT material, profile FROM bv_submission WHERE edition = ?")) {
            ps.setString(1, edition);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load cached profiles: " + e.getMessage());
        }
        return out;
    }

    // ----------------------------------------------------------------- writes

    /**
     * Record a submission. Blocking - call on the async scheduler.
     * The item must NOT be consumed until this returns {@link SubmitOutcome#OK}.
     *
     * @param profileJson cached skin profile, or null
     */
    public SubmitOutcome submit(String material, UUID uuid, String name,
                                int points, String profileJson) {
        byte[] id = toBytes(uuid);
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bv_contributor (uuid,last_name) VALUES (?,?) "
                        + "ON DUPLICATE KEY UPDATE last_name=VALUES(last_name), "
                        + "last_seen=CURRENT_TIMESTAMP")) {
                    ps.setBytes(1, id);
                    ps.setString(2, name);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bv_submission (material,edition,uuid,points,profile) "
                        + "VALUES (?,?,?,?,?)")) {
                    ps.setString(1, material);
                    ps.setString(2, edition);
                    ps.setBytes(3, id);
                    ps.setInt(4, points);
                    if (profileJson == null) ps.setNull(5, java.sql.Types.VARCHAR);
                    else ps.setString(5, profileJson);
                    ps.executeUpdate();
                } catch (SQLException dup) {
                    if (dup.getErrorCode() == ER_DUP_ENTRY) {
                        c.rollback();
                        collected.add(material); // reconcile the memory cache
                        return SubmitOutcome.ALREADY_TAKEN;
                    }
                    throw dup;
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE bv_contributor SET points = points + ?, "
                        + "blocks_given = blocks_given + 1 WHERE uuid = ?")) {
                    ps.setInt(1, points);
                    ps.setBytes(2, id);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO bv_audit (actor,action,material,detail) "
                        + "VALUES (?,?,?,JSON_OBJECT('points',?))")) {
                    ps.setBytes(1, id);
                    ps.setString(2, "submit");
                    ps.setString(3, material);
                    ps.setInt(4, points);
                    ps.executeUpdate();
                }

                c.commit();
                collected.add(material);
                return SubmitOutcome.OK;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Submission write failed for " + material
                    + " by " + name + ": " + e.getMessage());
            return SubmitOutcome.DB_ERROR;
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
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) + 1 FROM bv_contributor a "
                     + "JOIN bv_contributor b ON b.uuid = ? "
                     + "WHERE a.points > b.points")) {
            ps.setBytes(1, toBytes(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Rank query failed: " + e.getMessage());
            return -1;
        }
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
