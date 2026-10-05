package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.decorix.finance.core.api.ReceiptApi.CreateRequest;
import com.decorix.finance.core.api.ReceiptApi.ItemInput;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ReceiptServiceTest {
    @Test
    void cashTotalAndItemTotalStayIndependentWhenTheyDisagree() {
        var prepared = ReceiptService.validateRequest(new CreateRequest(null, "150.00", "Market", null,
                List.of(item("Tea", "100.00"))));

        assertEquals(new BigDecimal("150.00"), prepared.cashTotal());
        assertEquals(new BigDecimal("100.00"), prepared.itemsTotal());
        assertEquals("review_required", prepared.state());
    }

    @Test
    void exactThreePercentDifferenceIsAcceptedWithoutRoundingUp() {
        var atBoundary = ReceiptService.validateRequest(new CreateRequest(null, "100.00", null, null,
                List.of(item("Tea", "103.00"))));
        var outsideBoundary = ReceiptService.validateRequest(new CreateRequest(null, "100.00", null, null,
                List.of(item("Tea", "103.01"))));

        assertEquals("draft", atBoundary.state());
        assertEquals("review_required", outsideBoundary.state());
    }

    @Test
    void missingLineTotalKeepsItemsTotalUnknown() {
        var prepared = ReceiptService.validateRequest(new CreateRequest(null, "100.00", null, null,
                List.of(new ItemInput("Tea", "1", "100.00", null))));

        assertNull(prepared.itemsTotal());
        assertEquals("review_required", prepared.state());
    }

    @Test
    void rejectsZeroCashAndExcessQuantityPrecision() {
        assertThrows(ResponseStatusException.class, () -> ReceiptService.validateRequest(
                new CreateRequest(null, "0.00", null, null, List.of())));
        assertThrows(ResponseStatusException.class, () -> ReceiptService.validateRequest(
                new CreateRequest(null, "1.00", null, null,
                        List.of(new ItemInput("Tea", "0.0000001", null, null)))));
    }

    private static ItemInput item(String name, String lineSum) {
        return new ItemInput(name, "1", lineSum, lineSum);
    }
}
