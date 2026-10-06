package com.example.ldemo;

import com.launchdarkly.sdk.ArrayBuilder;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.server.LDClient;
import com.launchdarkly.sdk.server.ai.AICompletionConfig;
import com.launchdarkly.sdk.server.ai.AICompletionConfigDefault;
import com.launchdarkly.sdk.server.ai.AIJudgeConfig;
import com.launchdarkly.sdk.server.ai.AIJudgeConfigDefault;
import com.launchdarkly.sdk.server.ai.Judge;
import com.launchdarkly.sdk.server.ai.LDAIClient;
import com.launchdarkly.sdk.server.ai.LDAIConfigTracker;
import com.launchdarkly.sdk.server.ai.RunnerResult;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.AIMetrics;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.JudgeResult;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The AI support assistant, shared by the console demo (AiConfigDemo) and the web app (CheckoutService).
 *
 * Two LaunchDarkly controls wrap every question:
 *   1. The KILL SWITCH flag (ai-assistant-enabled). Create this boolean flag; Off means no AI call happens at all.
 *   2. The AI CONFIG (support-assistant). Create this completion-mode AI Config. LaunchDarkly picks the model, prompt, and parameters per shopper.
 *   3. The ACCURACY JUDGE (babysitting-service-reply-accuracy). Create this judge-mode config. After a reply, LaunchDarkly serves the judge config and
 *      this class scores the Q&A. The Java AI SDK does not run UI-attached judges by itself.
 *
 * NOTE: the Java AI SDK is pre-1.0. The model name and messages are read by reflection (see the helpers
 * at the bottom), so a renamed getter won't break the build.
 */
final class AiAssistant {

    /** Create this completion AI Config in LaunchDarkly. */
    static final String CONFIG_KEY = "support-assistant";
    /** Create this judge AI Config in LaunchDarkly (optional accuracy card). */
    static final String JUDGE_KEY = "babysitting-service-reply-accuracy";
    /** Create this boolean kill-switch flag in LaunchDarkly. */
    static final String KILL_SWITCH_FLAG = "ai-assistant-enabled";
    /** Custom conversion: successful Ask reply (secondary experiment metric). */
    static final String EVENT_ASSISTANT_REPLY = "assistant-reply";
    /** Custom conversion: reply judged helpful (primary experiment metric). */
    static final String EVENT_ASSISTANT_HELPFUL = "assistant-helpful";
    /** Custom numeric: Ask latency in ms (secondary experiment metric). */
    static final String EVENT_ASSISTANT_LATENCY_MS = "assistant-latency-ms";
    /** Judge / demo threshold for "helpful" when a live score is present. */
    static final double HELPFUL_SCORE_THRESHOLD = 0.6;
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private AiAssistant() { }

    /** What happened when a shopper asked a question. */
    static final class Answer {
        final boolean available;   // false when the kill switch or the AI Config is off
        final String reason;       // why it's unavailable
        final String model;
        final String variation;
        final String text;
        final String error;        // set when the model call failed
        final int inputTokens;
        final int outputTokens;
        final long latencyMs;
        final boolean live;        // true when a real model answered
        final Double judgeScore;   // 0.0-1.0 when the accuracy judge ran
        final String judgeReasoning;
        final String judgeKey;
        final boolean judgeLive;
        final String judgeError;

        private Answer(boolean available, String reason, String model, String variation, String text, String error,
                       int inputTokens, int outputTokens, long latencyMs, boolean live,
                       Double judgeScore, String judgeReasoning, String judgeKey, boolean judgeLive, String judgeError) {
            this.available = available; this.reason = reason; this.model = model; this.variation = variation;
            this.text = text; this.error = error; this.inputTokens = inputTokens; this.outputTokens = outputTokens;
            this.latencyMs = latencyMs; this.live = live;
            this.judgeScore = judgeScore; this.judgeReasoning = judgeReasoning;
            this.judgeKey = judgeKey; this.judgeLive = judgeLive; this.judgeError = judgeError;
        }
        static Answer unavailable(String reason) {
            return new Answer(false, reason, null, null, null, null, 0, 0, 0, false, null, null, null, false, null);
        }
        static Answer failed(String model, String error) {
            return new Answer(true, null, model, null, null, error, 0, 0, 0, false, null, null, null, false, null);
        }
        static Answer ok(String model, String variation, String text, int in, int out, long ms, boolean live) {
            return new Answer(true, null, model, variation, text, null, in, out, ms, live, null, null, null, false, null);
        }
        Answer withJudge(Double score, String reasoning, String key, boolean liveJudge, String error) {
            return new Answer(available, reason, model, variation, text, this.error, inputTokens, outputTokens, latencyMs, live,
                    score, reasoning, key, liveJudge, error);
        }
    }

    static Answer ask(LDClient ldClient, LDAIClient aiClient, LDContext ctx, String question, String anthropicKey) {
        // 1. Kill switch. The default is false, so if LaunchDarkly is unreachable the assistant fails closed.
        if (!ldClient.boolVariation(KILL_SWITCH_FLAG, ctx, false)) {
            return Answer.unavailable("The kill switch (" + KILL_SWITCH_FLAG + ") is off");
        }

        // 2. Ask LaunchDarkly which model and prompt this shopper gets. {{product}} fills the prompt;
        //    {{ldctx.name}} comes from the context itself.
        Map<String, Object> variables = new HashMap<>();
        variables.put("product", "Amelia's Babysitting Service");
        AICompletionConfig config = aiClient.completionConfig(
                CONFIG_KEY, ctx, AICompletionConfigDefault.disabled(), variables);
        if (!config.isEnabled()) {
            return Answer.unavailable("The AI Config is disabled for this shopper");
        }

        String model = modelName(config);
        String variation = variationKey(config);
        boolean live = anthropicKey != null && !anthropicKey.isBlank();

        // 3. Call the model, timing it and recording tokens against this config variation.
        //    Custom track() events below feed the support-assistant experiment metrics in LaunchDarkly.
        LDAIConfigTracker tracker = config.createTracker();
        try {
            long start = System.nanoTime();
            ModelResult r = tracker.trackDurationOf(() -> callModel(config, model, question, anthropicKey));
            long ms = (System.nanoTime() - start) / 1_000_000;
            tracker.trackTokens(new LDAITrackingTypes.TokenUsage(
                    r.inputTokens + r.outputTokens, r.inputTokens, r.outputTokens));
            tracker.trackSuccess();
            Answer answer = score(aiClient, ctx, question,
                    Answer.ok(model, variation, r.text, r.inputTokens, r.outputTokens, ms, live), anthropicKey);
            trackExperimentMetrics(ldClient, ctx, tracker, answer);
            return answer;
        } catch (Exception e) {
            tracker.trackError();
            String why = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return Answer.failed(model, why);
        }
    }

    /**
     * Emit custom metrics for the support-assistant experiment, plus AI SDK feedback for autogen metrics.
     * Event keys: assistant-reply, assistant-helpful, assistant-latency-ms.
     */
    static void trackExperimentMetrics(LDClient ldClient, LDContext ctx, LDAIConfigTracker tracker, Answer answer) {
        if (ldClient == null || ctx == null || answer == null || !answer.available || answer.error != null) {
            return;
        }
        ldClient.track(EVENT_ASSISTANT_REPLY, ctx);
        ldClient.trackMetric(EVENT_ASSISTANT_LATENCY_MS, ctx, LDValue.ofNull(), answer.latencyMs);

        boolean helpful = isHelpful(answer);
        if (helpful) {
            ldClient.track(EVENT_ASSISTANT_HELPFUL, ctx);
        }
        if (tracker != null) {
            tracker.trackFeedback(helpful
                    ? LDAITrackingTypes.FeedbackKind.POSITIVE
                    : LDAITrackingTypes.FeedbackKind.NEGATIVE);
        }
    }

    /**
     * Live answers use the accuracy judge score. Simulated answers (no Anthropic key) differentiate
     * experiment arms by variation key so Haiku/concise vs Sonnet/detailed still move the primary metric.
     */
    static boolean isHelpful(Answer answer) {
        if (answer == null) return false;
        if (answer.live && answer.judgeScore != null) {
            return answer.judgeScore >= HELPFUL_SCORE_THRESHOLD;
        }
        String variation = answer.variation == null ? "" : answer.variation.toLowerCase();
        // concise (Haiku) is the control; detailed publishes under variation key "support-assistant".
        if (variation.contains("concise") || variation.contains("haiku")) return false;
        if (variation.contains("grounded") || variation.contains("detailed")
                || "support-assistant".equals(variation)) {
            return true;
        }
        return answer.judgeScore != null && answer.judgeScore >= HELPFUL_SCORE_THRESHOLD;
    }

    // ------------------------------------------------------------------------------------------

    /**
     * Score the assistant reply with the babysitting-service-reply-accuracy judge. The Java AI SDK does not run
     * UI-attached judges, so this invokes the judge config directly after each successful answer.
     */
    private static Answer score(LDAIClient aiClient, LDContext ctx, String question, Answer answer, String apiKey) {
        try {
            AIJudgeConfig judgeCfg = aiClient.judgeConfig(
                    JUDGE_KEY, ctx, AIJudgeConfigDefault.disabled(), Map.of());
            if (!judgeCfg.isEnabled()) {
                Map<String, Object> parsed = simulatedJudgeParsed(answer.text);
                return answer.withJudge(
                        ((Number) parsed.get("score")).doubleValue(),
                        parsed.get("reasoning") + " LaunchDarkly did not serve " + JUDGE_KEY + " (create it and turn targeting on).",
                        JUDGE_KEY,
                        false,
                        null);
            }
            boolean liveJudge = apiKey != null && !apiKey.isBlank();
            JudgeResult result = new Judge(judgeCfg, (input, schema) -> liveJudge
                    ? liveJudgeRun(judgeCfg, input, apiKey)
                    : simulatedJudgeRun(answer.text), null)
                    .evaluate(question, answer.text);
            if (result.isSuccess() && result.getScore() != null) {
                return answer.withJudge(result.getScore(), result.getReasoning(), JUDGE_KEY, liveJudge, null);
            }
            String why = result.getErrorMessage() != null
                    ? result.getErrorMessage()
                    : "Accuracy judge did not return a score.";
            return answer.withJudge(null, null, JUDGE_KEY, liveJudge, why);
        } catch (RuntimeException e) {
            String why = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return answer.withJudge(null, null, JUDGE_KEY, false, why);
        }
    }

    static Map<String, Object> simulatedJudgeParsed(String output) {
        Map<String, Object> parsed = new HashMap<>();
        String text = output == null ? "" : output.toLowerCase();
        if (text.contains("[simulated reply]") || text.contains("add an anthropic_api_key")) {
            parsed.put("score", 0.2);
            parsed.put("reasoning", "This is a stub reply, not an accurate answer about the babysitting service.");
        } else {
            parsed.put("score", 0.7);
            parsed.put("reasoning", "Simulated accuracy score. Add ANTHROPIC_API_KEY so the judge can score a live answer.");
        }
        return parsed;
    }

    static Map<String, Object> parseJudgeJson(String text) {
        if (text == null) return null;
        String raw = text.trim();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            LDValue json = LDValue.parse(raw.substring(start, end + 1));
            if (!json.get("score").isNumber()) return null;
            Map<String, Object> parsed = new HashMap<>();
            parsed.put("score", json.get("score").doubleValue());
            if (json.get("reasoning").isString()) {
                parsed.put("reasoning", json.get("reasoning").stringValue());
            }
            return parsed;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static RunnerResult simulatedJudgeRun(String output) {
        Map<String, Object> parsed = simulatedJudgeParsed(output);
        return RunnerResult.builder(
                "{\"score\":" + parsed.get("score") + "}",
                AIMetrics.builder().success(true).durationMs(5L).build())
                .parsed(parsed)
                .build();
    }

    private static RunnerResult liveJudgeRun(AIJudgeConfig judgeCfg, String input, String apiKey) {
        String model = modelName(judgeCfg);
        StringBuilder system = new StringBuilder();
        for (String[] m : messages(judgeCfg)) {
            system.append(m[1]).append('\n');
        }
        if (system.length() == 0) {
            system.append("Score whether the babysitting-support reply is factually accurate for the parent's question.\n");
        }
        system.append("Reply with JSON only: {\"score\": <number 0.0-1.0>, \"reasoning\": \"<short reason>\"}.");

        ModelResult raw = callAnthropic(model, system.toString().trim(), input, apiKey);
        Map<String, Object> parsed = parseJudgeJson(raw.text);
        if (parsed == null) {
            throw new RuntimeException("Accuracy judge did not return JSON with a score.");
        }
        return RunnerResult.builder(raw.text, AIMetrics.builder()
                        .success(true)
                        .tokens(new LDAITrackingTypes.TokenUsage(
                                raw.inputTokens + raw.outputTokens, raw.inputTokens, raw.outputTokens))
                        .build())
                .parsed(parsed)
                .build();
    }

    private static ModelResult callModel(AICompletionConfig config, String model, String question, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String shown = question.length() > 120 ? question.substring(0, 120) + "..." : question;
            return new ModelResult("[simulated reply] You asked: \"" + shown
                    + "\". Add an ANTHROPIC_API_KEY to get a real answer.", 120, 85);
        }
        try {
            // Anthropic takes the system prompt as a top-level field; other messages go in "messages".
            StringBuilder system = new StringBuilder();
            ArrayBuilder messages = LDValue.buildArray();
            for (String[] m : messages(config)) {
                if ("system".equals(m[0])) {
                    system.append(m[1]).append("\n");
                } else {
                    messages.add(LDValue.buildObject().put("role", m[0]).put("content", m[1]).build());
                }
            }
            messages.add(LDValue.buildObject().put("role", "user").put("content", question).build());

            return callAnthropic(model, system.toString().trim(), messages.build(), apiKey);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ModelResult callAnthropic(String model, String system, String user, String apiKey) {
        ArrayBuilder messages = LDValue.buildArray()
                .add(LDValue.buildObject().put("role", "user").put("content", user).build());
        return callAnthropic(model, system, messages.build(), apiKey);
    }

    private static ModelResult callAnthropic(String model, String system, LDValue messages, String apiKey) {
        ObjectBuilder body = LDValue.buildObject()
                .put("model", model)
                .put("max_tokens", 400)
                .put("messages", messages);
        if (system != null && !system.isBlank()) body.put("system", system);

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ANTHROPIC_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.build().toJsonString()))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Anthropic returned " + response.statusCode()
                        + ". If it's a 404, the model ID in your AI Config isn't a valid Anthropic model ID.");
            }

            LDValue json = LDValue.parse(response.body());
            StringBuilder text = new StringBuilder();
            for (LDValue block : json.get("content").values()) {
                if ("text".equals(block.get("type").stringValue())) text.append(block.get("text").stringValue());
            }
            LDValue usage = json.get("usage");
            return new ModelResult(text.toString(),
                    usage.get("input_tokens").intValue(), usage.get("output_tokens").intValue());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The Java AI SDK is pre-1.0, so these two helpers read the model name and messages by reflection.
     * If a getter gets renamed, the project still compiles and the assistant falls back gracefully
     * instead of breaking the whole build.
     */
    private static String variationKey(Object config) {
        Object key = read(config, "getVariationKey", "getKey");
        if (key != null && !String.valueOf(key).isBlank()) return String.valueOf(key);
        Object meta = read(config, "getMeta");
        Object fromMeta = read(meta, "getVariationKey", "variationKey");
        return fromMeta == null ? null : String.valueOf(fromMeta);
    }

    private static String modelName(Object config) {
        Object model = read(config, "getModel");
        Object name = read(model, "getName", "getId", "name");
        if (name != null) return String.valueOf(name);
        return model != null ? String.valueOf(model) : "unknown";
    }

    /** Messages from the AI Config as [role, content] pairs. */
    private static List<String[]> messages(Object config) {
        List<String[]> out = new ArrayList<>();
        Object list = read(config, "getMessages");
        if (!(list instanceof Iterable)) return out;
        for (Object m : (Iterable<?>) list) {
            Object role = read(m, "getRole", "role");
            Object content = read(m, "getContent", "content");
            if (content != null) {
                out.add(new String[]{role == null ? "user" : String.valueOf(role).toLowerCase(), String.valueOf(content)});
            }
        }
        return out;
    }

    /** Calls the first no-argument getter that exists, or returns null. */
    private static Object read(Object target, String... getterNames) {
        if (target == null) return null;
        for (String name : getterNames) {
            try {
                Method method = target.getClass().getMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // try the next name
            }
        }
        return null;
    }

    private static final class ModelResult {
        final String text;
        final int inputTokens;
        final int outputTokens;
        ModelResult(String text, int inputTokens, int outputTokens) {
            this.text = text;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }
    }
}
