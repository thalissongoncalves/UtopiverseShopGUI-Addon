package net.arcanestudios.rotatingsell.integration.sellgui;

import net.arcanestudios.rotatingsell.RotatingSell;
import net.brcdev.shopgui.ShopGuiPlusApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Captures the SellGUI inventory snapshot on close (before SellGUI empties it) and
 * schedules a payout correction for the next tick.
 *
 * <p>Registered at {@link EventPriority#LOWEST} so this listener fires before SellGUI's
 * own close handler, ensuring the inventory contents are still present when we read them.
 */
public final class SellGuiInventoryBridge implements Listener {

    private final RotatingSell plugin;
    private final SellGuiCompanionConfig companion;
    private final SellGuiPayoutCorrector corrector;

    public SellGuiInventoryBridge(RotatingSell plugin, SellGuiCompanionConfig companion) {
        this.plugin = plugin;
        this.companion = companion;
        this.corrector = new SellGuiPayoutCorrector(plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!plugin.getConfig().getBoolean("sellgui-integration.enabled", true)) {
            return;
        }
        if (plugin.getRotationManager() == null) {
            return;
        }
        HumanEntity humanEntity = event.getPlayer();
        if (!(humanEntity instanceof Player player)) {
            return;
        }
        InventoryView view = event.getView();
        if (view.getType() != InventoryType.CHEST) {
            return;
        }
        Inventory topInventory = view.getTopInventory();
        if (topInventory.getSize() != companion.getSlotCount()) {
            return;
        }
        String plainTitle = plainTitle(view);
        String titleKeyword = companion.getTitleContains();
        if (titleKeyword != null && !titleKeyword.isBlank()) {
            if (!plainTitle.toLowerCase(Locale.ROOT).contains(titleKeyword.toLowerCase(Locale.ROOT))) {
                return;
            }
        }
        if (!companion.decorationLayoutMatches(topInventory)) {
            return;
        }
        int contentItemCount = companion.countContentItems(topInventory);
        if (plugin.getConfig().getBoolean("sellgui-integration.require-content-items", false) && contentItemCount == 0) {
            return;
        }

        List<ItemStack> snapshot = snapshotContent(topInventory);
        if (snapshot.isEmpty()) {
            return;
        }

        double balanceBefore = Double.NaN;
        RegisteredServiceProvider<Economy> economyProvider =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (economyProvider != null) {
            balanceBefore = economyProvider.getProvider().getBalance(player);
        } else {
            plugin.getLogger().warning("[SellGUI-Bridge] Vault Economy not found on inventory close — "
                    + "payout correction cannot deposit or withdraw.");
        }

        // Compute the boosted total synchronously before SellGUI processes the transaction,
        // so we can display the correct value in the title override on the next tick.
        final double expectedTotal = computeExpectedTotal(player, snapshot);
        final int totalItemCount = snapshot.stream().mapToInt(ItemStack::getAmount).sum();

        // Override SellGUI's default title (tick 1) with the correct boosted amount (tick 2).
        if (expectedTotal > 0.0) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                RegisteredServiceProvider<Economy> provider =
                        Bukkit.getServicesManager().getRegistration(Economy.class);
                String formattedAmount = provider != null
                        ? provider.getProvider().format(expectedTotal)
                        : String.format(Locale.ROOT, "%.2f", expectedTotal);

                String title = ChatColor.translateAlternateColorCodes('&', "&a&l+" + formattedAmount);
                String subtitle = ChatColor.translateAlternateColorCodes('&',
                        "&7You sold &a&n" + totalItemCount + "&7 items in this batch");
                player.sendTitle(title, subtitle, 10, 70, 20);
            }, 2L);
        }

        corrector.scheduleCorrect(player, snapshot, balanceBefore);
    }

    /**
     * Calculates the expected boosted total using {@code base × multiplier} for each stack.
     *
     * <p>The boost is applied exclusively via the ShopGUI+ PriceModifier, so the sell price for
     * any path ({@code /shop}, {@code /sell hand}, {@code /sellg}) is {@code base × multiplier}.
     */
    private double computeExpectedTotal(Player player, List<ItemStack> stacks) {
        double total = 0.0;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir()) continue;
            // Base price without PriceModifier; fall back to the player-scoped price if unavailable.
            double basePrice = ShopGuiPlusApi.getItemStackPriceSell(stack);
            if (basePrice < 0.0) {
                basePrice = ShopGuiPlusApi.getItemStackPriceSell(player, stack);
            }
            if (basePrice < 0.0) continue;
            double multiplier = plugin.getRotationManager().getMultiplier(stack.getType());
            total += basePrice * multiplier;
        }
        return Math.round(total * 100.0) / 100.0;
    }

    private List<ItemStack> snapshotContent(Inventory topInventory) {
        List<ItemStack> items = new ArrayList<>();
        int size = Math.min(topInventory.getSize(), companion.getSlotCount());
        for (int i = 0; i < size; i++) {
            if (companion.decorationSlots().contains(i)) {
                continue;
            }
            ItemStack stack = topInventory.getItem(i);
            if (stack != null && !stack.getType().isAir()) {
                items.add(stack.clone());
            }
        }
        return items;
    }

    private static String plainTitle(InventoryView view) {
        Component title = view.title();
        return PlainTextComponentSerializer.plainText().serialize(title).trim();
    }
}
