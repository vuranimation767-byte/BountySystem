package dev.bountysystem.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Placement flow state machine: target → amount (preset or chat) →
 * debounced one-shot confirm → handler invoked exactly once.
 */
class PlaceFlowTest {

    private static final UUID PLACER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    record Placement(UUID placer, UUID target, double amount) {
    }

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final List<Placement> placed = new ArrayList<>();
    private final PlaceFlow flow = new PlaceFlow(
            (placer, target, amount) -> placed.add(new Placement(placer, target, amount)),
            clock::get, 250);

    @Test
    void flowsThroughTargetAmountConfirm() {
        flow.begin();
        assertEquals(PlaceFlow.Stage.CHOOSE_TARGET, flow.stage());

        flow.chooseTarget(TARGET, "Target");
        assertEquals(PlaceFlow.Stage.CHOOSE_AMOUNT, flow.stage());
        assertEquals(TARGET, flow.targetId());
        assertEquals("Target", flow.targetName());

        flow.chooseAmount(250.0);
        assertEquals(PlaceFlow.Stage.CONFIRM, flow.stage());

        assertTrue(flow.confirm(PLACER));
        assertEquals(1, placed.size());
        assertEquals(new Placement(PLACER, TARGET, 250.0), placed.getFirst());

        // consumed: a second confirm must not invoke the handler again
        assertFalse(flow.confirm(PLACER));
        assertEquals(1, placed.size());
    }

    @Test
    void onlyConfirmStageExecutesTheHandler() {
        assertFalse(flow.confirm(PLACER));    // IDLE
        assertEquals(0, placed.size());

        flow.begin();
        assertFalse(flow.confirm(PLACER));    // CHOOSE_TARGET
        flow.chooseTarget(TARGET, "Target");
        assertFalse(flow.confirm(PLACER));    // CHOOSE_AMOUNT
        assertEquals(0, placed.size());

        flow.chooseAmount(50.0);
        assertTrue(flow.confirm(PLACER));
        assertEquals(1, placed.size());
    }

    @Test
    void rapidConfirmsAreDebounced() {
        flow.begin();
        flow.chooseTarget(TARGET, "Target");
        flow.chooseAmount(100.0);

        assertTrue(flow.confirm(PLACER));
        clock.addAndGet(100); // inside the 250ms debounce window
        flow.rearm();          // simulate a failed-attempt retry arm
        assertFalse(flow.confirm(PLACER));
        assertEquals(1, placed.size());

        clock.addAndGet(300); // outside the debounce window
        assertTrue(flow.confirm(PLACER));
        assertEquals(2, placed.size());
    }

    @Test
    void backStepsOneStageAtATime() {
        flow.begin();
        flow.chooseTarget(TARGET, "Target");
        flow.chooseAmount(250.0);
        assertEquals(PlaceFlow.Stage.CONFIRM, flow.stage());

        flow.back();
        assertEquals(PlaceFlow.Stage.CHOOSE_AMOUNT, flow.stage());
        flow.back();
        assertEquals(PlaceFlow.Stage.CHOOSE_TARGET, flow.stage());
        assertNull(flow.targetId()); // stepping past the pick clears the choice
    }

    @Test
    void customChatInputCyclesThroughAwaiting() {
        flow.begin();
        flow.chooseTarget(TARGET, "Target");
        assertEquals(PlaceFlow.Stage.CHOOSE_AMOUNT, flow.stage());

        flow.requestCustomInput();
        assertEquals(PlaceFlow.Stage.AWAITING_INPUT, flow.stage());

        flow.cancelInput();
        assertEquals(PlaceFlow.Stage.CHOOSE_AMOUNT, flow.stage());

        // requestCustomInput from an unrelated stage must not hijack the flow
        flow.begin(); // CHOOSE_TARGET
        flow.requestCustomInput();
        assertEquals(PlaceFlow.Stage.CHOOSE_TARGET, flow.stage());
    }

    @Test
    void rearmAllowsRetryAfterFailure() {
        flow.begin();
        flow.chooseTarget(TARGET, "Target");
        flow.chooseAmount(100.0);
        assertTrue(flow.confirm(PLACER)); // handler "fails" in the GUI service and calls rearm()

        clock.addAndGet(400);
        flow.rearm();
        assertTrue(flow.confirm(PLACER));
        assertEquals(2, placed.size());
    }

    @Test
    void resetReturnsToIdle() {
        flow.begin();
        flow.chooseTarget(TARGET, "Target");
        flow.chooseAmount(100.0);
        flow.reset();
        assertEquals(PlaceFlow.Stage.IDLE, flow.stage());
        assertNull(flow.targetId());
        assertNull(flow.targetName());
        assertTrue(Double.isNaN(flow.amount()));
        assertFalse(flow.confirm(PLACER)); // idle → never executes
        assertEquals(0, placed.size());
    }
}
