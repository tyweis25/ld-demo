package com.example.ldemo;

import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.LDClient;

import java.util.Scanner;

/**
 * LaunchDarkly feature flag demo: Amelia's Babysitting Service rolls out a new booking flow
 * account by account, without a redeploy.
 *
 * Uses MULTI-CONTEXTS (user + organization) so targeting can happen at the account level:
 * everyone at an enterprise customer gets the new flow together, regardless of their own role.
 *
 * Create this boolean flag in your LaunchDarkly project: new-checkout-flow
 * Put your server-side SDK key in LD_SDK_KEY (.env).
 * Part 2: individual-target user-harper and/or user-liam; rule-target organization.tier = enterprise.
 * Individual targets are evaluated before rules — Liam can get true even when a free-tier rule would deny it.
 * Run: export LD_SDK_KEY=sdk-xxxx && mvn -q compile exec:java
 */
public class FeatureFlagDemo {

    /** Create boolean flag new-checkout-flow in LaunchDarkly. */
    private static final String FLAG_KEY = "new-checkout-flow";
    static final ContextKind ORGANIZATION = ContextKind.of("organization");

    /** A shopper is a user who belongs to an organization (the customer account). */
    static final class Shopper {
        final LDContext user;
        final LDContext org;
        final LDContext multi;

        Shopper(String userKey, String name, String role, String orgKey, String orgName, String tier) {
            this.user = LDContext.builder(userKey).name(name).set("role", role).build();
            this.org = LDContext.builder(ORGANIZATION, orgKey).name(orgName).set("tier", tier).build();
            // One evaluation context that carries both kinds, so rules can target either.
            this.multi = LDContext.createMulti(user, org);
        }
    }

    public static void main(String[] args) throws Exception {
        String sdkKey = System.getenv("LD_SDK_KEY");
        if (sdkKey == null || sdkKey.isBlank()) {
            System.err.println("Missing LD_SDK_KEY. Export your server-side SDK key first.");
            System.exit(1);
        }

        Shopper amelia = new Shopper("user-amelia", "Amelia Smith", "admin",  "org-ld",     "Amelia's Babysitting Service", "enterprise");
        Shopper harper = new Shopper("user-harper", "Harper Reed",  "parent", "org-ld",     "Amelia's Babysitting Service", "enterprise");
        Shopper liam   = new Shopper("user-liam",   "Liam Carter",  "parent", "org-bright", "Parkside Parents Co-op",       "free");
        Shopper[] shoppers = {amelia, harper, liam};

        // The client streams flag rules and evaluates them locally, so evaluation is fast
        // and keeps working from cache if LaunchDarkly becomes unreachable.
        try (LDClient client = new LDClient(sdkKey)) {
            if (!client.isInitialized()) {
                System.err.println("Could not initialize the LaunchDarkly client. Check the SDK key and network.");
                System.exit(1);
            }
            System.out.println("Connected to LaunchDarkly.\n");

            System.out.println("== Step 1: evaluate the flag per shopper (user + organization) ==");
            showEvaluations(client, shoppers);

            System.out.println("\n== Step 2: run checkouts and send metric events ==");
            checkout(client, amelia, 129.00);
            checkout(client, harper, 59.99);
            checkout(client, liam, 24.50);

            System.out.println("\n== Step 3: live change ==");
            client.getFlagTracker().addFlagValueChangeListener(FLAG_KEY, amelia.multi, event ->
                    System.out.printf(">>> LIVE UPDATE: '%s' changed for Amelia's Babysitting Service: %s -> %s%n",
                            event.getKey(), event.getOldValue(), event.getNewValue()));

            System.out.println("Go to the LaunchDarkly dashboard and change the targeting (or turn the flag off).");
            System.out.println("Watch this console for the update. Press Enter when done.");
            new Scanner(System.in).nextLine();

            System.out.println("\n== Re-evaluating after your change (no restart, no redeploy) ==");
            showEvaluations(client, shoppers);
        }
        System.out.println("\nDone. Events flushed and client closed.");
    }

    private static void showEvaluations(LDClient client, Shopper[] shoppers) {
        for (Shopper s : shoppers) {
            // The default value (false) is the safe fallback if the flag is missing or LD is unreachable.
            EvaluationDetail<Boolean> detail = client.boolVariationDetail(FLAG_KEY, s.multi, false);
            System.out.printf("%-12s role=%-6s org=%-15s tier=%-10s -> %-5s reason: %s%n",
                    s.user.getName(), s.user.getValue("role").stringValue(),
                    s.org.getName(), s.org.getValue("tier").stringValue(),
                    detail.getValue(), detail.getReason());
        }
    }

    private static void checkout(LDClient client, Shopper s, double amount) {
        boolean newFlow = client.boolVariation(FLAG_KEY, s.multi, false);
        System.out.printf("%s (%s) -> %s checkout, order $%.2f%n",
                s.user.getName(), s.org.getName(), newFlow ? "NEW one-page" : "LEGACY multi-step", amount);

        // Custom events feed metrics, experiments, and guarded rollouts in LaunchDarkly.
        client.track("checkout-completed", s.multi);
        client.trackMetric("checkout-revenue", s.multi, LDValue.ofNull(), amount);
    }
}
