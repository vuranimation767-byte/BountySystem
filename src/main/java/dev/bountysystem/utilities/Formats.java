package dev.bountysystem.utilities;

import java.util.Locale;

/** Small text formatting helpers shared by the command layer and the GUI. */
public final class Formats {

    private Formats() {
    }

    /**
     * Money as a player-friendly string: whole amounts without decimals
     * ("250"), fractional amounts with two decimals ("12.50").
     */
    public static String amount(double amount) {
        if (amount == Math.floor(amount) && !Double.isInfinite(amount) && Math.abs(amount) < 1_000_000_000) {
            return Long.toString((long) amount);
        }
        return String.format(Locale.ROOT, "%.2f", amount);
    }
}
