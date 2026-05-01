package net.arcanestudios.rotatingsell.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.arcanestudios.rotatingsell.RotatingSell;
import net.arcanestudios.rotatingsell.managers.DataManager;
import net.arcanestudios.rotatingsell.utils.TimeFormatter;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * PlaceholderAPI expansion for UtopiverseShopGUI-Addon.
 *
 * <p>Placeholders (replace {@code {group}} with Farming, MobDrops, Resources, case-insensitive):
 * <ul>
 *   <li>{@code %rotatingsell_group_{group}%} — hologram line; default looks like {@code Baked Potato 1.15x}</li>
 *   <li>{@code %rotatingsell_item_{group}%} — boosted item name only, e.g. {@code Baked Potato}</li>
 *   <li>{@code %rotatingsell_multiplier_{group}%} — multiplier only, e.g. {@code 1.15x}</li>
 *   <li>{@code %rotatingsell_remaining_{group}%} — time until next rotation</li>
 *   <li>{@code %rotatingsell_info_{group}%} — item, multiplier, and time</li>
 * </ul>
 *
 * <p>Message templates in {@code config.yml} under {@code messages:} support:
 * {@code %group%} (canonical group name), {@code %item%} (pretty material name),
 * {@code %multiplier%} (numeric), {@code %time%}.
 */
public class PlaceholderProvider extends PlaceholderExpansion {

    private final RotatingSell plugin;

    public PlaceholderProvider(RotatingSell plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "rotatingsell";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Thalissondev";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String[] parts = params.split("_", 2);
        if (parts.length < 2) {
            return null;
        }

        String type = parts[0].toLowerCase(Locale.ROOT);
        String groupInput = parts[1];

        String canonicalGroup = resolveCanonicalGroupName(groupInput);
        DataManager.ActiveRotation rotation =
                canonicalGroup != null ? plugin.getRotationManager().getActiveRotation(canonicalGroup) : null;

        ConfigurationSection msg = plugin.getConfig().getConfigurationSection("messages");
        String displayGroup = canonicalGroup != null ? canonicalGroup : groupInput;

        if (rotation == null) {
            return switch (type) {
                case "group" -> applyGroupFormat(
                        getFormat(msg, "group-format", "&f%item% &a%multiplier%x"),
                        displayGroup,
                        "N/A", "1.00");

                case "item" -> "N/A";

                case "multiplier" -> "1.00x";

                case "timeframe", "remaining" -> applyTimeFormat(
                        getFormat(msg, "timeframe-format", "&7%time%"), "N/A");

                case "info" -> applyInfoFormat(
                        getFormat(msg, "info-format", "%item%: %multiplier%x - %time%"),
                        displayGroup,
                        "N/A", "1.00", "N/A");

                default -> null;
            };
        }

        String rawItem = rotation.activeItem();
        String displayItem = "UNKNOWN".equalsIgnoreCase(rawItem)
                ? "N/A"
                : TimeFormatter.formatItemName(rawItem);

        String multiplierStr = String.format(Locale.US, "%.2f", rotation.multiplier());
        long remainingMs = rotation.expiresAt() - System.currentTimeMillis();
        String timeStr = TimeFormatter.formatDuration(remainingMs);

        return switch (type) {
            case "group" -> applyGroupFormat(
                    getFormat(msg, "group-format", "&f%item% &a%multiplier%x"),
                    displayGroup,
                    displayItem, multiplierStr);

            case "item" -> displayItem;

            case "multiplier" -> multiplierStr + "x";

            case "timeframe", "remaining" -> applyTimeFormat(
                    getFormat(msg, "timeframe-format", "&7%time%"), timeStr);

            case "info" -> applyInfoFormat(
                    getFormat(msg, "info-format", "%item%: %multiplier%x - %time%"),
                    displayGroup,
                    displayItem, multiplierStr, timeStr);

            default -> null;
        };
    }

    private String getFormat(ConfigurationSection msg, String key, String fallback) {
        return msg != null ? msg.getString(key, fallback) : fallback;
    }

    private String applyGroupFormat(String template, String groupName, String itemName, String multiplier) {
        return colorize(template
                .replace("%group%", groupName)
                .replace("%item%", itemName)
                .replace("%multiplier%", multiplier));
    }

    private String applyTimeFormat(String template, String time) {
        return colorize(template.replace("%time%", time));
    }

    private String applyInfoFormat(String template, String groupName, String itemName, String multiplier, String time) {
        return colorize(template
                .replace("%group%", groupName)
                .replace("%item%", itemName)
                .replace("%multiplier%", multiplier)
                .replace("%time%", time));
    }

    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** Exact config key for the group, or null if unknown. */
    private String resolveCanonicalGroupName(String groupInput) {
        if (plugin.getRotationManager() == null) {
            return null;
        }
        for (String name : plugin.getRotationManager().getGroupNames()) {
            if (name.equalsIgnoreCase(groupInput)) {
                return name;
            }
        }
        return null;
    }
}
