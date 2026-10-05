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
        assertEquals("user-amelia", amelia.getIndividualContext("user").getKey());
        assertEquals("user-liam", liam.getIndividualContext("user").getKey());
        assertTrue(amelia.isMultiple());
        assertTrue(liam.isMultiple());
    }

    @Test
    void harperSharesAmeliaOrganizationInTheConsoleDemo() {
        FeatureFlagDemo.Shopper amelia = new FeatureFlagDemo.Shopper(
                "user-amelia", "Amelia Smith", "admin",
                "org-ld", "Amelia's Babysitting Service", "enterprise");
        FeatureFlagDemo.Shopper harper = new FeatureFlagDemo.Shopper(
                "user-harper", "Harper Reed", "parent",
                "org-ld", "Amelia's Babysitting Service", "enterprise");
        assertEquals(amelia.org.getKey(), harper.org.getKey());
        assertEquals("enterprise", harper.org.getValue("tier").stringValue());
        assertEquals("parent", harper.user.getValue("role").stringValue());
        assertEquals("admin", amelia.user.getValue("role").stringValue());
    }

    @Test
    void flowNameAndParseAmountHelpers() {
        assertEquals("new one-page booking", CheckoutService.flowName(true));
        assertEquals("classic multi-step booking", CheckoutService.flowName(false));
        assertEquals(42.5, CheckoutService.parseAmount("42.5"), 0.0001);
        assertEquals(0, CheckoutService.parseAmount("nope"), 0.0001);
        assertEquals(0, CheckoutService.parseAmount("-1"), 0.0001);
    }

    @Test
    void bookingQueryUsesPlanAndOptionalQuestion() {
        String status = CheckoutService.bookingQuery(Map.of(
                "user", "amelia",
                "name", "Amelia Smith",
                "tier", "enterprise"), false);
        assertTrue(status.contains("user=amelia"));
        assertTrue(status.contains("plan=enterprise"));
        assertTrue(!status.contains("question="));

        String ask = CheckoutService.bookingQuery(Map.of(
                "user", "liam",
                "name", "Liam Carter",
                "plan", "free",
                "question", "need a sitter",
                "confirm", "true"), true);
        assertTrue(ask.contains("plan=free"));
        assertTrue(ask.contains("question=need+a+sitter") || ask.contains("question=need%20a%20sitter"));
        assertTrue(ask.contains("confirm=true"));
    }
}
