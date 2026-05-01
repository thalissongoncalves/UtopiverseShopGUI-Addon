package net.arcanestudios.rotatingsell.integration.sellgui;

import net.arcanestudios.rotatingsell.RotatingSell;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the ShopGUIPlus-SellGUI {@code config.yml} (rows, title, decorations) to reliably
 * identify and validate the SellGUI inventory when it closes.
 */
public final class SellGuiCompanionConfig {

    private final RotatingSell plugin;

    private int rows = 6;
    private String titleContains = "Sell GUI";
    private Set<Integer> decorationSlots = Collections.emptySet();
    private List<DecorationTemplate> decorationTemplates = List.of();

    private record DecorationTemplate(int slot, Material material) {}

    public SellGuiCompanionConfig(RotatingSell plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        String fallbackTitle = plugin.getConfig().getString("sellgui-integration.title-contains", "Sell GUI");
        int fallbackRows = plugin.getConfig().getInt("sellgui-integration.rows", 6);

        Plugin sellGui = Bukkit.getPluginManager().getPlugin("ShopGUIPlus-SellGUI");
        if (sellGui == null || !sellGui.isEnabled()) {
            applyFallbacks(fallbackTitle, fallbackRows);
            plugin.getLogger().info("[SellGUI-Bridge] SellGUI plugin not loaded — using addon defaults"
                    + " (rows=" + rows + ", title-contains=\"" + titleContains + "\").");
            return;
        }

        File configFile = new File(sellGui.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            applyFallbacks(fallbackTitle, fallbackRows);
            plugin.getLogger().warning("[SellGUI-Bridge] SellGUI config.yml not found at "
                    + configFile.getAbsolutePath() + " — using addon defaults.");
            return;
        }

        FileConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        int rawRows = yaml.getInt("options.rows", fallbackRows);
        this.rows = Math.min(6, Math.max(1, rawRows));
        String rawTitle = yaml.getString("messages.sellgui_title", fallbackTitle);
        this.titleContains = plainSubstringFromLegacy(rawTitle, fallbackTitle);
        parseDecorations(yaml.getConfigurationSection("options.decorations"));
    }

    private void applyFallbacks(String fallbackTitle, int fallbackRows) {
        this.rows = Math.min(6, Math.max(1, fallbackRows));
        this.titleContains = fallbackTitle;
        this.decorationSlots = Collections.emptySet();
        this.decorationTemplates = List.of();
    }

    private void parseDecorations(ConfigurationSection decorationsRoot) {
        if (decorationsRoot == null) {
            this.decorationSlots = Collections.emptySet();
            this.decorationTemplates = List.of();
            return;
        }
        Set<Integer> slots = new HashSet<>();
        List<DecorationTemplate> templates = new ArrayList<>();
        for (String key : decorationsRoot.getKeys(false)) {
            ConfigurationSection entry = decorationsRoot.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            if (entry.contains("slot")) {
                slots.add(entry.getInt("slot"));
            }
            ConfigurationSection itemSection = entry.getConfigurationSection("item");
            if (itemSection == null || !entry.contains("slot")) {
                continue;
            }
            String materialName = itemSection.getString("material");
            if (materialName == null) {
                continue;
            }
            Material material = Material.matchMaterial(materialName.toUpperCase(Locale.ROOT));
            if (material == null) {
                continue;
            }
            templates.add(new DecorationTemplate(entry.getInt("slot"), material));
        }
        this.decorationSlots = slots.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(slots);
        this.decorationTemplates = templates.isEmpty() ? List.of() : List.copyOf(templates);
    }

    private static String plainSubstringFromLegacy(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String colored = org.bukkit.ChatColor.translateAlternateColorCodes('&', raw);
        String stripped = org.bukkit.ChatColor.stripColor(colored);
        if (stripped == null || stripped.isBlank()) {
            return fallback;
        }
        return stripped.trim();
    }

    public int getRows() {
        return rows;
    }

    public int getSlotCount() {
        return rows * 9;
    }

    public String getTitleContains() {
        return titleContains;
    }

    public Set<Integer> decorationSlots() {
        return decorationSlots;
    }

    /**
     * Counts non-air content slots (excluding decoration slots) in the given inventory.
     */
    public int countContentItems(Inventory inventory) {
        int count = 0;
        int size = Math.min(inventory.getSize(), getSlotCount());
        for (int i = 0; i < size; i++) {
            if (decorationSlots.contains(i)) {
                continue;
            }
            ItemStack item = inventory.getItem(i);
            if (item != null && !item.getType().isAir()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns true if all known decoration templates match the items in the given inventory.
     * When decoration templates are configured, this check reduces false positives from other
     * chest inventories with the same size and title.
     */
    public boolean decorationLayoutMatches(Inventory inventory) {
        for (DecorationTemplate template : decorationTemplates) {
            if (template.slot() < 0 || template.slot() >= inventory.getSize()) {
                return false;
            }
            ItemStack item = inventory.getItem(template.slot());
            if (item == null || item.getType() != template.material()) {
                return false;
            }
        }
        return true;
    }
}
