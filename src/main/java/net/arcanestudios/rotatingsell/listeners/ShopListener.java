package net.arcanestudios.rotatingsell.listeners;

import net.arcanestudios.rotatingsell.RotatingSell;
import net.arcanestudios.rotatingsell.managers.DataManager;
import net.brcdev.shopgui.ShopGuiPlusApi;
import net.brcdev.shopgui.event.PlayerDataPostLoadEvent;
import net.brcdev.shopgui.event.ShopsPostLoadEvent;
import net.brcdev.shopgui.exception.player.PlayerDataNotLoadedException;
import net.brcdev.shopgui.exception.shop.ShopsNotLoadedException;
import net.brcdev.shopgui.modifier.PriceModifierActionType;
import net.brcdev.shopgui.shop.Shop;
import net.brcdev.shopgui.shop.ShopManager;
import net.brcdev.shopgui.shop.item.ShopItem;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Applies the rotating sell multiplier for ShopGUI+ transactions via the PriceModifier API.
 *
 * <p>For every online player, all tracked materials are reset to their base sell price, then the
 * single active item per group has its sell PriceModifier set to the group's current multiplier.
 * This affects all sell paths uniformly: {@code /shop}, {@code /sell hand}, {@code /sell all},
 * and {@code /sellg} (SellGUI) — because each of them queries the player-aware sell price which
 * already includes the PriceModifier. No per-event price override is applied; the modifier alone
 * is the single source of truth for the boosted price.
 */
public class ShopListener implements Listener {

    private static final double MULTIPLIER_IDENTITY_EPSILON = 1e-6;

    private final RotatingSell plugin;

    public ShopListener(RotatingSell plugin) {
        this.plugin = plugin;
    }

    public void refreshAllOnlineModifiers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            applyModifiersToPlayer(player);
        }
        if (plugin.getConfig().getBoolean("sellgui-integration.log-price-modifier-refresh", false)) {
            plugin.getLogger().info("[RotatingSell] PriceModifiers synced for "
                    + plugin.getServer().getOnlinePlayers().size() + " online player(s).");
        }
    }

    private void applyModifiersToPlayer(Player player) {
        try {
            ShopManager shopManager = ShopGuiPlusApi.getPlugin().getShopManager();
            if (!shopManager.areShopsLoaded()) {
                return;
            }
            Set<Shop> shops = shopManager.getShops();

            for (String materialName : plugin.getRotationManager().getTrackedMaterialNames()) {
                Material material = Material.getMaterial(materialName);
                if (material == null) {
                    continue;
                }
                resetSellModifierOnAllListings(player, shops, material);
            }

            for (String groupName : plugin.getRotationManager().getGroupNames()) {
                DataManager.ActiveRotation active = plugin.getRotationManager().getActiveRotation(groupName);
                if (active == null || Math.abs(active.multiplier() - 1.0) <= MULTIPLIER_IDENTITY_EPSILON) {
                    continue;
                }
                // ShopGUI+ PriceModifier does not support zero or negative values — skip them.
                // Negative multipliers are clamped to 0.01 at rotation time, but guard here too.
                if (active.multiplier() <= 0.0) {
                    continue;
                }
                Material activeMaterial = parseMaterialName(active.activeItem());
                if (activeMaterial == null) {
                    continue;
                }
                applySellModifierOnAllListings(player, shops, activeMaterial, active.multiplier());
            }
        } catch (ShopsNotLoadedException e) {
            plugin.getLogger().fine("Shops not loaded yet; skipping PriceModifier sync for " + player.getName());
        }
    }

    private static boolean shopItemRepresentsMaterial(ShopItem shopItem, Material material) {
        ItemStack item = shopItem.getItem();
        if (item != null && !item.getType().isAir() && item.getType() == material) {
            return true;
        }
        ItemStack placeholder = shopItem.getPlaceholder();
        return placeholder != null && !placeholder.getType().isAir() && placeholder.getType() == material;
    }

    private static void resetSellModifierOnAllListings(Player player, Set<Shop> shops, Material material) {
        for (Shop shop : shops) {
            List<ShopItem> shopItems = shop.getShopItems();
            if (shopItems == null) {
                continue;
            }
            for (ShopItem shopItem : shopItems) {
                if (!shopItemRepresentsMaterial(shopItem, material)) {
                    continue;
                }
                try {
                    ShopGuiPlusApi.resetPriceModifier(player, shopItem, PriceModifierActionType.SELL);
                } catch (PlayerDataNotLoadedException ignored) {
                }
            }
        }
    }

    private static void applySellModifierOnAllListings(
            Player player, Set<Shop> shops, Material material, double multiplier) {
        for (Shop shop : shops) {
            List<ShopItem> shopItems = shop.getShopItems();
            if (shopItems == null) {
                continue;
            }
            for (ShopItem shopItem : shopItems) {
                if (!shopItemRepresentsMaterial(shopItem, material)) {
                    continue;
                }
                try {
                    ShopGuiPlusApi.setPriceModifier(
                            player, shopItem, PriceModifierActionType.SELL, multiplier);
                } catch (PlayerDataNotLoadedException ignored) {
                }
            }
        }
    }

    @EventHandler
    public void onShopsPostLoad(ShopsPostLoadEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, this::refreshAllOnlineModifiers, 1L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> applyModifiersToPlayer(player), 1L);
    }

    @EventHandler
    public void onPlayerDataLoad(PlayerDataPostLoadEvent event) {
        applyModifiersToPlayer(event.getPlayer());
    }

    private static Material parseMaterialName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Material.getMaterial(raw.toUpperCase(Locale.ROOT));
    }

}
