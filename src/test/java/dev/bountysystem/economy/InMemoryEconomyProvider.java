package dev.bountysystem.economy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Test-only in-memory economy. Deliberately placed in the TEST sourceset so it
 * can never end up inside the shipped plugin jar or be used as a production economy.
 */
public class InMemoryEconomyProvider implements EconomyProvider {

    private final Map<UUID, Double> balances = new HashMap<>();
    private final Object lock = new Object();

    public void setBalance(UUID playerId, double balance) {
        synchronized (lock) {
            balances.put(playerId, balance);
        }
    }

    public double balance(UUID playerId) {
        synchronized (lock) {
            return balances.getOrDefault(playerId, 0.0);
        }
    }

    @Override
    public String name() {
        return "in-memory-test";
    }

    @Override
    public boolean withdraw(UUID playerId, double amount) {
        synchronized (lock) {
            double current = balances.getOrDefault(playerId, 0.0);
            if (current < amount) {
                return false;
            }
            balances.put(playerId, current - amount);
            return true;
        }
    }

    @Override
    public boolean deposit(UUID playerId, double amount) {
        synchronized (lock) {
            balances.put(playerId, balances.getOrDefault(playerId, 0.0) + amount);
        }
        return true;
    }
}
