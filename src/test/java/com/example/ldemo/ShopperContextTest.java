package com.example.ldemo;

import com.launchdarkly.sdk.LDContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit tests for shopper / multi-context shape (no LaunchDarkly network). */
class ShopperContextTest {

    @Test
    void featureFlagDemoShopperBuildsUserAndOrgMultiContext() {
        FeatureFlagDemo.Shopper amelia = new FeatureFlagDemo.Shopper(
                "user-amelia", "Amelia Smith", "admin",
                "org-ld", "Amelia's Babysitting Service", "enterprise");

        assertEquals("Amelia Smith", amelia.user.getName());
        assertEquals("admin", amelia.user.getValue("role").stringValue());
        assertEquals("enterprise", amelia.org.getValue("tier").stringValue());
        assertTrue(amelia.multi.isMultiple());
        assertEquals(amelia.user.getKey(), amelia.multi.getIndividualContext("user").getKey());
        assertEquals(amelia.org.getKey(), amelia.multi.getIndividualContext("organization").getKey());
    }

    @Test
    void checkoutServiceContextSetsPlanFromTierForAiConfigRules() {
        LDContext amelia = CheckoutService.contextFrom(Map.of(
                "user", "amelia",
                "name", "Amelia Smith",
                "org", "org-ld",
                "tier", "enterprise",
                "role", "admin"));
        LDContext liam = CheckoutService.contextFrom(Map.of(
                "user", "liam",
                "name", "Liam Carter",
                "org", "org-bright",
                "tier", "free",
                "role", "parent"));

        assertEquals("enterprise", amelia.getIndividualContext("user").getValue("plan").stringValue());
        assertEquals("free", liam.getIndividualContext("user").getValue("plan").stringValue());
        assertEquals("enterprise", amelia.getIndividualContext("organization").getValue("tier").stringValue());
        assertEquals("free", liam.getIndividualContext("organization").getValue("tier").stringValue());
    }

    @Test
    void flowNameAndParseAmountHelpers() {
        assertEquals("new one-page booking", CheckoutService.flowName(true));
        assertEquals("classic multi-step booking", CheckoutService.flowName(false));
        assertEquals(42.5, CheckoutService.parseAmount("42.5"), 0.0001);
        assertEquals(0, CheckoutService.parseAmount("nope"), 0.0001);
        assertEquals(0, CheckoutService.parseAmount("-1"), 0.0001);
    }
}
