package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class MerchantMappingServiceTest {
    @Test
    void mapsInvalidMerchantLabelToBadRequestBeforeDatabaseAccess() {
        var service = new MerchantMappingService(null, null);

        var error = assertThrows(ResponseStatusException.class,
                () -> service.save(UUID.randomUUID(), "subject", new MerchantMappingApi.MappingRequest(" \n ", "еда")));

        assertEquals(400, error.getStatusCode().value());
    }
}
