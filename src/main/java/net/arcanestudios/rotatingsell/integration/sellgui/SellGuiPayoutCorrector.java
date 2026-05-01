package net.arcanestudios.rotatingsell.integration.sellgui;

import net.arcanestudios.rotatingsell.RotatingSell;
import net.brcdev.shopgui.ShopGuiPlusApi;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Corrects the player's balance after a SellGUI transaction to account for the active sell boost.
 *
 * <h3>How the calculation works</h3>
 * <ul>
 *   <li>The boost is applied exclusively via the ShopGUI+ {@code PriceModifier} API. All sell
 *       paths — {@code /shop}, {@code /sell hand}, and {@code /sellg} — query the player-aware
 *       sell price, which already includes the modifier. No secondary event override exists.</li>
 *   <li>Expected payout per stack: {@code base × multiplier}, where {@code base} is the raw
 *       sell price without any player context (no PriceModifier applied).</li>
 *   <li>SellGUI should already pay the correct amount via the PriceModifier. This corrector
 *       acts as a safety net: if the PriceModifier was not yet applied when SellGUI ran
 *       (e.g. player data not loaded), the delta is deposited or withdrawn via Vault.</li>
 * </ul>
 */
public final class SellGuiPayoutCorrector {

    private static final double MONEY_EPSILON = 0.005;

    private final RotatingSell plugin;

    public SellGuiPayoutCorrector(RotatingSell plugin) {
        this.plugin = plugin;
    }

    public void scheduleCorrect(Player player, List<ItemStack> snapshot, double balanceBefore) {
        if (snapshot.isEmpty()) {
            return;
        }
        List<ItemStack> frozenSnapshot = new ArrayList<>(snapshot.size());
        for (ItemStack stack : snapshot) {
            if (stack != null && !stack.getType().isAir()) {
                frozenSnapshot.add(stack.clone());
            }
        }
        if (frozenSnapshot.isEmpty()) {
            return;
        }
        long delay = Math.max(1L, plugin.getConfig().getLong("sellgui-integration.correction-delay-ticks", 10L));
        plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> applyCorrection(player, frozenSnapshot, balanceBefore, 0), delay);
    }

    private void applyCorrection(Player player, List<ItemStack> stacks, double balanceBefore, int attempt) {
        if (!player.isOnline()) {
            return;
        }
        RegisteredServiceProvider<Economy> economyProvider =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (economyProvider == null) {
            plugin.getLogger().warning("[SellGUI-Payout] Vault not available — boost correction skipped.");
            return;
        }
        Economy economy = economyProvider.getProvider();

        double totalExpected = 0.0;
        int processedLines = 0;

        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            Material material = stack.getType();
            double multiplier = plugin.getRotationManager().getMultiplier(material);

            // base = price without any PriceModifier (no player context).
            // engineWithPlayer = price the ShopGUI+ API returns WITH the player's PriceModifier applied.
            //
            // Expected payout = base × multiplier (single apply via PriceModifier).
            // SellGUI should already pay this amount; the corrector only acts when there is a delta.
            double basePrice = ShopGuiPlusApi.getItemStackPriceSell(stack.clone());
            double engineWithPlayer = ShopGuiPlusApi.getItemStackPriceSell(player, stack.clone());

            if (basePrice < 0.0 && engineWithPlayer < 0.0) {
                continue;
            }

            double effectiveBase = basePrice >= 0.0 ? clampPositive(basePrice) : clampPositive(engineWithPlayer);
            totalExpected += roundMoney(effectiveBase * multiplier);
            processedLines++;
        }

        if (processedLines == 0) {
            return;
        }

        if (Double.isNaN(balanceBefore)) {
            plugin.getLogger().warning("[SellGUI-Payout] Balance before sale is unknown — no deposit made.");
            return;
        }

        double balanceAfter = economy.getBalance(player);
        double actualPayout = roundMoney(balanceAfter - balanceBefore);

        int maxRetries = Math.max(0, plugin.getConfig().getInt("sellgui-integration.correction-max-balance-retries", 3));
        long retryDelay = Math.max(1L, plugin.getConfig().getLong("sellgui-integration.correction-balance-retry-ticks", 15L));

        if (actualPayout <= MONEY_EPSILON && attempt < maxRetries) {
            plugin.getServer().getScheduler().runTaskLater(
                    plugin, () -> applyCorrection(player, stacks, balanceBefore, attempt + 1), retryDelay);
            return;
        }

        if (actualPayout <= MONEY_EPSILON) {
            return;
        }

        double delta = roundMoney(totalExpected - actualPayout);

        if (Math.abs(delta) <= MONEY_EPSILON) {
            return;
        }

        if (delta > 0.0) {
            EconomyResponse response = economy.depositPlayer(player, delta);
            if (!response.transactionSuccess()) {
                plugin.getLogger().log(Level.SEVERE,
                        "[SellGUI-Payout] Failed to deposit " + delta + " to " + player.getName()
                                + ": " + response.errorMessage);
            } else {
                String formattedTotal = economy.format(totalExpected);
                String formattedDelta = economy.format(delta);
                player.sendMessage(
                        "\u00a7aShop > \u00a7fSell with boost: \u00a7a" + formattedTotal
                                + " \u00a77(\u00a7a+" + formattedDelta + " \u00a77boost)");
            }
        } else {
            double excess = -delta;
            EconomyResponse response = economy.withdrawPlayer(player, excess);
            if (!response.transactionSuccess()) {
                plugin.getLogger().log(Level.WARNING,
                        "[SellGUI-Payout] Failed to withdraw excess " + excess + " from " + player.getName()
                                + ": " + response.errorMessage);
            } else {
                String formattedTotal = economy.format(totalExpected);
                String formattedDelta = economy.format(excess);
                player.sendMessage(
                        "\u00a7aShop > \u00a7fSell with boost: \u00a7a" + formattedTotal
                                + " \u00a77(\u00a7c-" + formattedDelta + " \u00a77penalty)");
            }
        }
    }

    private static double clampPositive(double value) {
        if (Double.isNaN(value) || value < 0.0) {
            return 0.0;
        }
        return value;
    }

    private static double roundMoney(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
