package com.decorix.finance.core.api;

import java.util.Map;

public final class HealthStatusApi {
    private HealthStatusApi() {}

    public record Capability(String status, String diagnosticCode) {}
    public record Response(Map<String, Capability> capabilities) {}
}
