package net.arcanestudios.rotatingsell.commands;

import net.arcanestudios.rotatingsell.RotatingSell;
import net.arcanestudios.rotatingsell.managers.DataManager;
import net.arcanestudios.rotatingsell.utils.TimeFormatter;
import net.brcdev.shopgui.ShopGuiPlusApi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Locale;

public class RotateSellCommand implements CommandExecutor, TabCompleter {

    private final RotatingSell plugin;

    public RotateSellCommand(RotatingSell plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!sender.hasPermission("rotatesell.admin")) {
            sender.sendMessage(ChatColor.RED + "✖ You don't have permission to perform this action.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "info" -> handleInfo(sender, args);
            case "rotate" -> handleRotate(sender, args);
            case "debug" -> handleDebug(sender, args);
            default -> sender.sendMessage(ChatColor.RED + "✖ Unknown command. Use /rotatesell for help.");
        }

        return true;
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadPlugin();
        sender.sendMessage(
                ChatColor.GREEN + "✔ " + ChatColor.WHITE + "System reloaded! Config and timers have been updated.");
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (plugin.getRotationManager() == null) {
            sender.sendMessage(ChatColor.RED + "✖ The rotation system is not initialized yet.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "✖ Please specify a group. Usage: /rotatesell info <group>");
            return;
        }

        String groupName = resolveGroupName(args[1]);
        if (groupName == null) {
            sender.sendMessage(ChatColor.RED + "✖ Group not found: " + ChatColor.WHITE + args[1]);
            return;
        }

        DataManager.ActiveRotation rotation = plugin.getRotationManager().getActiveRotation(groupName);
        if (rotation == null) {
            sender.sendMessage(ChatColor.RED + "✖ No active boost for group: " + ChatColor.WHITE + groupName);
            return;
        }

        long remaining = rotation.expiresAt() - System.currentTimeMillis();
        sender.sendMessage(ChatColor.GOLD + " " + ChatColor.BOLD + "Group Status: " + ChatColor.YELLOW + groupName);
        sender.sendMessage(ChatColor.GRAY + " » Active Item: " + ChatColor.YELLOW
                + TimeFormatter.formatItemName(rotation.activeItem()));
        sender.sendMessage(ChatColor.GRAY + " » Active Multiplier: " + ChatColor.GREEN
                + String.format(Locale.US, "%.2f", rotation.multiplier()) + "x");
        sender.sendMessage(
                ChatColor.GRAY + " » Expiration: " + ChatColor.AQUA + TimeFormatter.formatDuration(remaining));
    }

    private void handleRotate(CommandSender sender, String[] args) {
        if (plugin.getRotationManager() == null) {
            sender.sendMessage(ChatColor.RED + "✖ The rotation system is not initialized yet.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "✖ Usage: /rotatesell rotate <group>");
            return;
        }

        String groupName = resolveGroupName(args[1]);
        if (groupName == null) {
            sender.sendMessage(ChatColor.RED + "✖ " + ChatColor.GRAY + "Group not found: " + ChatColor.WHITE + args[1]);
            return;
        }

        plugin.getRotationManager().forceRotate(groupName);
        DataManager.ActiveRotation rotation = plugin.getRotationManager().getActiveRotation(groupName);
        String newMultiplier = rotation != null ? String.format(Locale.US, "%.2f", rotation.multiplier()) + "x" : "?";
        String newItem = rotation != null ? rotation.activeItem() : "?";
        sender.sendMessage(ChatColor.GREEN + "✔ " + ChatColor.WHITE + "Rotation triggered for group "
                + ChatColor.YELLOW + groupName + ChatColor.WHITE + ". New boost: "
                + ChatColor.YELLOW + newItem + ChatColor.WHITE + " (" + ChatColor.GREEN + newMultiplier + ")");
    }

    private void handleDebug(CommandSender sender, String[] args) {
        if (plugin.getRotationManager() == null) {
            sender.sendMessage(ChatColor.RED + "✖ The rotation system is not initialized yet.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "✖ Usage: /rotatesell debug <player> <MATERIAL>");
            sender.sendMessage(ChatColor.GRAY + " Example: /rotatesell debug Steve BEETROOT");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "✖ Player not found or not online: " + ChatColor.WHITE + args[1]);
            return;
        }

        Material material = Material.getMaterial(args[2].toUpperCase(Locale.ROOT));
        if (material == null) {
            sender.sendMessage(ChatColor.RED + "✖ Unknown material: " + ChatColor.WHITE + args[2]);
            return;
        }

        ItemStack stack = new ItemStack(material, 1);
        double priceNoPlayer;
        double priceWithPlayer;
        try {
            priceNoPlayer = ShopGuiPlusApi.getItemStackPriceSell(stack);
        } catch (Exception e) {
            priceNoPlayer = -1.0;
        }
        try {
            priceWithPlayer = ShopGuiPlusApi.getItemStackPriceSell(target, stack);
        } catch (Exception e) {
            priceWithPlayer = -1.0;
        }

        double ourMultiplier = plugin.getRotationManager().getMultiplier(material);
        // Expected = base (no player, no PriceModifier) × multiplier (no rounding — 4dp precision)
        double expectedShop = priceNoPlayer >= 0
                ? priceNoPlayer * ourMultiplier
                : priceWithPlayer >= 0
                        ? priceWithPlayer
                        : -1.0;

        sender.sendMessage(ChatColor.GOLD + ChatColor.BOLD.toString() + "▶ RotatingSell Debug: "
                + ChatColor.YELLOW + material.name() + ChatColor.GOLD + " for " + ChatColor.YELLOW + target.getName());
        sender.sendMessage(ChatColor.GRAY + " » Base sell price (no player):  "
                + ChatColor.WHITE + formatPrice(priceNoPlayer));
        sender.sendMessage(ChatColor.GRAY + " » Effective sell price (with player rank modifier):  "
                + ChatColor.WHITE + formatPrice(priceWithPlayer));
        sender.sendMessage(ChatColor.GRAY + " » Our rotation multiplier:  "
                + ChatColor.GREEN + String.format(Locale.US, "%.4f", ourMultiplier) + "x");
        sender.sendMessage(ChatColor.GRAY + " » Expected sell price (base × mult):  "
                + ChatColor.AQUA + formatPrice(expectedShop));
        sender.sendMessage(ChatColor.GRAY + " » (Applied via PriceModifier — same for /shop, /sell hand and /sellg)");
    }

    private static String formatPrice(double price) {
        return price < 0 ? "N/A (not listed in any shop)" : String.format(Locale.US, "$%.4f", price);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + " " + ChatColor.BOLD + "UtopiverseShopGUI Management");
        sender.sendMessage(ChatColor.YELLOW + "/rotatesell reload " + ChatColor.GRAY + "- Reload entire system");
        sender.sendMessage(ChatColor.YELLOW + "/rotatesell info <group> " + ChatColor.GRAY + "- Current boost status");
        sender.sendMessage(
                ChatColor.YELLOW + "/rotatesell rotate <group> " + ChatColor.GRAY + "- Force instant rotate");
        sender.sendMessage(ChatColor.YELLOW + "/rotatesell debug <player> <material> "
                + ChatColor.GRAY + "- Show sell price breakdown for a player");
    }

    /**
     * Finds the exact group name from config, case-insensitive.
     * Returns null if no match is found.
     */
    private String resolveGroupName(String input) {
        if (plugin.getRotationManager() == null)
            return null;
        for (String name : plugin.getRotationManager().getGroupNames()) {
            if (name.equalsIgnoreCase(input))
                return name;
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
            @NotNull String[] args) {
        if (!sender.hasPermission("rotatesell.admin"))
            return Collections.emptyList();

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.add("reload");
            completions.add("info");
            completions.add("rotate");
            completions.add("debug");
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("rotate")) {
                if (plugin.getRotationManager() != null) {
                    completions.addAll(plugin.getRotationManager().getGroupNames());
                }
            } else if (args[0].equalsIgnoreCase("debug")) {
                Bukkit.getOnlinePlayers().forEach(p -> completions.add(p.getName()));
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("debug")) {
            if (plugin.getRotationManager() != null) {
                completions.addAll(plugin.getRotationManager().getTrackedMaterialNames());
            }
        }

        String input = args[args.length - 1].toLowerCase();
        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(input))
                .sorted()
                .collect(Collectors.toList());
    }
}
