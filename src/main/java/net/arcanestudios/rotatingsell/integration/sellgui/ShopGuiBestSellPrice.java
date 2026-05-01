package net.arcanestudios.rotatingsell.integration.sellgui;

import net.brcdev.shopgui.ShopGuiPlusApi;
import net.brcdev.shopgui.exception.shop.ShopsNotLoadedException;
import net.brcdev.shopgui.shop.Shop;
import net.brcdev.shopgui.shop.ShopManager;
import net.brcdev.shopgui.shop.item.ShopItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Finds the highest base sell price (without any player {@code PriceModifier}) for an item
 * stack across all shops and pages.
 *
 * <p>SellGUI calls {@code getItemStackPriceSell(player, stack)}, which already includes the
 * active {@code PriceModifier}. Using the player-context API would therefore double-apply
 * the multiplier. This class instead uses {@code getSellPriceForAmount(int)} — the raw,
 * unmodified price — so the caller can then apply exactly one multiplier on top.
 */
public final class ShopGuiBestSellPrice {

    private ShopGuiBestSellPrice() {}

    /**
     * Returns the highest base sell price (without player / PriceModifier) for the given stack,
     * scanning every shop and page known to ShopGUI+.
     *
     * @return the best price found, or {@code -1.0} if no listing matches the stack's material
     */
    public static double maxBaseSellPriceForStack(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return -1.0;
        }
        try {
            ShopManager shopManager = ShopGuiPlusApi.getPlugin().getShopManager();
            if (!shopManager.areShopsLoaded()) {
                return ShopGuiPlusApi.getItemStackPriceSell(stack);
            }
            Set<Shop> shops = shopManager.getShops();
            Material material = stack.getType();
            int amount = Math.max(1, stack.getAmount());
            double best = -1.0;
            for (Shop shop : shops) {
                for (ShopItem shopItem : collectAllShopItems(shop)) {
                    if (!shopItemRepresentsMaterial(shopItem, material)) {
                        continue;
                    }
                    double price = safePrice(shopItem.getSellPriceForAmount(amount));
                    if (price >= 0.0 && price > best) {
                        best = price;
                    }
                }
            }
            if (best < 0.0) {
                return ShopGuiPlusApi.getItemStackPriceSell(stack);
            }
            return best;
        } catch (ShopsNotLoadedException e) {
            return ShopGuiPlusApi.getItemStackPriceSell(stack);
        } catch (Throwable t) {
            return ShopGuiPlusApi.getItemStackPriceSell(stack);
        }
    }

    private static List<ShopItem> collectAllShopItems(Shop shop) {
        IdentityHashMap<ShopItem, Boolean> seen = new IdentityHashMap<>();
        addShopItemList(shop.getShopItems(), seen);

        for (String methodName : new String[]{"getPages", "getShopPages", "getMenuPages"}) {
            try {
                Method method = shop.getClass().getMethod(methodName);
                Object pages = method.invoke(shop);
                if (pages instanceof Iterable<?> iterable) {
                    for (Object page : iterable) {
                        addFromPageObject(page, seen);
                    }
                }
            } catch (ReflectiveOperationException | ClassCastException ignored) {
            }
        }

        for (int pageIndex = 0; pageIndex < 32; pageIndex++) {
            Object pageObject = tryGetShopPage(shop, pageIndex);
            if (pageObject == null) {
                break;
            }
            int sizeBefore = seen.size();
            addFromPageObject(pageObject, seen);
            if (seen.size() == sizeBefore && pageIndex > 0) {
                break;
            }
        }

        return new ArrayList<>(seen.keySet());
    }

    private static Object tryGetShopPage(Shop shop, int pageIndex) {
        for (String methodName : new String[]{"getShopPage", "getPage"}) {
            try {
                Method method = shop.getClass().getMethod(methodName, int.class);
                method.setAccessible(true);
                return method.invoke(shop, pageIndex);
            } catch (ReflectiveOperationException | ClassCastException ignored) {
            }
        }
        return null;
    }

    private static void addFromPageObject(Object page, IdentityHashMap<ShopItem, Boolean> seen) {
        if (page == null) {
            return;
        }
        for (String methodName : new String[]{"getShopItems", "getItems", "getShopItemList", "getItemList"}) {
            try {
                Method method = page.getClass().getMethod(methodName);
                Object result = method.invoke(page);
                if (result instanceof List<?> list) {
                    for (Object entry : list) {
                        if (entry instanceof ShopItem shopItem) {
                            seen.put(shopItem, Boolean.TRUE);
                        }
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private static void addShopItemList(List<ShopItem> list, IdentityHashMap<ShopItem, Boolean> seen) {
        if (list == null) {
            return;
        }
        for (ShopItem shopItem : list) {
            if (shopItem != null) {
                seen.put(shopItem, Boolean.TRUE);
            }
        }
    }

    static boolean shopItemRepresentsMaterial(ShopItem shopItem, Material material) {
        ItemStack item = shopItem.getItem();
        if (item != null && !item.getType().isAir() && item.getType() == material) {
            return true;
        }
        ItemStack placeholder = shopItem.getPlaceholder();
        return placeholder != null && !placeholder.getType().isAir() && placeholder.getType() == material;
    }

    private static double safePrice(double value) {
        if (Double.isNaN(value) || value < 0.0) {
            return -1.0;
        }
        return value;
    }
}
