package com.decorix.finance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class DevelopmentConnectionBuilderTest {
    @Test
    fun permitsOnlyTheLocalKeycloakHttpEndpoint() {
        assertTrue(DevelopmentConnectionBuilder.isAllowedForDevelopmentHttp(
            URI("http://localhost:8081/realms/finance/protocol/openid-connect/token")))
        assertFalse(DevelopmentConnectionBuilder.isAllowedForDevelopmentHttp(
            URI("http://localhost:8080/api/v1/me/tenants")))
        assertFalse(DevelopmentConnectionBuilder.isAllowedForDevelopmentHttp(
            URI("http://10.0.2.2:8081/realms/finance")))
        assertFalse(DevelopmentConnectionBuilder.isAllowedForDevelopmentHttp(
            URI("http://identity.example.com/realms/finance")))
    }
}
