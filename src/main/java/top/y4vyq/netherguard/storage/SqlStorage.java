package top.y4vyq.netherguard.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.configuration.file.FileConfiguration;

import top.y4vyq.netherguard.NetherGuardPlugin;
import top.y4vyq.netherguard.model.Region;

/**
 * SQLite / MySQL 双实现。每次操作使用独立连接，配合 WriteExecutor 保证写入串行。
 */
public final class SqlStorage implements Storage {

    private static final String SQLITE_CREATE = """
            CREATE TABLE IF NOT EXISTS {table} (
                id          INTEGER PRIMARY KEY,
                region_name TEXT    NOT NULL,
                owner_uuid  TEXT    NOT NULL,
                owner_name  TEXT,
                world_name  TEXT    NOT NULL,
                min_x       INTEGER NOT NULL,
                min_y       INTEGER NOT NULL,
                min_z       INTEGER NOT NULL,
                max_x       INTEGER NOT NULL,
                max_y       INTEGER NOT NULL,
                max_z       INTEGER NOT NULL,
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            )
            """;

    private static final String MYSQL_CREATE = """
            CREATE TABLE IF NOT EXISTS {table} (
                id          BIGINT PRIMARY KEY AUTO_INCREMENT,
                region_name VARCHAR(64)  NOT NULL,
                owner_uuid  CHAR(36)     NOT NULL,
                owner_name  VARCHAR(32),
                world_name  VARCHAR(128) NOT NULL,
                min_x       INT NOT NULL,
                min_y       INT NOT NULL,
                min_z       INT NOT NULL,
                max_x       INT NOT NULL,
                max_y       INT NOT NULL,
                max_z       INT NOT NULL,
                created_at  BIGINT NOT NULL,
                updated_at  BIGINT NOT NULL,
                UNIQUE KEY uk_owner_name (owner_uuid, region_name),
                KEY idx_world (world_name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    private final NetherGuardPlugin plugin;
    private final boolean mysql;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String table;

    public SqlStorage(NetherGuardPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration cfg = plugin.getConfig();

        String type = cfg.getString("storage.type", "sqlite").toLowerCase();
        this.mysql = "mysql".equals(type);

        if (mysql) {
            String host = cfg.getString("storage.mysql.host", "localhost");
            int port = cfg.getInt("storage.mysql.port", 3306);
            String db = cfg.getString("storage.mysql.database", "portal_region");
            this.username = cfg.getString("storage.mysql.username", "root");
            this.password = cfg.getString("storage.mysql.password", "");
            this.table = cfg.getString("storage.mysql.table-prefix", "pr_") + "portal_regions";
            this.jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&characterEncoding=utf8&serverTimezone=UTC";
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("MySQL JDBC driver not found on classpath", e);
            }
        } else {
            String fileName = cfg.getString("storage.sqlite.file", "regions.db");
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                plugin.getLogger().warning("Could not create plugin data folder: " + dataFolder);
            }
            File dbFile = new File(dataFolder, fileName);
            this.username = null;
            this.password = null;
            this.table = "portal_regions";
            this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("SQLite JDBC driver not found on classpath", e);
            }
        }
    }

    private Connection open() throws SQLException {
        Connection c = (username != null)
                ? DriverManager.getConnection(jdbcUrl, username, password)
                : DriverManager.getConnection(jdbcUrl);

        if (!mysql) {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
            }
        }
        return c;
    }

    @Override
    public void init() throws SQLException {
        String create = (mysql ? MYSQL_CREATE : SQLITE_CREATE).replace("{table}", table);
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.executeUpdate(create);
            if (!mysql) {
                s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_" + table
                        + "_owner_name ON " + table + "(owner_uuid, region_name)");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + table
                        + "_world ON " + table + "(world_name)");
            }
        }
    }

    @Override
    public List<Region> loadAll() throws SQLException {
        List<Region> out = new ArrayList<>();
        boolean use2d = plugin.getConfig().getBoolean("limits.use-2d", false);
        String sql = "SELECT id, region_name, owner_uuid, owner_name, world_name, "
                + "min_x, min_y, min_z, max_x, max_y, max_z, created_at, updated_at FROM " + table;
        try (Connection c = open();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(mapRow(rs, use2d));
            }
        }
        return out;
    }

    @Override
    public long insert(Region r) throws SQLException {
        String sql = "INSERT INTO " + table
                + " (region_name, owner_uuid, owner_name, world_name,"
                + "  min_x, min_y, min_z, max_x, max_y, max_z, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.getName());
            ps.setString(2, r.getOwnerUuid().toString());
            ps.setString(3, r.getOwnerName());
            ps.setString(4, r.getWorldName());
            ps.setInt(5, r.getMinX());
            ps.setInt(6, r.getMinY());
            ps.setInt(7, r.getMinZ());
            ps.setInt(8, r.getMaxX());
            ps.setInt(9, r.getMaxY());
            ps.setInt(10, r.getMaxZ());
            ps.setLong(11, r.getCreatedAt());
            ps.setLong(12, r.getUpdatedAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        return -1L;
    }

    @Override
    public boolean delete(long id) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    private Region mapRow(ResultSet rs, boolean use2d) throws SQLException {
        return new Region(
                rs.getLong("id"),
                rs.getString("region_name"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("owner_name"),
                rs.getString("world_name"),
                rs.getInt("min_x"), rs.getInt("min_y"), rs.getInt("min_z"),
                rs.getInt("max_x"), rs.getInt("max_y"), rs.getInt("max_z"),
                use2d,
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}