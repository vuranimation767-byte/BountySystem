package dev.bountysystem.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import java.util.UUID;

/**
 * Vault-backed economy provider (the standard Paper economy bridge).
 *
 * <p>Constructed only after BountyPlugin confirmed the Vault plugin is present;
 * {@link #create} returns {@code null} when Vault is installed but no economy
 * plugin has registered its service with Vault.</p>
 */
public final class VaultEconomyProvider implements EconomyProvider {

    private final Economy vault;

    private VaultEconomyProvider(Economy vault) {
        this.vault = vault;
    }

    /** @return a provider, or {@code null} when no economy is registered with Vault */
    public static VaultEconomyProvider create(Plugin plugin) {
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : new VaultEconomyProvider(registration.getProvider());
    }

    @Override
    public String name() {
        return "Vault/" + vault.getName();
    }

    @Override
    public boolean withdraw(UUID playerId, double amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return vault.withdrawPlayer(player, amount).transactionSuccess();
    }

    @Override
    public boolean deposit(UUID playerId, double amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return vault.depositPlayer(player, amount).transactionSuccess();
    }
}
