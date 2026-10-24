package dev.bountysystem.gui;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Identity marker for BountySystem inventories.
 *
 * <p>Click handling matches against THIS object — never against item names,
 * lore or titles, which a client could display in a spoofed inventory.</p>
 */
public final class MenuHolder implements InventoryHolder {

    private final MenuKey key;
    private final UUID playerId;
    private Inventory inventory;

    public MenuHolder(MenuKey key, UUID playerId) {
        this.key = key;
        this.playerId = playerId;
    }

    public MenuKey key() {
        return key;
    }

    public UUID playerId() {
        return playerId;
    }

    /** Attaches the real inventory right after {@code Bukkit.createInventory(holder, …)}. */
    public void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
