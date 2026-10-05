package com.example.ldemo;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.server.LDClient;
import com.launchdarkly.sdk.server.ai.LDAIClient;
import com.launchdarkly.sdk.server.ai.LDAIClientImpl;

import java.util.Scanner;

/**
 * Extra credit (console version): an AI support assistant whose model and prompt are controlled by a
 * LaunchDarkly AI Config, protected by a feature flag kill switch. The web version lives in CheckoutService.
 *
 * LaunchDarkly setup:
 *   - Boolean flag:              ai-assistant-enabled   (the kill switch)
 *   - Completion-mode AI Config: support-assistant      (model + prompt per customer)
 *
 * Run:
 *   export LD_SDK_KEY=sdk-xxxx
 *   export ANTHROPIC_API_KEY=sk-ant-xxxx      # optional; without it the model call is simulated
 *   ./run.sh ai
 */
public class AiConfigDemo {

    public static void main(String[] args) throws Exception {
        String sdkKey = System.getenv("LD_SDK_KEY");
        if (sdkKey == null || sdkKey.isBlank()) {
            System.err.println("Missing LD_SDK_KEY. Export your server-side SDK key first.");
            System.exit(1);
        }
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        boolean live = anthropicKey != null && !anthropicKey.isBlank();
        System.out.println(live ? "Mode: LIVE model calls (Anthropic)\n"
                                : "Mode: SIMULATED model calls (set ANTHROPIC_API_KEY to go live)\n");

        LDContext amelia = LDContext.builder("user-amelia")
                .name("Amelia Smith").set("plan", "enterprise").build();
        LDContext liam = LDContext.builder("user-liam")
                .name("Liam Carter").set("plan", "free").build();

        try (LDClient ldClient = new LDClient(sdkKey)) {
            if (!ldClient.isInitialized()) {
                System.err.println("Could not initialize the LaunchDarkly client.");
                System.exit(1);
            }
            LDAIClient aiClient = new LDAIClientImpl(ldClient);
            String question = "Can I change my shipping address?";

            ask(ldClient, aiClient, amelia, question, anthropicKey);
            ask(ldClient, aiClient, liam, question, anthropicKey);

            // The kill switch moment: flip the flag off in the dashboard, then ask again.
            System.out.println("\nNow turn OFF '" + AiAssistant.KILL_SWITCH_FLAG + "' in LaunchDarkly, then press Enter.");
            new Scanner(System.in).nextLine();
            ask(ldClient, aiClient, amelia, question, anthropicKey);
        }
        System.out.println("\nDone. Check the AI Config's Monitoring tab for duration and token metrics.");
    }

    private static void ask(LDClient ldClient, LDAIClient aiClient, LDContext ctx, String question, String anthropicKey) {
        System.out.println("\n--- " + ctx.getName() + " asks: " + question);
        AiAssistant.Answer a = AiAssistant.ask(ldClient, aiClient, ctx, question, anthropicKey);

        if (!a.available) {
            System.out.println("AI assistant is OFF (" + a.reason + "). Showing fallback: \"Please contact support.\"");
            return;
        }
        System.out.println("Model served by LaunchDarkly: " + a.model
                + (a.variation != null ? " (" + a.variation + ")" : ""));
        if (a.error != null) {
            System.out.println("Model call failed, error recorded in LaunchDarkly: " + a.error);
            return;
        }
        System.out.printf("Tokens: %d in / %d out, %d ms%n", a.inputTokens, a.outputTokens, a.latencyMs);
        System.out.println("Reply:  " + a.text);
        if (a.judgeScore != null) {
            System.out.printf("Accuracy judge %s: %.2f%s%n", a.judgeKey, a.judgeScore,
                    a.judgeReasoning != null ? " — " + a.judgeReasoning : "");
        } else if (a.judgeError != null) {
            System.out.println("Accuracy judge: " + a.judgeError);
        }
    }
}
