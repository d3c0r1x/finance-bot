package com.decorix.finance.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiGatewayRequestTest {
    @Test
    void buildsVersionedRequestEnvelopeWithBoundedDeadlineAndCorrelationId() {
        long before = System.currentTimeMillis();

        var request = AiGatewayRequest.create("transaction-draft", Duration.ofSeconds(3), "local-only",
                Set.of("text", "structured_output"));
        var headers = request.headers();

        assertThat(headers)
                .containsEntry("X-Finance-Task-Kind", "transaction-draft")
                .containsEntry("X-Finance-Input-Schema-Version", "transaction-draft-context.v1")
                .containsEntry("X-Finance-Output-Schema-Version", "transaction-draft-advice.v1")
                .containsEntry("X-Finance-AI-Policy", "local-only")
                .containsEntry("X-Finance-Required-Capabilities", "structured_output,text");
        assertThat(UUID.fromString(headers.get("X-Finance-Correlation-ID").toString())).isNotNull();
        long deadline = Long.parseLong(headers.get("X-Finance-Deadline-Unix-Ms").toString());
        assertThat(deadline).isBetween(before + 2_000, before + 10_000);
        assertThat(request.correlationId()).isEqualTo(headers.get("X-Finance-Correlation-ID"));
    }

    @Test
    void rejectsUnsupportedTaskPolicyAndEmptyDeadline() {
        assertThatThrownBy(() -> AiGatewayRequest.create("unsupported", Duration.ofSeconds(1), "local-only",
                Set.of("text"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiGatewayRequest.create("budget-proposal", Duration.ZERO, "local-only",
                Set.of("text", "structured_output"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiGatewayRequest.create("budget-proposal", Duration.ofSeconds(1), "cloud",
                Set.of("text", "structured_output"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsTypedVisionRequestEnvelope() {
        var request = AiGatewayRequest.create("receipt-vision", Duration.ofSeconds(5), "local-only",
                Set.of("vision", "structured_output"));

        assertThat(request.headers())
                .containsEntry("X-Finance-Input-Schema-Version", "receipt-vision-context.v1")
                .containsEntry("X-Finance-Output-Schema-Version", "receipt-vision-result.v1")
                .containsEntry("X-Finance-Required-Capabilities", "structured_output,vision");
    }
}
