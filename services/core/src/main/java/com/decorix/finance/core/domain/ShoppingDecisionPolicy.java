package com.decorix.finance.core.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Member decisions that change which receipt-rhythm suggestions are shown. */
public final class ShoppingDecisionPolicy {
    private ShoppingDecisionPolicy() {}

    /** Legacy marks use calendar dates and suppress a suggestion for less than one normal interval. */
    public static boolean boughtMarkIsFresh(Instant markedAt, Instant lastPurchasedAt,
                                             int medianIntervalDays, Instant asOf) {
        if (markedAt == null || lastPurchasedAt == null || asOf == null || medianIntervalDays <= 0) return false;
        LocalDate markedDate = markedAt.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate lastPurchaseDate = lastPurchasedAt.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate today = asOf.atZone(ZoneOffset.UTC).toLocalDate();
        long ageDays = ChronoUnit.DAYS.between(markedDate, today);
        return !markedDate.isBefore(lastPurchaseDate) && ageDays >= 0 && ageDays < medianIntervalDays;
    }
}
