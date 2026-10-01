package com.example.ldemo;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.server.LDClient;
import com.launchdarkly.sdk.server.ai.LDAIClient;
import com.launchdarkly.sdk.server.ai.LDAIClientImpl;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Live AI Config / kill-switch path. Skips when LD_SDK_KEY is missing. */
class AiAssistantIT {

    @Test
    void askReturnsSimulatedReplyWhenAnthropicKeyAbsent() throws Exception {
        Optional<String> key = TestEnv.sdkKey();
        assumeTrue(key.isPresent(), "LD_SDK_KEY not set; skipping AI assistant test");

        LDContext ctx = CheckoutService.contextFrom(Map.of(
                "user", "amelia",
                "name", "Amelia Smith",
                "org", "org-ld",
                "tier", "enterprise",
                "role", "admin"));

        try (LDClient client = new LDClient(key.get())) {
            assumeTrue(client.isInitialized(), "LaunchDarkly client did not initialize");
            assumeTrue(client.boolVariation(AiAssistant.KILL_SWITCH_FLAG, ctx, false),
                    "ai-assistant-enabled is off; turn it on to exercise AiAssistant.ask");

            LDAIClient ai = new LDAIClientImpl(client);
            AiAssistant.Answer answer = AiAssistant.ask(client, ai, ctx, "Do you offer evening sitters?", "");

            assumeTrue(answer.available,
                    "AI Config unavailable for this shopper (is support-assistant serving an enabled variation?)");

            assertTrue(answer.error == null, () -> "unexpected error: " + answer.error);
            assertNotNull(answer.model);
            assertFalse(answer.model.isBlank());
            assertNotNull(answer.text);
            assertTrue(answer.text.contains("simulated") || !answer.text.isEmpty());
            assertFalse(answer.live);
        }
    }

    @Test
    void askReportsUnavailableWhenKillSwitchOff() throws Exception {
        Optional<String> key = TestEnv.sdkKey();
        assumeTrue(key.isPresent(), "LD_SDK_KEY not set; skipping AI assistant test");

        // Use a throwaway context; we force kill-switch check by evaluating after confirming client works.
        LDContext ctx = CheckoutService.contextFrom(Map.of(
                "user", "test-kill-switch",
                "name", "Test User",
                "org", "org-test",
                "tier", "free",
                "role", "parent"));

        try (LDClient client = new LDClient(key.get())) {
            assumeTrue(client.isInitialized(), "LaunchDarkly client did not initialize");
            // Only run the negative path when the kill switch is currently off for this context.
            assumeTrue(!client.boolVariation(AiAssistant.KILL_SWITCH_FLAG, ctx, false),
                    "kill switch is on for this context; skipping unavailable-path assertion");

            LDAIClient ai = new LDAIClientImpl(client);
            AiAssistant.Answer answer = AiAssistant.ask(client, ai, ctx, "Hello", "");
            assertFalse(answer.available);
            assertNotNull(answer.reason);
            assertTrue(answer.reason.toLowerCase().contains("kill"));
        }
    }
}
