package dev.bountysystem.gui;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pure state machine of the placement workflow: target → amount → confirm.
 *
 * <p>All Bukkit/economy access is delegated to the injected
 * {@link ConfirmHandler}, so this class is unit-testable without a server
 * runtime. The confirm click is debounced and one-shot: repeated rapid clicks
 * invoke the handler once, and a failed attempt must call {@link #rearm()}
 * before it can be clicked again.</p>
 */
public final class PlaceFlow {

    /** Receives the final confirmed placement decision. */
    @FunctionalInterface
    public interface ConfirmHandler {
        void place(UUID placerId, UUID targetId, double amount);
    }

    public enum Stage { IDLE, CHOOSE_TARGET, CHOOSE_AMOUNT, AWAITING_INPUT, CONFIRM }

    private final ConfirmHandler handler;
    private final Supplier<Long> clock;
    private final long debounceMillis;

    private Stage stage = Stage.IDLE;
    private UUID targetId;
    private String targetName;
    private double amount = Double.NaN;
    private boolean confirmedOnce;
    private long lastConfirmAt;
    private boolean consumed;

    public PlaceFlow(ConfirmHandler handler, Supplier<Long> clock, long debounceMillis) {
        this.handler = handler;
        this.clock = clock;
        this.debounceMillis = debounceMillis;
    }

    public void begin() {
        reset();
        stage = Stage.CHOOSE_TARGET;
    }

    public void chooseTarget(UUID targetId, String targetName) {
        this.targetId = targetId;
        this.targetName = targetName;
        this.stage = Stage.CHOOSE_AMOUNT;
    }

    public void chooseAmount(double amount) {
        this.amount = amount;
        this.stage = Stage.CONFIRM;
        this.consumed = false;
    }

    /** Switches the flow to wait for a chat-typed amount (cancel word aborts back to presets). */
    public void requestCustomInput() {
        if (stage == Stage.CHOOSE_AMOUNT || stage == Stage.CONFIRM) {
            stage = Stage.AWAITING_INPUT;
        }
    }

    /** Chat amount entry completed or aborted; leaves the AWAITING_INPUT state. */
    public void cancelInput() {
        if (stage == Stage.AWAITING_INPUT) {
            stage = Stage.CHOOSE_AMOUNT;
        }
    }

    /**
     * Confirm click. Idempotent within the debounce window and after a consumed
     * confirm — the handler runs at most once per confirmed click.
     */
    public boolean confirm(UUID placerId) {
        if (stage != Stage.CONFIRM || consumed) {
            return false;
        }
        long now = clock.get();
        if (confirmedOnce && now - lastConfirmAt < debounceMillis) {
            return false; // rapid double-click protection
        }
        lastConfirmAt = now;
        confirmedOnce = true;
        consumed = true;
        handler.place(placerId, targetId, amount);
        return true;
    }

    /** Re-arms confirm after a failed attempt (e.g. insufficient funds). */
    public void rearm() {
        if (stage == Stage.CONFIRM) {
            consumed = false;
        }
    }

    /** Steps the flow one stage back (confirm → amount → target). */
    public void back() {
        switch (stage) {
            case CONFIRM -> {
                stage = Stage.CHOOSE_AMOUNT;
                consumed = false;
            }
            case AWAITING_INPUT, CHOOSE_AMOUNT -> {
                stage = Stage.CHOOSE_TARGET;
                targetId = null; // the pick step chooses again
                targetName = null;
            }
            default -> {
            }
        }
    }

    public void reset() {
        stage = Stage.IDLE;
        targetId = null;
        targetName = null;
        amount = Double.NaN;
        consumed = false;
    }

    public Stage stage() {
        return stage;
    }

    public UUID targetId() {
        return targetId;
    }

    public String targetName() {
        return targetName;
    }

    public double amount() {
        return amount;
    }
}
