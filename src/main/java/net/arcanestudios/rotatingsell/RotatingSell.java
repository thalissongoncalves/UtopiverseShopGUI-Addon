package net.arcanestudios.rotatingsell;

import net.arcanestudios.rotatingsell.commands.RotateSellCommand;
import net.arcanestudios.rotatingsell.integration.sellgui.SellGuiCompanionConfig;
import net.arcanestudios.rotatingsell.integration.sellgui.SellGuiInventoryBridge;
import net.arcanestudios.rotatingsell.listeners.ShopListener;
import net.arcanestudios.rotatingsell.managers.DataManager;
import net.arcanestudios.rotatingsell.managers.RotationManager;
import net.arcanestudios.rotatingsell.placeholders.PlaceholderProvider;
import net.brcdev.shopgui.event.ShopGUIPlusPostEnableEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Main plugin class for UtopiverseShopGUI-Addon.
 *
 * <p>Provides rotating sell price multipliers on top of ShopGUIPlus, with optional
 * SellGUI integration to correct payouts when players use the {@code /sellg} command.
 */
public class RotatingSell extends JavaPlugin implements Listener {

    private static RotatingSell instance;
    private RotationManager rotationManager;
    private DataManager dataManager;
    private ShopListener shopListener;
    private SellGuiCompanionConfig sellGuiCompanionConfig;
    private boolean sellGuiBridgeRegistered;

    /**
     * Pre-registers the SellGUI inventory bridge as early as possible so that our
     * {@code InventoryCloseEvent} listener (LOWEST priority) fires before SellGUI's own
     * listener, allowing us to capture the inventory snapshot reliably.
     *
     * <p>Until {@link #initializeSystem()} runs, the bridge silently ignores events.
     */
    @Override
    public void onLoad() {
        if (!getConfig().getBoolean("sellgui-integration.enabled", true)) {
            return;
        }
        try {
            if (getServer() == null) {
                return;
            }
            sellGuiCompanionConfig = new SellGuiCompanionConfig(this);
            getServer().getPluginManager().registerEvents(
                    new SellGuiInventoryBridge(this, sellGuiCompanionConfig), this);
            sellGuiBridgeRegistered = true;
            getLogger().info("[SellGUI-Bridge] Listener pre-registered (onLoad) — inventory snapshot will be captured before SellGUI processes.");
        } catch (Throwable t) {
            getLogger().log(Level.FINE, "SellGUI listener pre-registration deferred to onEnable: " + t.getMessage());
        }
    }

    @Override
    public void onEnable() {
        instance = this;

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        saveDefaultConfig();
        this.dataManager = new DataManager(this);

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("rotatesell") != null) {
            RotateSellCommand commandExecutor = new RotateSellCommand(this);
            getCommand("rotatesell").setExecutor(commandExecutor);
            getCommand("rotatesell").setTabCompleter(commandExecutor);
        }

        if (getServer().getPluginManager().isPluginEnabled("ShopGUIPlus")) {
            initializeSystem();
        }

        getLogger().log(Level.INFO, "UtopiverseShopGUI-Addon enabled. Initializing integration...");
    }

    @Override
    public void onDisable() {
        if (dataManager != null) {
            dataManager.saveData();
        }
        getLogger().log(Level.INFO, "UtopiverseShopGUI-Addon disabled. Session data saved.");
    }

    public void reloadPlugin() {
        reloadConfig();
        if (dataManager != null) {
            dataManager.loadData();
        }
        if (rotationManager == null) {
            initializeSystem();
        } else {
            rotationManager.reload();
        }
        if (sellGuiCompanionConfig != null) {
            sellGuiCompanionConfig.reload();
        }

        int groupCount = rotationManager != null ? rotationManager.getGroupNames().size() : 0;
        getLogger().info("Config reloaded — " + groupCount + " group(s) active.");
    }

    public void initializeSystem() {
        if (rotationManager == null) {
            this.shopListener = new ShopListener(this);
            getServer().getPluginManager().registerEvents(shopListener, this);

            this.rotationManager = new RotationManager(this);

            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                new PlaceholderProvider(this).register();
            }

            displaySuccessMessage();
        }

        ensureSellGuiBridgeRegistered();
    }

    private void ensureSellGuiBridgeRegistered() {
        if (!getConfig().getBoolean("sellgui-integration.enabled", true)) {
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("ShopGUIPlus-SellGUI")) {
            return;
        }
        if (rotationManager == null || shopListener == null) {
            return;
        }

        if (sellGuiCompanionConfig == null) {
            sellGuiCompanionConfig = new SellGuiCompanionConfig(this);
        } else {
            sellGuiCompanionConfig.reload();
        }

        shopListener.refreshAllOnlineModifiers();

        if (!sellGuiBridgeRegistered) {
            SellGuiInventoryBridge bridge = new SellGuiInventoryBridge(this, sellGuiCompanionConfig);
            getServer().getPluginManager().registerEvents(bridge, this);
            sellGuiBridgeRegistered = true;
            getLogger().info("[SellGUI-Bridge] Listener registered — post-sell boost correction is active.");
        }
    }

    @EventHandler
    public void onShopGUIPlusPostEnable(ShopGUIPlusPostEnableEvent event) {
        initializeSystem();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if ("ShopGUIPlus-SellGUI".equalsIgnoreCase(event.getPlugin().getName())) {
            getServer().getScheduler().runTask(this, () -> {
                initializeSystem();
                getLogger().info("[SellGUI-Bridge] SellGUI loaded — bridge and PriceModifiers updated.");
            });
        }
    }

    private void displaySuccessMessage() {
        int groupCount = rotationManager.getGroupNames().size();
        int itemCount = rotationManager.getGroupConfigs().values().stream()
                .mapToInt(config -> config.items().size())
                .sum();

        getLogger().log(Level.INFO, "--------------------------------------------------");
        getLogger().log(Level.INFO, " UtopiverseShopGUI-Addon successfully integrated!");
        getLogger().log(Level.INFO, " Provider: ShopGUIPlus");
        getLogger().log(Level.INFO, " Mode: Group-Based Rotation");
        getLogger().log(Level.INFO, " Groups: " + groupCount + " | Items tracked: " + itemCount);
        getLogger().log(Level.INFO, "--------------------------------------------------");
    }

    public static RotatingSell getInstance() {
        return instance;
    }

    public RotationManager getRotationManager() {
        return rotationManager;
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public ShopListener getShopListener() {
        return shopListener;
    }
}
