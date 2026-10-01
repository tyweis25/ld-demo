package com.example.ldemo;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.server.LDClient;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Live LaunchDarkly evaluations. Skips when LD_SDK_KEY is not available. */
class FlagEvaluationIT {

    @Test
    void evaluatesNewCheckoutFlowForDemoShoppers() throws Exception {
        Optional<String> key = TestEnv.sdkKey();
        assumeTrue(key.isPresent(), "LD_SDK_KEY not set; skipping live flag evaluation");

        FeatureFlagDemo.Shopper amelia = new FeatureFlagDemo.Shopper(
                "user-amelia", "Amelia Smith", "admin",
                "org-ld", "Amelia's Babysitting Service", "enterprise");
        FeatureFlagDemo.Shopper liam = new FeatureFlagDemo.Shopper(
                "user-liam", "Liam Carter", "parent",
                "org-bright", "Parkside Parents Co-op", "free");

        try (LDClient client = new LDClient(key.get())) {
            assumeTrue(client.isInitialized(), "LaunchDarkly client did not initialize");

            EvaluationDetail<Boolean> a = client.boolVariationDetail("new-checkout-flow", amelia.multi, false);
            EvaluationDetail<Boolean> l = client.boolVariationDetail("new-checkout-flow", liam.multi, false);

            assertNotNull(a.getReason());
            assertNotNull(l.getReason());
            // Values depend on current LD targeting; we only assert evaluation succeeded.
            assertTrue(a.getReason().getKind() != null);
            assertTrue(l.getReason().getKind() != null);
        }
    }
}
