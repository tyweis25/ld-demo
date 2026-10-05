package com.example.ldemo;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit tests for the accuracy judge and Answer shape. No LaunchDarkly network. */
class AiAssistantJudgeTest {

    @Test
    void configKeysMatchLaunchDarkly() {
        assertEquals("support-assistant", AiAssistant.CONFIG_KEY);
        assertEquals("babysitting-service-reply-accuracy", AiAssistant.JUDGE_KEY);
        assertEquals("ai-assistant-enabled", AiAssistant.KILL_SWITCH_FLAG);
    }

    @Test
    void parseJudgeJsonReadsScoreAndReasoning() {
        Map<String, Object> parsed = AiAssistant.parseJudgeJson(
                "{\"score\":0.8,\"reasoning\":\"Mentions evening sitters.\"}");
        assertNotNull(parsed);
        assertEquals(0.8, ((Number) parsed.get("score")).doubleValue(), 0.0001);
        assertEquals("Mentions evening sitters.", parsed.get("reasoning"));
    }

    @Test
    void parseJudgeJsonHandlesFencesPrefixAndIntegerScore() {
        Map<String, Object> fenced = AiAssistant.parseJudgeJson(
                "```json\n{\"score\":1,\"reasoning\":\"Correct\"}\n```");
        assertNotNull(fenced);
        assertEquals(1.0, ((Number) fenced.get("score")).doubleValue(), 0.0001);

        Map<String, Object> prefixed = AiAssistant.parseJudgeJson(
                "Here is the verdict:\n{\"score\":0,\"reasoning\":\"Invented $18/hr.\"}\nThanks.");
        assertNotNull(prefixed);
        assertEquals(0.0, ((Number) prefixed.get("score")).doubleValue(), 0.0001);
        assertEquals("Invented $18/hr.", prefixed.get("reasoning"));
    }

    @Test
    void parseJudgeJsonRejectsMissingOrNonNumericScore() {
        assertNull(AiAssistant.parseJudgeJson(null));
        assertNull(AiAssistant.parseJudgeJson(""));
        assertNull(AiAssistant.parseJudgeJson("no json here"));
        assertNull(AiAssistant.parseJudgeJson("{\"reasoning\":\"oops\"}"));
        assertNull(AiAssistant.parseJudgeJson("{\"score\":\"high\"}"));
        assertNull(AiAssistant.parseJudgeJson("{"));
    }

    @Test
    void parseJudgeJsonAllowsScoreWithoutReasoning() {
        Map<String, Object> parsed = AiAssistant.parseJudgeJson("{\"score\":0.6}");
        assertNotNull(parsed);
        assertEquals(0.6, ((Number) parsed.get("score")).doubleValue(), 0.0001);
        assertFalse(parsed.containsKey("reasoning"));
    }

    @Test
    void simulatedJudgeFlagsStubReply() {
        Map<String, Object> stub = AiAssistant.simulatedJudgeParsed(
                "[simulated reply] You asked: \"Do you offer evening sitters?\"");
        assertTrue(((Number) stub.get("score")).doubleValue() < 0.5);
        assertTrue(String.valueOf(stub.get("reasoning")).toLowerCase().contains("stub"));

        Map<String, Object> missingKey = AiAssistant.simulatedJudgeParsed(
                "Add an ANTHROPIC_API_KEY to get a real answer.");
        assertTrue(((Number) missingKey.get("score")).doubleValue() < 0.5);
    }

    @Test
    void simulatedJudgeScoresALiveLookingReply() {
        Map<String, Object> live = AiAssistant.simulatedJudgeParsed(
                "Evening sitters start around 18:00. Maya is $28/hr.");
        assertEquals(0.7, ((Number) live.get("score")).doubleValue(), 0.0001);
        assertTrue(String.valueOf(live.get("reasoning")).toLowerCase().contains("simulated"));
    }

    @Test
    void answerFactoriesAndWithJudgePreserveTheReply() {
        AiAssistant.Answer off = AiAssistant.Answer.unavailable("The kill switch (ai-assistant-enabled) is off");
        assertFalse(off.available);
        assertTrue(off.reason.contains("kill switch"));
        assertNull(off.judgeScore);

        AiAssistant.Answer failed = AiAssistant.Answer.failed("claude-haiku", "Anthropic returned 404");
        assertTrue(failed.available);
        assertEquals("Anthropic returned 404", failed.error);
        assertFalse(failed.live);

        AiAssistant.Answer ok = AiAssistant.Answer.ok(
                "claude-sonnet", "grounded", "Maya is $28/hr.", 10, 20, 30, true);
        AiAssistant.Answer judged = ok.withJudge(1.0, "Matches official facts", AiAssistant.JUDGE_KEY, true, null);
        assertEquals("Maya is $28/hr.", judged.text);
        assertEquals("grounded", judged.variation);
        assertEquals(1.0, judged.judgeScore, 0.0001);
        assertEquals("Matches official facts", judged.judgeReasoning);
        assertEquals(AiAssistant.JUDGE_KEY, judged.judgeKey);
        assertTrue(judged.judgeLive);
        assertTrue(judged.live);
        assertEquals(10, judged.inputTokens);
        assertEquals(20, judged.outputTokens);
        assertEquals(30, judged.latencyMs);

        AiAssistant.Answer judgeFailed = ok.withJudge(null, null, AiAssistant.JUDGE_KEY, true, "Accuracy judge did not return a score.");
        assertNull(judgeFailed.judgeScore);
        assertEquals("Accuracy judge did not return a score.", judgeFailed.judgeError);
        assertEquals("Maya is $28/hr.", judgeFailed.text);
    }
}
