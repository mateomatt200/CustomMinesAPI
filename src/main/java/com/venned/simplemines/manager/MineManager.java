package com.venned.simplemines.manager;


import com.google.common.reflect.TypeToken;
import com.venned.simplemines.build.Mine;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class MineManager {
    private final JavaPlugin plugin;
    private final Map<String, Mine> mines = new HashMap<>();
    private final File minesFolder;
    private HikariDataSource dataSource;

    public MineManager(JavaPlugin plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        this.minesFolder = new File(plugin.getDataFolder(), "mines");
        if (!minesFolder.exists()) {
            minesFolder.mkdirs();
        }

        setupDataSource();
        setupDatabase();

        loadMines();


        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveDirtyMines, 200, 200);

    }

    private void setupDatabase() {
        try (Connection connection = getConnection()) {
            connection.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS mines (" +
                            "  name VARCHAR(32) PRIMARY KEY," +
                            "  blocks_mined INT NOT NULL" +
                            ");"
            );
        } catch (SQLException e) {
            plugin.getLogger().severe("❌ Error table 'mine':");
            e.printStackTrace();
        }
    }


    private void setupDataSource() {
        String host = plugin.getConfig().getString("data-base.host");
        String user = plugin.getConfig().getString("data-base.user");
        String pass = plugin.getConfig().getString("data-base.pass");
        String database = plugin.getConfig().getString("data-base.database", "cards");
        boolean useSSL = plugin.getConfig().getBoolean("data-base.ssl");

        String jdbcUrl = "jdbc:mysql://" + host + ":3306/" + database + "?useSSL=" + useSSL + "&autoReconnect=true";

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(pass);

        config.setInitializationFailTimeout(-1);
        config.setConnectionTestQuery("SELECT 1");
        config.setValidationTimeout(5_000);

        config.setIdleTimeout(600_000);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);

        this.dataSource = new HikariDataSource(config);
        plugin.getLogger().info("✅ Connection SQL.");
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }


    public void loadMines() {
        mines.clear();
        File[] files = minesFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = cfg;
            try {
                Mine mine = new Mine(section);
                loadBlocksData(mine);
                mines.put(mine.getMineName(), mine);
            } catch (Exception e) {
                plugin.getLogger().severe("Error load mine " + file.getName() + ": " + e.getMessage());
            }
        }


    }

    public void loadBlocksData(Mine mine) {
        String sqlSelect = "SELECT blocks_mined FROM mines WHERE name = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sqlSelect)) {

            stmt.setString(1, mine.getMineName());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                mine.setBlocksMined(rs.getInt("blocks_mined"));
            } else {
                saveSQL(mine);
            }

        } catch (SQLException e) {
            plugin.getLogger().severe("Error SQL Mine " + mine.getMineName());
            e.printStackTrace();
        }
    }

    private void saveDirtyMines() {
        if (mines.isEmpty()) return;

        String sql = "INSERT INTO mines (name, blocks_mined) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE blocks_mined = VALUES(blocks_mined)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            for (Mine m : mines.values()) {
                ps.setString(1, m.getMineName());
                ps.setInt(2, m.getBlocksMined());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            plugin.getLogger().severe("Error : " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void saveSQL(Mine mine) {
        String query = "INSERT INTO mines (name, blocks_mined) " +
                "VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "blocks_mined = VALUES(blocks_mined) ";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, mine.getMineName());
            stmt.setInt(2, mine.getBlocksMined());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Error SQL Save Mine -> " + mine.getMineName());
            e.printStackTrace();
        }
    }


    public void saveMine(Mine mine) {

        mines.putIfAbsent(mine.getMineName(), mine);

        File file = new File(minesFolder, mine.getMineName() + ".yml");
        YamlConfiguration cfg = new YamlConfiguration();

        cfg.set("mine_name", mine.getMineName());

        List<String> blockList = new ArrayList<>();
        for (Mine.BlockEntry block : mine.getBlocks()) {
            String line = block.getMaterial().name() + ":" +
                    block.getPercentage() + ":" +
                    true + ":" +
                    true;
            blockList.add(line);
        }
        cfg.set("blocks", blockList);


        Location min = mine.getRegionMin();
        Location max = mine.getRegionMax();
        cfg.set("region.world", min.getWorld().getName());
        cfg.set("region.xmin", min.getBlockX());
        cfg.set("region.ymin", min.getBlockY());
        cfg.set("region.zmin", min.getBlockZ());
        cfg.set("region.xmax", max.getBlockX());
        cfg.set("region.ymax", max.getBlockY());
        cfg.set("region.zmax", max.getBlockZ());


        cfg.set("reset.use_timer", mine.useTimer);
        cfg.set("reset.timer", mine.timer);
        cfg.set("reset.use_percentage", mine.usePercentage);
        cfg.set("reset.percentage", mine.resetPercentage);
        cfg.set("reset.reset_type", mine.resetType.name());
        cfg.set("reset.use_messages", mine.useMessages);
        cfg.set("reset.blocks_mined_current", mine.getBlocksMinedCurrent());
        cfg.set("reset.reset_direction", mine.resetDirection.name());

        Location tp = mine.teleportLocation;
        if (tp != null) {
            cfg.set("teleport_location", tp);
        }

        cfg.set("blocks_mined", mine.getBlocksMined());
        saveSQL(mine);

        try {
            cfg.save(file);
            plugin.getLogger().info("Save Mine: " + mine.getMineName());
        } catch (IOException e) {
            plugin.getLogger().severe("Error Save Mine  " + mine.getMineName() + ": " + e.getMessage());
        }
    }

    public boolean deleteMine(String name) {
        Mine mine = mines.remove(name);
        if (mine == null) return false;
        File file = new File(minesFolder, name + ".yml");
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Failed to delete YAML for mine: " + name);
        }

        String sql = "DELETE FROM mines WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Error deleting SQL record for mine " + name);
            e.printStackTrace();
        }
        return true;
    }


    public Mine getMine(String name) {
        return mines.get(name);
    }

    public Map<String, Mine> getMines() {
        return new HashMap<>(mines);
    }


    public File getMinesFolder() {
        return minesFolder;
    }
}
