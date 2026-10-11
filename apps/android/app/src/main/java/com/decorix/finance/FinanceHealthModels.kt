package com.decorix.finance

import org.json.JSONObject

data class FinanceHealthCapability(val status: String, val diagnosticCode: String?)

data class FinanceHealthStatus(val capabilities: Map<String, FinanceHealthCapability>)

internal object FinanceHealthModels {
    private val capabilityKeys = setOf("localAi", "receiptVision", "receiptOcr")
    private val statuses = setOf("available", "unavailable", "disabled")
    private val diagnosticCodes = setOf(
        "OLLAMA_UNAVAILABLE", "MODEL_MISSING", "VISION_DISABLED", "VISION_CONFIG_INVALID",
        "TESSERACT_MISSING", "HEALTH_CHECK_FAILED", "HEALTH_SERVICE_UNAVAILABLE",
        "REMOTE_MODEL_STATUS_UNCHECKED", "MODEL_CONFIG_INVALID",
    )

    fun parse(response: JSONObject): FinanceHealthStatus {
        val rootKeys = response.keys().asSequence().toSet()
        require(rootKeys == setOf("capabilities")) { "Invalid health response" }
        val capabilitiesJson = response.optJSONObject("capabilities")
            ?: throw IllegalArgumentException("Invalid health response")
        require(capabilitiesJson.keys().asSequence().toSet() == capabilityKeys) { "Invalid health response" }

        val capabilities = capabilityKeys.associateWith { key ->
            val value = capabilitiesJson.optJSONObject(key)
                ?: throw IllegalArgumentException("Invalid health response")
            require(value.keys().asSequence().toSet() == setOf("status", "diagnosticCode")) {
                "Invalid health response"
            }
            val status = value.opt("status") as? String
                ?: throw IllegalArgumentException("Invalid health response")
            require(status in statuses) { "Invalid health response" }
            val diagnosticValue = value.opt("diagnosticCode")
            val diagnostic = if (diagnosticValue == null || diagnosticValue == JSONObject.NULL) null
            else diagnosticValue as? String ?: throw IllegalArgumentException("Invalid health response")
            require(diagnostic == null || diagnostic in diagnosticCodes) { "Invalid health response" }
            require(status != "available" || diagnostic == null) { "Invalid health response" }
            FinanceHealthCapability(status, diagnostic)
        }
        return FinanceHealthStatus(capabilities)
    }
}
