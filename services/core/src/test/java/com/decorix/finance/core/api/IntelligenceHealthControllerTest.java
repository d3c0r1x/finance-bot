package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.server.ResponseStatusException;

class IntelligenceHealthControllerTest {
    @Test
    void requiresActiveTenantMembershipBeforeReturningCapabilityStatus() {
        var profiles = mock(MemberProfileService.class);
        var client = mock(IntelligenceHealthClient.class);
        var controller = new IntelligenceHealthController(profiles, client);
        var user = mock(OidcUser.class);
        var tenantId = UUID.randomUUID();
        when(user.getSubject()).thenReturn("member-subject");
        when(profiles.listMembers(tenantId, "member-subject")).thenReturn(List.of());
        var expected = new HealthStatusApi.Response(Map.of());
        when(client.status()).thenReturn(expected);

        assertEquals(expected, controller.get(tenantId, user));
        verify(profiles).listMembers(tenantId, "member-subject");
        verify(client).status();
    }

    @Test
    void membershipFailurePreventsHealthProbe() {
        var profiles = mock(MemberProfileService.class);
        var client = mock(IntelligenceHealthClient.class);
        var controller = new IntelligenceHealthController(profiles, client);
        var user = mock(OidcUser.class);
        var tenantId = UUID.randomUUID();
        when(user.getSubject()).thenReturn("outsider");
        when(profiles.listMembers(tenantId, "outsider")).thenThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND));

        assertThrows(ResponseStatusException.class, () -> controller.get(tenantId, user));
        verify(client, never()).status();
    }
}
