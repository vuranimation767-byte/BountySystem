package dev.bountysystem.gui;

import dev.bountysystem.BountyPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * The single security membrane around every bounty menu.
 *
 * <p>Strategy: cancel first, then act. While any bounty menu is open ALL
 * inventory interactions are cancelled — top-inventory clicks (incl. number-key
 * swaps, double-click collection, creative clicks), bottom-inventory clicks
 * (shift-click smuggling) and any drag touching the menu. Only afterwards do
 * whitelisted, session-registered slot positions trigger actions keyed by
 * server-side state, never by item names, lore or titles.</p>
 */
public final class MenuListener implements Listener {

    private final BountyPlugin plugin;
    private final GuiService gui;

    public MenuListener(BountyPlugin plugin, GuiService gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return; // some other plugin's inventory — untouched
        }
        // blanket cancel: shift-clicks, hotbar swaps, double-click collect, creative clicks, everything
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // clicks on the player's own inventory while a menu is open are blocked, silently
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        gui.click(player, holder, event.getRawSlot());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        int menuSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < menuSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder
                && event.getPlayer() instanceof Player player) {
            gui.closed(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        gui.quit(event.getPlayer().getUniqueId());
    }

    /**
     * Captures the chat-typed custom reward amount: the message is consumed
     * (never broadcast) and handled on the main thread, where the flow re-
     * validates and either opens the confirm step or asks again.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        if (!gui.awaitingChatAmount(event.getPlayer().getUniqueId())) {
            return; // normal chat — completely untouched
        }
        event.setCancelled(true);
        String text = event.message() instanceof net.kyori.adventure.text.TextComponent simple
                ? simple.content() // normal chat always arrives as a plain text component
                : net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(event.message());
        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> gui.onChatText(playerId, text));
    }
}
