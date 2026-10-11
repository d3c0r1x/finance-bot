package com.decorix.finance

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FinanceHealthModelsTest {
    @Test fun parsesRequiredCapabilitiesAndNullableDiagnostics() {
        val health = FinanceModels.intelligenceHealth(response(
            localAi = capability("available", null),
            receiptVision = capability("unavailable", "OLLAMA_UNAVAILABLE"),
            receiptOcr = capability("disabled", "TESSERACT_MISSING"),
        ))

        assertEquals(setOf("localAi", "receiptVision", "receiptOcr"), health.capabilities.keys)
        assertEquals("available", health.capabilities.getValue("localAi").status)
        assertNull(health.capabilities.getValue("localAi").diagnosticCode)
        assertEquals("unavailable", health.capabilities.getValue("receiptVision").status)
        assertEquals("OLLAMA_UNAVAILABLE", health.capabilities.getValue("receiptVision").diagnosticCode)
        assertEquals("disabled", health.capabilities.getValue("receiptOcr").status)
        assertEquals("TESSERACT_MISSING", health.capabilities.getValue("receiptOcr").diagnosticCode)
    }

    @Test fun acceptsOnlyKnownStatusesAndSanitizedDiagnosticCodes() {
        val diagnostics = listOf(
            "OLLAMA_UNAVAILABLE",
            "MODEL_MISSING",
            "VISION_DISABLED",
            "VISION_CONFIG_INVALID",
            "TESSERACT_MISSING",
            "HEALTH_CHECK_FAILED",
            "HEALTH_SERVICE_UNAVAILABLE",
            "REMOTE_MODEL_STATUS_UNCHECKED",
            "MODEL_CONFIG_INVALID",
        )

        diagnostics.forEach { diagnostic ->
            FinanceModels.intelligenceHealth(response(
                localAi = capability("unavailable", diagnostic),
                receiptVision = capability("available", null),
                receiptOcr = capability("disabled", null),
            ))
        }
        listOf("available", "unavailable", "disabled").forEach { status ->
            FinanceModels.intelligenceHealth(response(
                localAi = capability(status, null),
                receiptVision = capability("available", null),
                receiptOcr = capability("disabled", null),
            ))
        }
    }

    @Test fun rejectsAvailableCapabilityWithDiagnostic() {
        assertRejected(response(
            localAi = capability("available", "MODEL_MISSING"),
            receiptVision = capability("available", null),
            receiptOcr = capability("available", null),
        ))
    }

    @Test fun rejectsUnknownStatusAndDiagnostic() {
        assertRejected(response(
            localAi = capability("starting", null),
            receiptVision = capability("available", null),
            receiptOcr = capability("available", null),
        ))
        assertRejected(response(
            localAi = capability("unavailable", "MODEL_URL=https://secret.invalid"),
            receiptVision = capability("available", null),
            receiptOcr = capability("available", null),
        ))
    }

    @Test fun rejectsMissingOrAdditionalCapabilities() {
        assertRejected(JSONObject("""
            {"capabilities":{"localAi":${capability("available", null)},
             "receiptVision":${capability("available", null)}}}
        """.trimIndent()))
        assertRejected(response(
            localAi = capability("available", null),
            receiptVision = capability("available", null),
            receiptOcr = capability("available", null),
            extraCapability = capability("available", null),
        ))
    }

    @Test fun rejectsMalformedAndUnexpectedResponseFields() {
        listOf(
            JSONObject("{}"),
            JSONObject("""{"capabilities":[]}"""),
            JSONObject("""{"capabilities":{"localAi":"available","receiptVision":{},"receiptOcr":{}}}"""),
            JSONObject("""{"capabilities":{"localAi":{"status":"available"},"receiptVision":{"status":"available","diagnosticCode":null},"receiptOcr":{"status":"available","diagnosticCode":null}}}"""),
            JSONObject("""{"capabilities":{"localAi":{"status":"available","diagnosticCode":null,"providerUrl":"https://secret.invalid"},"receiptVision":{"status":"available","diagnosticCode":null},"receiptOcr":{"status":"available","diagnosticCode":null}}}"""),
            response(
                localAi = capability("available", null),
                receiptVision = capability("available", null),
                receiptOcr = capability("available", null),
            ).put("providerUrl", "https://secret.invalid"),
        ).forEach(::assertRejected)
    }

    private fun assertRejected(json: JSONObject) {
        assertThrows(IllegalArgumentException::class.java) {
            FinanceModels.intelligenceHealth(json)
        }
    }

    private fun response(
        localAi: String,
        receiptVision: String,
        receiptOcr: String,
        extraCapability: String? = null,
    ): JSONObject {
        val extra = extraCapability?.let { ",\"extra\":$it" }.orEmpty()
        return JSONObject("""
            {"capabilities":{"localAi":$localAi,"receiptVision":$receiptVision,"receiptOcr":$receiptOcr$extra}}
        """.trimIndent())
    }

    private fun capability(status: String, diagnosticCode: String?): String {
        val diagnostic = diagnosticCode?.let { JSONObject.quote(it) } ?: "null"
        return "{\"status\":${JSONObject.quote(status)},\"diagnosticCode\":$diagnostic}"
    }
}
