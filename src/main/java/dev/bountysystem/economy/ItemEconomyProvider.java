package dev.bountysystem.economy;

import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Item-based payment mode: bounties are paid with a real, configurable inventory
 * item instead of an economy plugin (intended for servers without Vault/economy).
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>amounts must be a whole multiple of the configured per-item value</li>
 *   <li>the item balance is counted and validated before anything is removed</li>
 *   <li>payments only work for online players; if someone is offline the
 *       deposit reports {@code false} (no items moved) and the transaction
 *       ledger marks it for a later retry</li>
 * </ul>
 */
public final class ItemEconomyProvider implements EconomyProvider {

    private final Material currency;
    private final double itemValue;

    public ItemEconomyProvider(Material currency, double itemValue) {
        this.currency = currency;
        this.itemValue = itemValue;
    }

    @Override
    public String name() {
        return "items:" + currency.name();
    }

    @Override
    public boolean withdraw(UUID playerId, double amount) {
        long units = unitsFor(amount);
        if (units <= 0) {
            return false; // not a whole multiple of the item value
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return false; // item payment requires the player to be online
        }
        if (countItems(player) < units) {
            return false; // balance validated before charging
        }
        takeItems(player, units);
        return true;
    }

    @Override
    public boolean deposit(UUID playerId, double amount) {
        long units = unitsFor(amount);
        if (units <= 0) {
            return false;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return false; // offline: the ledger entry gets retried later
        }
        int remaining = (int) units;
        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, currency.getMaxStackSize());
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(currency, chunk));
            if (!leftover.isEmpty()) {
                // inventory full — drop the rest at their feet so payment never vanishes
                for (ItemStack stack : leftover.values()) {
                    player.getWorld().dropItem(player.getLocation(), stack);
                }
            }
            remaining -= chunk;
        }
        return true;
    }

    private long unitsFor(double amount) {
        double exact = amount / itemValue;
        long units = Math.round(exact);
        if (Math.abs(exact - units) > 1e-9) {
            return -1; // fractional items are not payable
        }
        return units;
    }

    private long countItems(Player player) {
        long count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == currency) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private void takeItems(Player player, long units) {
        int remaining = (int) units;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (remaining <= 0) {
                return;
            }
            if (stack == null || stack.getType() != currency) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take); // amount 0 removes the item
            remaining -= take;
        }
    }
}
