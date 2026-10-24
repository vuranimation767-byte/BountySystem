package dev.bountysystem;

import dev.bountysystem.bounties.BountyDeathListener;
import dev.bountysystem.bounties.BountyManager;
import dev.bountysystem.commands.BountyCommand;
import dev.bountysystem.configuration.BountyConfig;
import dev.bountysystem.economy.EconomyProvider;
import dev.bountysystem.economy.ItemEconomyProvider;
import dev.bountysystem.economy.VaultEconomyProvider;
import dev.bountysystem.gui.GuiService;
import dev.bountysystem.gui.MenuListener;
import dev.bountysystem.storage.BountyStorage;
import dev.bountysystem.storage.SqliteBountyStorage;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * BountySystem plugin entry point.
 *
 * <p>Stage 1: metadata, default configuration and lifecycle logging. Stage 2:
 * core bounty logic and SQLite storage. Stage 3: real payment handling (Vault
 * bridge or item-based mode) and safe kill settlement. Stage 4: commands,
 * permissions, configuration with validation/reload and the bounty GUI.</p>
 */
public final class BountyPlugin extends JavaPlugin {

    private BountyStorage storage;
    private BountyManager bountyManager;
    private GuiService guiService;
    private volatile BountyConfig config;

    @Override
    public void onLoad() {
        getLogger().info("Loading BountySystem " + getPluginMeta().getVersion() + "...");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            config = BountyConfig.load(getConfig(), getLogger());
        } catch (IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "Invalid BountySystem configuration — disabling plugin: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            Path databaseFile = getDataFolder().toPath().resolve("bounties.db");
            storage = new SqliteBountyStorage(databaseFile, getLogger());
            storage.open();

            EconomyProvider economy = resolveEconomy(config);
            bountyManager = new BountyManager(getLogger(), config, storage, economy);
            int restored = bountyManager.loadFromStorage();
            bountyManager.reconcile();

            guiService = new GuiService(this, this::config, bountyManager);
            Bukkit.getPluginManager().registerEvents(new BountyDeathListener(bountyManager, this::config), this);
            Bukkit.getPluginManager().registerEvents(new MenuListener(this, guiService), this);

            BountyCommand commands = new BountyCommand(this, bountyManager, guiService);
            PluginCommand bountyCommand = getCommand("bounty");
            if (bountyCommand == null) {
                throw new IllegalStateException("plugin.yml does not declare the 'bounty' command");
            }
            bountyCommand.setExecutor(commands);
            bountyCommand.setTabCompleter(commands);

            getLogger().info("BountySystem " + getPluginMeta().getVersion()
                    + " enabled (economy=" + (economy == null ? "NONE — bounties disabled" : economy.name())
                    + ", " + restored + " active bounty record(s) restored).");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialise BountySystem — disabling plugin.", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    /** Current (validated) configuration; swaps on successful reloads. */
    private BountyConfig config() {
        return config;
    }

    public BountyConfig bountyConfig() {
        return config;
    }

    /** Cached display name of any player (fallback "unknown"); used by the chat/ledger output. */
    public String displayNameOf(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayer(playerId);
        return cached.getName() == null ? "unknown" : cached.getName();
    }

    /**
     * {@code /bounty reload}: re-reads and validates the configuration before applying.
     * Limits, cooldown, refund ratio, messages and GUI text apply live; the economy
     * provider is fixed at enable time, so an economy change requires a restart.
     */
    public void reloadBounties(CommandSender feedback) {
        BountyConfig current = config;
        reloadConfig();
        BountyConfig fresh;
        try {
            fresh = BountyConfig.load(getConfig(), getLogger());
        } catch (IllegalArgumentException e) {
            // validation first: the old, known-good configuration stays active
            getLogger().log(Level.SEVERE, "Reload refused — invalid configuration: " + e.getMessage());
            feedback.sendMessage("§cReload refused: §f" + e.getMessage() + "§c. The old settings stay active.");
            return;
        }

        BountyConfig.Messages messages = fresh.messages();
        boolean economyChanged = current == null
                || fresh.economyMode() != current.economyMode()
                || fresh.itemCurrency() != current.itemCurrency()
                || fresh.itemValue() != current.itemValue();

        config = fresh; // supplier consumers (manager, GUI, death listener) see the new instance
        bountyManager.applyReloadedConfig(fresh);

        feedback.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(messages.reloadSuccess));
        if (economyChanged) {
            feedback.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacyAmpersand().deserialize(messages.prefix + messages.reloadEconomyRestart));
        }
    }

    /** Resolves the configured economy, or {@code null} (with a clear warning) when none is available. */
    private EconomyProvider resolveEconomy(BountyConfig config) {
        return switch (config.economyMode()) {
            case ITEMS -> new ItemEconomyProvider(config.itemCurrency(), config.itemValue());
            case VAULT -> {
                Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
                if (vault == null) {
                    getLogger().warning("economy.mode is VAULT but the Vault plugin is not installed — "
                            + "install Vault plus an economy plugin, or set economy.mode: 'ITEMS'. "
                            + "Bounties are disabled this session.");
                    yield null;
                }
                try {
                    EconomyProvider vaultEconomy = VaultEconomyProvider.create(this);
                    if (vaultEconomy == null) {
                        getLogger().warning("Vault is installed but no economy plugin has registered with it — "
                                + "install an economy plugin (e.g. EssentialsX). Bounties are disabled this session.");
                    }
                    yield vaultEconomy;
                } catch (Throwable t) {
                    getLogger().log(Level.SEVERE, "Failed to hook the Vault economy — bounties are disabled this session.", t);
                    yield null;
                }
            }
        };
    }

    @Override
    public void onDisable() {
        if (guiService != null) {
            for (UUID open : Bukkit.getOnlinePlayers().stream()
                    .map(Player::getUniqueId)
                    .toList()) {
                guiService.closed(open);
            }
        }
        if (bountyManager != null) {
            bountyManager.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
        getLogger().info("BountySystem disabled.");
    }
}
