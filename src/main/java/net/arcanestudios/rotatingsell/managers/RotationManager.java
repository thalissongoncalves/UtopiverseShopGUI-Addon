package net.arcanestudios.rotatingsell.managers;

import net.arcanestudios.rotatingsell.RotatingSell;
import net.arcanestudios.rotatingsell.utils.TimeFormatter;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages group-based rotation cycles and sell price multipliers.
 * All items within a group share the same multiplier and expiration timer.
 */
public class RotationManager {

    private final RotatingSell plugin;

    // Group name → its config (min, max, duration, items)
    private final Map<String, RotatingGroupConfig> groupConfigs = new HashMap<>();

    // Material name → group name, for fast lookups during sell events
    private final Map<String, String> itemToGroup = new HashMap<>();

    private BukkitTask rotationTask;

    public RotationManager(RotatingSell plugin) {
        this.plugin = plugin;
        loadConfig();
        startRotationTask();
    }

    /**
     * Reads group definitions from config.yml and builds the item→group index.
     */
    public void loadConfig() {
        groupConfigs.clear();
        itemToGroup.clear();

        ConfigurationSection groupsSection = plugin.getConfig().getConfigurationSection("groups");
        if (groupsSection == null) {
            plugin.getLogger().warning("No 'groups' section found in config.yml!");
            return;
        }

        for (String groupName : groupsSection.getKeys(false)) {
            ConfigurationSection section = groupsSection.getConfigurationSection(groupName);
            if (section == null) continue;

            double min = section.getDouble("min", 0.5);
            double max = section.getDouble("max", 1.5);

            if (min < -1.0) {
                plugin.getLogger().warning(String.format(
                        "Group '%s' has min=%.2f which would produce a multiplier <= 0. " +
                        "Values below -1.0 are invalid — it will be clamped to 0.01x at runtime.",
                        groupName, min
                ));
            }

            long durationMs = TimeFormatter.parseDuration(section.getString("duration", "1d"));
            List<String> rawItems = section.getStringList("items");

            List<String> validItems = new ArrayList<>();
            for (String entry : rawItems) {
                String material = entry.toUpperCase();
                if (Material.getMaterial(material) == null) {
                    plugin.getLogger().warning("Unknown material '" + entry + "' in group '" + groupName + "', skipping.");
                    continue;
                }
                validItems.add(material);
                itemToGroup.put(material, groupName);
            }

            groupConfigs.put(groupName, new RotatingGroupConfig(min, max, durationMs, validItems));
        }

        plugin.getLogger().info(String.format(
                "Loaded %d rotation group(s) covering %d item(s).",
                groupConfigs.size(), itemToGroup.size()
        ));

        // Fix any UNKNOWN active items from legacy data
        Map<String, DataManager.ActiveRotation> activeRotations = plugin.getDataManager().getActiveRotations();
        for (Map.Entry<String, RotatingGroupConfig> entry : groupConfigs.entrySet()) {
            String groupName = entry.getKey();
            DataManager.ActiveRotation active = activeRotations.get(groupName);
            if (active != null && "UNKNOWN".equals(active.activeItem())) {
                String newItem = "UNKNOWN";
                if (!entry.getValue().items().isEmpty()) {
                    newItem = entry.getValue().items().get(ThreadLocalRandom.current().nextInt(entry.getValue().items().size()));
                }
                plugin.getDataManager().updateRotation(groupName, newItem, active.multiplier(), active.expiresAt());
                plugin.getDataManager().saveData();
            }
        }

        plugin.getDataManager().purgeUnknownGroups(groupConfigs.keySet());

        performRotationCheck();
    }

    /**
     * Cancels the current task, reloads config, regenerates all multipliers
     * with the new bounds, and restarts the scheduler.
     */
    public void reload() {
        if (rotationTask != null) {
            rotationTask.cancel();
            rotationTask = null;
        }
        loadConfig();
        startRotationTask();
    }

    private void startRotationTask() {
        long intervalSeconds = plugin.getConfig().getLong("settings.check-interval-seconds", 300);
        rotationTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::performRotationCheck,
                20L * intervalSeconds,
                20L * intervalSeconds
        );
    }

    /**
     * Checks every group and rotates those whose timer has expired.
     */
    private void performRotationCheck() {
        long now = System.currentTimeMillis();
        boolean changed = false;

        for (Map.Entry<String, RotatingGroupConfig> entry : groupConfigs.entrySet()) {
            String groupName = entry.getKey();
            DataManager.ActiveRotation active = plugin.getDataManager().getActiveRotations().get(groupName);

            if (active == null || now >= active.expiresAt()) {
                rotateGroup(groupName, entry.getValue());
                changed = true;
            }
        }

        if (changed) {
            plugin.getDataManager().saveData();
        }
    }

    /**
     * Picks a new random multiplier for a group and updates the data store.
     * Also schedules a one-shot expiry check so the group rotates exactly when it expires,
     * regardless of the periodic check-interval-seconds setting.
     */
    public void rotateGroup(String groupName, RotatingGroupConfig config) {
        double rawMultiplier = (config.min() >= config.max())
                ? config.min()
                : ThreadLocalRandom.current().nextDouble(config.min(), config.max());
        double multiplier = Math.max(0.01, Math.round(rawMultiplier * 100.0) / 100.0);
        long expiresAt = System.currentTimeMillis() + config.durationMs();

        String activeItem = "UNKNOWN";
        if (config.items() != null && !config.items().isEmpty()) {
            activeItem = config.items().get(ThreadLocalRandom.current().nextInt(config.items().size()));
        }

        plugin.getDataManager().updateRotation(groupName, activeItem, multiplier, expiresAt);
        plugin.getLogger().info(String.format(Locale.US,
                "Group '%s' rotated: %s is now %.2fx for the next %s",
                groupName, activeItem, multiplier, TimeFormatter.formatDuration(config.durationMs())
        ));

        if (plugin.getShopListener() != null) {
            plugin.getShopListener().refreshAllOnlineModifiers();
        }

        // Schedule a precise expiry check so short-duration groups rotate on time
        // without depending solely on the periodic check-interval-seconds tick.
        long expiryTicks = Math.max(1L, config.durationMs() / 50L) + 2L;
        plugin.getServer().getScheduler().runTaskLater(plugin, this::performRotationCheck, expiryTicks);
    }

    /**
     * Forces an immediate rotation for the given group name.
     * Returns false if the group doesn't exist in config.
     */
    public boolean forceRotate(String groupName) {
        RotatingGroupConfig config = groupConfigs.get(groupName);
        if (config == null) return false;

        rotateGroup(groupName, config);
        plugin.getDataManager().saveData();
        return true;
    }

    /**
     * Returns the active sell multiplier for a material.
     * Looks up which group the material belongs to, then returns that group's multiplier.
     * Returns 1.0 if the material isn't part of any rotation group.
     */
    public double getMultiplier(Material material) {
        String groupName = itemToGroup.get(material.name());
        if (groupName == null) return 1.0;

        DataManager.ActiveRotation active = plugin.getDataManager().getActiveRotations().get(groupName);
        if (active != null && active.activeItem().equalsIgnoreCase(material.name())) {
            return active.multiplier();
        }
        return 1.0;
    }

    /**
     * Returns the active rotation for a group, or null if it doesn't exist.
     */
    public DataManager.ActiveRotation getActiveRotation(String groupName) {
        return plugin.getDataManager().getActiveRotations().get(groupName);
    }

    /**
     * Returns all group names for tab completion and placeholder lookups.
     */
    public Set<String> getGroupNames() {
        return groupConfigs.keySet();
    }

    public Map<String, RotatingGroupConfig> getGroupConfigs() {
        return groupConfigs;
    }

    /**
     * Material names (uppercase) that belong to any rotation group — used to match a
     * {@link net.brcdev.shopgui.shop.item.ShopItem} to a vanilla material when the stack on the
     * shop item is not reliable (common with SellGUI / placeholder entries).
     */
    public Set<String> getTrackedMaterialNames() {
        return Collections.unmodifiableSet(itemToGroup.keySet());
    }

    public record RotatingGroupConfig(double min, double max, long durationMs, List<String> items) {}
}
