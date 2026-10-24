package dev.bountysystem.economy;

import java.util.UUID;

/**
 * Test economy wrapping {@link InMemoryEconomyProvider}; deposits can be made to
 * fail either by throwing (economy outage) or returning {@code false} (rejection),
 * to exercise the payment-failure paths.
 */
public final class ToggleableEconomyProvider extends InMemoryEconomyProvider {

    public enum DepositFailure { NONE, THROW_EXCEPTION, RETURN_FALSE }

    private volatile DepositFailure depositFailure = DepositFailure.NONE;

    public void setDepositFailure(DepositFailure mode) {
        this.depositFailure = mode;
    }

    @Override
    public boolean deposit(UUID playerId, double amount) {
        if (depositFailure == DepositFailure.THROW_EXCEPTION) {
            throw new IllegalStateException("simulated economy outage");
        }
        if (depositFailure == DepositFailure.RETURN_FALSE) {
            return false;
        }
        return super.deposit(playerId, amount);
    }
}
