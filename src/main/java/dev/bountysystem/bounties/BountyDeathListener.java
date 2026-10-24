package dev.bountysystem.bounties;

import dev.bountysystem.configuration.BountyConfig;
import java.util.function.Supplier;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Detects eligible player-kill events and settles the victim's bounties.
 *
 * <p>Eligibility rules: the killer must be another player — suicides (direct or
 * self-attributed projectiles), environmental deaths and mob kills never pay.</p>
 */
public final class BountyDeathListener implements Listener {

    private final BountyManager manager;
    private final Supplier<BountyConfig> configSupplier;

    public BountyDeathListener(BountyManager manager, Supplier<BountyConfig> configSupplier) {
        this.manager = manager;
        this.configSupplier = configSupplier;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) {
            return; // environment or mob death
        }
        if (killer.getUniqueId().equals(victim.getUniqueId())) {
            return; // direct or indirect suicide
        }
        KillPayout settlement = manager.settleDeath(killer.getUniqueId(), victim.getUniqueId());
        if (settlement.payout() > 0.0) {
            String broadcast = configSupplier.get().claimBroadcastFor(
                    killer.getName(), victim.getName(), settlement.payout());
            Bukkit.broadcast(LegacyComponentSerializer.legacyAmpersand().deserialize(broadcast));
        }
    }
}
