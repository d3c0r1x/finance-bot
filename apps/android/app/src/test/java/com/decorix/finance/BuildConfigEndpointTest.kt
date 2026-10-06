package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildConfigEndpointTest {
    @Test
    fun configuredApiAndIdentityEndpointsAreEmbeddedInTheBuild() {
        assertEquals(System.getProperty("expectedFinanceApiBaseUrl"), BuildConfig.API_BASE_URL)
        assertEquals(System.getProperty("expectedFinanceOidcIssuer"), BuildConfig.OIDC_REALM_URL)
    }
}
