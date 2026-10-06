package com.example.ldemo;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.server.LDClient;
import com.launchdarkly.sdk.server.ai.LDAIClient;
import com.launchdarkly.sdk.server.ai.LDAIClientImpl;

/**
 * Short traffic burst for the support-assistant AI Config experiment.
 *
 * Uses free-tier users (no enterprise plan) so they hit the fallthrough experiment
 * (concise Haiku vs detailed Sonnet). Enterprise parents stay on the grounded rule.
 *
 * Run:
 *   ./run.sh ai-experiment          # 120 users, 1 ask each (simulated unless ANTHROPIC_API_KEY is set)
 *   ./run.sh ai-experiment 60 2     # 60 users, 2 asks each
 *
 * Watch results: Experiments → Support Assistant Prompt/Model A/B in the test environment,
 * or the AI Config Monitoring tab for tokens / duration / feedback.
 */
public class AiExperimentTraffic {

    private static final String[] QUESTIONS = {
            "How much does Saturday evening babysitting cost?",
            "Can I change my booking time?",
            "Do you offer overnight sitters?",
            "What is your cancellation policy?",
            "Can Maya sit for twins next weekend?"
    };

    public static void main(String[] args) throws Exception {
        String sdkKey = System.getenv("LD_SDK_KEY");
        if (sdkKey == null || sdkKey.isBlank()) {
            System.err.println("Missing LD_SDK_KEY. Export your server-side SDK key first.");
            System.exit(1);
        }
        int users = parseIntArg(args, "--users", 0, 120);
        int asks = parseIntArg(args, "--asks", 1, 1);
        // Positional: ./run.sh ai-experiment [users] [asks]
        if (args.length >= 1 && !args[0].startsWith("--")) users = Integer.parseInt(args[0]);
        if (args.length >= 2 && !args[1].startsWith("--")) asks = Integer.parseInt(args[1]);
        users = Math.max(1, Math.min(users, 2000));
        asks = Math.max(1, Math.min(asks, 5));

        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        boolean live = anthropicKey != null && !anthropicKey.isBlank();
        System.out.printf("AI experiment traffic: %d free-tier users × %d ask(s), mode=%s%n",
                users, asks, live ? "LIVE" : "SIMULATED");
        System.out.println("Audience: plan=free → support-assistant fallthrough experiment.");
        System.out.println("Metrics: assistant-reply, assistant-helpful, assistant-latency-ms (+ AI SDK tokens/feedback).");

        int helpful = 0, replies = 0, unavailable = 0, errors = 0;
        try (LDClient ldClient = new LDClient(sdkKey)) {
            if (!ldClient.isInitialized()) {
                System.err.println("Could not initialize the LaunchDarkly client.");
                System.exit(1);
            }
            if (!ldClient.boolVariation(AiAssistant.KILL_SWITCH_FLAG,
                    LDContext.builder("user-ai-exp-probe").set("plan", "free").build(), false)) {
                System.err.println("ai-assistant-enabled is Off. Turn it On in test, then re-run.");
                System.exit(1);
            }
            LDAIClient aiClient = new LDAIClientImpl(ldClient);
            for (int i = 0; i < users; i++) {
                LDContext ctx = LDContext.builder("user-ai-exp-" + i)
                        .name("AI Exp Parent " + i)
                        .set("plan", "free")
                        .build();
                for (int a = 0; a < asks; a++) {
                    String q = QUESTIONS[(i + a) % QUESTIONS.length];
                    AiAssistant.Answer ans = AiAssistant.ask(ldClient, aiClient, ctx, q, anthropicKey);
                    if (!ans.available) {
                        unavailable++;
                        continue;
                    }
                    if (ans.error != null) {
                        errors++;
                        continue;
                    }
                    replies++;
                    if (AiAssistant.isHelpful(ans)) helpful++;
                    if (i < 3 && a == 0) {
                        System.out.printf("  sample user-ai-exp-%d → variation=%s model=%s helpful=%s%n",
                                i, ans.variation, ans.model, AiAssistant.isHelpful(ans));
                    }
                }
            }
            // Give the SDK a moment to flush custom events before exit.
            Thread.sleep(1500);
        }
        System.out.printf("Done. replies=%d helpful=%d unavailable=%d errors=%d%n",
                replies, helpful, unavailable, errors);
        System.out.println("Open LD: Experiments → Support Assistant Prompt/Model A/B (test), or");
        System.out.println("https://app.launchdarkly.com/projects/default/experiments/support-assistant-prompt-model?env=test");
    }

    private static int parseIntArg(String[] args, String name, int indexHint, int defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return defaultValue;
    }
}
