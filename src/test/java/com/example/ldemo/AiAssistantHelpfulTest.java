package com.example.ldemo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAssistantHelpfulTest {

    @Test
    void simulatedConciseIsNotHelpful() {
        AiAssistant.Answer a = AiAssistant.Answer.ok("claude-haiku", "concise", "[simulated reply]", 10, 10, 50, false);
        assertFalse(AiAssistant.isHelpful(a));
    }

    @Test
    void simulatedDetailedVariationKeyIsHelpful() {
        // detailed variation publishes under key "support-assistant"
        AiAssistant.Answer a = AiAssistant.Answer.ok("claude-sonnet", "support-assistant", "[simulated reply]", 10, 20, 80, false);
        assertTrue(AiAssistant.isHelpful(a));
    }

    @Test
    void liveUsesJudgeThreshold() {
        AiAssistant.Answer low = AiAssistant.Answer.ok("claude-sonnet", "concise", "ok", 1, 1, 10, true)
                .withJudge(0.4, "weak", "babysitting-service-reply-accuracy", true, null);
        AiAssistant.Answer high = AiAssistant.Answer.ok("claude-sonnet", "concise", "ok", 1, 1, 10, true)
                .withJudge(0.75, "solid", "babysitting-service-reply-accuracy", true, null);
        assertFalse(AiAssistant.isHelpful(low));
        assertTrue(AiAssistant.isHelpful(high));
    }
}
