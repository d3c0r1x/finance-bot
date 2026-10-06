package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GoalServiceTest {
    @Test
    void boundsTheCombinedGoPayloadWithoutIntegerOverflow() {
        assertTrue(GoalService.withinInputBound(25_000, 25_000));
        assertFalse(GoalService.withinInputBound(25_000, 25_001));
        assertFalse(GoalService.withinInputBound(Integer.MAX_VALUE, 1));
        assertFalse(GoalService.withinInputBound(1, -1));
    }
}
