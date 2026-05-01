package net.arcanestudios.rotatingsell.managers;

import net.arcanestudios.rotatingsell.RotatingSell;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Handles reading and writing rotation state to data.yml.
 * Each entry is keyed by group name (e.g. "Farming", "MobDrops").
 */
public class DataManager {

    private final RotatingSell plugin;
    private final File file;
    private FileConfiguration config;
    private final Map<String, ActiveRotation> activeRotations = new HashMap<>();

    public DataManager(RotatingSell plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        loadData();
    }

    /**
     * Reads rotation state from data.yml into memory.
     */
    public void loadData() {
        try {
            if (!file.exists()) {
                config = new YamlConfiguration();
                return;
            }

            config = YamlConfiguration.loadConfiguration(file);
            activeRotations.clear();

            for (String groupName : config.getKeys(false)) {
                ConfigurationSection section = config.getConfigurationSection(groupName);
                if (section == null) continue;

                double multiplier = section.getDouble("multiplier");
                long expiresAt = section.getLong("expires-at");
                String activeItem = normalizeMaterialName(section.getString("active-item", "UNKNOWN"));

                activeRotations.put(groupName, new ActiveRotation(activeItem, multiplier, expiresAt));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load rotation data from data.yml!", e);
        }
    }

    /**
     * Writes the current rotation state to data.yml.
     */
    public void saveData() {
        try {
            // Fresh instance so stale keys don't carry over
            FileConfiguration newConfig = new YamlConfiguration();

            for (Map.Entry<String, ActiveRotation> entry : activeRotations.entrySet()) {
                ConfigurationSection section = newConfig.createSection(entry.getKey());
                section.set("active-item", entry.getValue().activeItem());
                section.set("multiplier", entry.getValue().multiplier());
                section.set("expires-at", entry.getValue().expiresAt());
            }

            newConfig.save(file);
            this.config = newConfig;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save rotation data to data.yml!", e);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Unexpected error while saving data.yml", e);
        }
    }

    public Map<String, ActiveRotation> getActiveRotations() {
        return activeRotations;
    }

    public void updateRotation(String groupName, String activeItem, double multiplier, long expiresAt) {
        activeRotations.put(groupName, new ActiveRotation(normalizeMaterialName(activeItem), multiplier, expiresAt));
    }

    private static String normalizeMaterialName(String input) {
        if (input == null || input.isBlank()) {
            return "UNKNOWN";
        }
        String upper = input.toUpperCase(Locale.ROOT);
        return Material.getMaterial(upper) != null ? upper : input;
    }

    public void purgeUnknownGroups(Set<String> validGroupNames) {
        boolean changed = activeRotations.keySet().retainAll(validGroupNames);
        if (changed) {
            plugin.getLogger().info("[DataManager] Removed legacy per-item entries from data.yml.");
            saveData();
        }
    }

    public record ActiveRotation(String activeItem, double multiplier, long expiresAt) {}
}
