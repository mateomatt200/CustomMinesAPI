package com.venned.simplemines.build;

import com.venned.simplemines.Main;
import com.venned.simplemines.util.MineResetUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Mine {
    private final String mineName;
    private final List<BlockEntry> blocks;
    private final Location regionMin;
    private final Location regionMax;
    public boolean useTimer;
    public int timer;
    public final boolean usePercentage;
    public final int resetPercentage;
    public ResetType resetType;
    public final boolean useMessages;
    public ResetDirection resetDirection;
    private int blocksMined;
    public  Location teleportLocation;
    public Instant lastReset;

    public int blocksMinedCurrent;


    public Mine(String mineName, Location regionMin, Location regionMax){
        this.mineName = mineName;
        this.resetPercentage = 70;

        this.blocks = new ArrayList<>();
        this.regionMin = regionMin;
        this.regionMax = regionMax;

        this.useTimer = false;
        this.timer = 0;
        this.usePercentage = false;
        this.resetType = ResetType.INSTANT;
        this.useMessages = false;
        this.resetDirection = ResetDirection.TOPTOBOTTOM;

        this.blocksMined = 0;
        this.teleportLocation = null;
        this.lastReset = Instant.now();
    }

    public Mine(ConfigurationSection section) {
        // Nombre de la mina
        this.mineName = section.getString("mine_name");

        // Bloques y sus propiedades
        this.blocks = new ArrayList<>();
        for (String line : section.getStringList("blocks")) {
            try {
                String[] parts = line.split(":");
                if (parts.length < 4) {
                    Bukkit.getLogger().warning("[SimpleMines] Formato inválido en bloques de la mina '" + mineName + "': " + line);
                    continue;
                }

                Material mat = Material.matchMaterial(parts[0]);
                if (mat == null) {
                    Bukkit.getLogger().warning("[SimpleMines] Material desconocido: '" + parts[0] + "' en la mina '" + mineName + "'");
                    continue;
                }

                double chance = Double.parseDouble(parts[1]);
                boolean silkTouch = Boolean.parseBoolean(parts[2]);
                boolean explodeProof = Boolean.parseBoolean(parts[3]);

                blocks.add(new BlockEntry(mat, chance, silkTouch, explodeProof));

            //   Bukkit.getLogger().info("[Mines] Loaded block for mine '" + mineName + "': "
            //            + mat + " | " + chance + "% |");
            } catch (Exception e) {
                Bukkit.getLogger().severe("[SimpleMines] Error al cargar bloque '" + line + "' en la mina '" + mineName + "': " + e.getMessage());
            }
        }

        // Región (dos esquinas)
        ConfigurationSection reg = section.getConfigurationSection("region");
        World world = Bukkit.getWorld(reg.getString("world"));
        this.regionMin = new Location(
                world,
                reg.getDouble("xmin"),
                reg.getDouble("ymin"),
                reg.getDouble("zmin")
        );
        this.regionMax = new Location(
                world,
                reg.getDouble("xmax"),
                reg.getDouble("ymax"),
                reg.getDouble("zmax")
        );

        // Configuración de reseteo
        ConfigurationSection rst = section.getConfigurationSection("reset");
        this.useTimer        = rst.getBoolean("use_timer");
        this.timer           = rst.getInt("timer");
        this.usePercentage   = rst.getBoolean("use_percentage");
        this.resetPercentage = rst.getInt("percentage");
        this.resetType       = ResetType.valueOf(rst.getString("reset_type"));
        this.useMessages     = rst.getBoolean("use_messages");
        this.resetDirection  = ResetDirection.valueOf(rst.getString("reset_direction"));

        // Bloques minados (persistido)
        this.blocksMined = section.getInt("blocks_mined", 0);

        this.blocksMinedCurrent = section.getInt("blocks_mined_current", 0);

        ConfigurationSection tp = section.getConfigurationSection("teleport_location");
        if (tp != null) {
            String worldName = tp.getString("world");
            World world2 = Bukkit.getWorld(worldName);
            if (world2 != null) {
                teleportLocation = new Location(
                        world2,
                        tp.getDouble("x"),
                        tp.getDouble("y"),
                        tp.getDouble("z"),
                        (float) tp.getDouble("yaw", 0),
                        (float) tp.getDouble("pitch", 0)
                );
            } else {
                Bukkit.getLogger().warning("World '" + worldName + "' not loaded for teleport_location in mine '" + mineName + "'");
            }
        }
        this.lastReset = Instant.now();

    }

    public boolean isInside(Location loc) {
        if (loc == null || !loc.getWorld().equals(regionMin.getWorld())) return false;
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        int minX = Math.min(regionMin.getBlockX(), regionMax.getBlockX());
        int maxX = Math.max(regionMin.getBlockX(), regionMax.getBlockX());
        int minY = Math.min(regionMin.getBlockY(), regionMax.getBlockY());
        int maxY = Math.max(regionMin.getBlockY(), regionMax.getBlockY());
        int minZ = Math.min(regionMin.getBlockZ(), regionMax.getBlockZ());
        int maxZ = Math.max(regionMin.getBlockZ(), regionMax.getBlockZ());

        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public void incrementBlocksMinedCurrent() {
        this.blocksMinedCurrent++;
        this.blocksMined++;
    }

    public void desIncrementBlock(){
        --blocksMinedCurrent;
    }

    public void setBlocksMinedCurrent(int blocksMinedCurrent) {
        this.blocksMinedCurrent = blocksMinedCurrent;
    }

    public int getBlocksMinedCurrent() {
        return blocksMinedCurrent;
    }

    public void reset() {
        // Lógica para regenerar bloques en el área entre regionMin y regionMax según "blocks"
        this.blocksMinedCurrent = 0;
        this.lastReset = Instant.now();
        MineResetUtil.populateRegion(this);

        if (useMessages) {
           // Bukkit.broadcastMessage("La mina " + mineName + " ha sido reseteada.");
        }
    }

    /**
     * Verifica si debe resetear según configuración.
     */
    public boolean shouldReset() {
        // 1) Si hay timer y ha expirado, devuelve true
        if (useTimer) {
            long elapsed = Instant.now().getEpochSecond() - lastReset.getEpochSecond();
            if (elapsed >= timer) {
             return true;
            }
        }

        // 2) Si usas porcentaje, comprueba blocksMinedCurrent vs. target
        if (usePercentage) {
            int x1 = regionMin.getBlockX(), x2 = regionMax.getBlockX();
            int y1 = regionMin.getBlockY(), y2 = regionMax.getBlockY();
            int z1 = regionMin.getBlockZ(), z2 = regionMax.getBlockZ();
            long volume = (Math.abs(x2 - x1) + 1L)
                    * (Math.abs(y2 - y1) + 1L)
                    * (Math.abs(z2 - z1) + 1L);
            long target = (long) Math.ceil(resetPercentage / 100.0 * volume);

            if (blocksMinedCurrent >= target) {

                return true;
            }
        }

        return false;
    }

    public void setTeleportLocation(Location teleportLocation) {
        this.teleportLocation = teleportLocation;
    }

    public String getMineName() {
        return mineName;
    }

    public Location getRegionMax() {
        return regionMax;
    }

    public int getResetPercentage() {
        return resetPercentage;
    }

    public List<BlockEntry> getBlocks() {
        return blocks;
    }

    public Location getRegionMin() {
        return regionMin;
    }


    public int getBlocksMined() {
        return blocksMined;
    }

    public void setBlocksMined(int blocksMined) {
        this.blocksMined = blocksMined;
    }

    public Location getTeleportLocation() {
        return teleportLocation;
    }

    public static class BlockEntry {
        private final Material material;
        private double percentage;
        private final boolean silkTouch;
        private final boolean explosionProof;

        public BlockEntry(Material material, double percentage, boolean silkTouch, boolean explosionProof) {
            this.material = material;
            this.percentage = percentage;
            this.silkTouch = silkTouch;
            this.explosionProof = explosionProof;
        }

        public double getPercentage() {
            return percentage;
        }

        public Material getMaterial() {
            return material;
        }

        public void setPercentage(double percentage) {
            this.percentage = percentage;
        }
    }

    public enum ResetType {
        INSTANT,
        GRADUAL;
    }

    public enum ResetDirection {
        TOPTOBOTTOM,
        BOTTOMTOTOP;
    }
}