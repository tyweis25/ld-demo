package com.example.ldemo;

import com.launchdarkly.sdk.ArrayBuilder;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.server.LDClient;
import com.launchdarkly.sdk.server.ai.AICompletionConfig;
import com.launchdarkly.sdk.server.ai.AICompletionConfigDefault;
import com.launchdarkly.sdk.server.ai.LDAIClient;
import com.launchdarkly.sdk.server.ai.LDAIConfigTracker;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes;

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
 *   1. The KILL SWITCH flag (ai-assistant-enabled). Off means no AI call happens at all.
 *   2. The AI CONFIG (support-assistant). LaunchDarkly picks the model, prompt, and parameters per shopper.
 *
 * NOTE: the Java AI SDK is pre-1.0. The model name and messages are read by reflection (see the helpers
 * at the bottom), so a renamed getter won't break the build.
 */
final class AiAssistant {

    static final String CONFIG_KEY = "support-assistant";
    static final String KILL_SWITCH_FLAG = "ai-assistant-enabled";
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private AiAssistant() { }

    /** What happened when a shopper asked a question. */
    static final class Answer {
        final boolean available;   // false when the kill switch or the AI Config is off
        final String reason;       // why it's unavailable
        final String model;
        final String text;
        final String error;        // set when the model call failed
        final int inputTokens;
        final int outputTokens;
        final long latencyMs;
        final boolean live;        // true when a real model answered

        private Answer(boolean available, String reason, String model, String text, String error,
                       int inputTokens, int outputTokens, long latencyMs, boolean live) {
            this.available = available; this.reason = reason; this.model = model; this.text = text;
            this.error = error; this.inputTokens = inputTokens; this.outputTokens = outputTokens;
            this.latencyMs = latencyMs; this.live = live;
        }
        static Answer unavailable(String reason) { return new Answer(false, reason, null, null, null, 0, 0, 0, false); }
        static Answer failed(String model, String error) { return new Answer(true, null, model, null, error, 0, 0, 0, false); }
        static Answer ok(String model, String text, int in, int out, long ms, boolean live) {
            return new Answer(true, null, model, text, null, in, out, ms, live);
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
        boolean live = anthropicKey != null && !anthropicKey.isBlank();

        // 3. Call the model, timing it and recording tokens against this config variation.
        LDAIConfigTracker tracker = config.createTracker();
        try {
            long start = System.nanoTime();
            ModelResult r = tracker.trackDurationOf(() -> callModel(config, model, question, anthropicKey));
            long ms = (System.nanoTime() - start) / 1_000_000;
            tracker.trackTokens(new LDAITrackingTypes.TokenUsage(
                    r.inputTokens + r.outputTokens, r.inputTokens, r.outputTokens));
            tracker.trackSuccess();
            return Answer.ok(model, r.text, r.inputTokens, r.outputTokens, ms, live);
        } catch (Exception e) {
            tracker.trackError();
            String why = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return Answer.failed(model, why);
        }
    }

    // ------------------------------------------------------------------------------------------

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

            ObjectBuilder body = LDValue.buildObject()
                    .put("model", model)
                    .put("max_tokens", 400)
                    .put("messages", messages.build());
            if (system.length() > 0) body.put("system", system.toString().trim());

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
    private static String modelName(AICompletionConfig config) {
        Object model = read(config, "getModel");
        Object name = read(model, "getName", "getId", "name");
        if (name != null) return String.valueOf(name);
        return model != null ? String.valueOf(model) : "unknown";
    }

    /** Messages from the AI Config as [role, content] pairs. */
    private static List<String[]> messages(AICompletionConfig config) {
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
