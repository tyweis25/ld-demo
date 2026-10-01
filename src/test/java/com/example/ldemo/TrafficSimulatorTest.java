package com.example.ldemo;

import com.launchdarkly.sdk.LDContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for TrafficSimulator helpers (no network). */
class TrafficSimulatorTest {

    @Test
    void parseOptionsDefaultsAndOverrides() {
        TrafficSimulator.Options defaults = TrafficSimulator.parseOptions(new String[0]);
        assertEquals("new-checkout-flow", defaults.flagKey);
        assertEquals(15, defaults.minutes);
        assertEquals(20, defaults.rate);
        assertEquals(3000, defaults.users);
        assertFalse(defaults.bad);

        TrafficSimulator.Options custom = TrafficSimulator.parseOptions(new String[]{
                "--flag=new-payment-service",
                "--minutes=30",
                "--rate=5",
                "--users=100",
                "--bad"
        });
        assertEquals("new-payment-service", custom.flagKey);
        assertEquals(30, custom.minutes);
        assertEquals(5, custom.rate);
        assertEquals(100, custom.users);
        assertTrue(custom.bad);
        assertEquals(0.15, TrafficSimulator.newErrorRate(true), 0.0001);
        assertEquals(0.02, TrafficSimulator.newErrorRate(false), 0.0001);
    }

    @Test
    void simContextIsStableMultiContext() {
        LDContext ctx = TrafficSimulator.simContext(0);
        assertTrue(ctx.isMultiple());
        assertEquals("sim-user-0", ctx.getIndividualContext("user").getKey());
        assertEquals("admin", ctx.getIndividualContext("user").getValue("role").stringValue());
        assertEquals("enterprise", ctx.getIndividualContext("organization").getValue("tier").stringValue());

        LDContext again = TrafficSimulator.simContext(0);
        assertEquals(ctx.getIndividualContext("organization").getKey(),
                again.getIndividualContext("organization").getKey());
    }
}
