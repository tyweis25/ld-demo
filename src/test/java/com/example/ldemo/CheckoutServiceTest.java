package com.example.ldemo;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit tests for checkout helpers and the booking-sidecar proxy. No LaunchDarkly network. */
class CheckoutServiceTest {

    @Test
    void queryDecodesPairsAndEmptyValues() {
        assertTrue(CheckoutService.query(null).isEmpty());
        assertTrue(CheckoutService.query("").isEmpty());

        Map<String, String> q = CheckoutService.query(
                "user=amelia&name=Amelia+Smith&question=need+a+sitter&confirm=true&empty=");
        assertEquals("amelia", q.get("user"));
        assertEquals("Amelia Smith", q.get("name"));
        assertEquals("need a sitter", q.get("question"));
        assertEquals("true", q.get("confirm"));
        assertEquals("", q.get("empty"));
    }

    @Test
    void bookingQueryDefaultsPlanFromTierAndOmitsQuestionUnlessAsked() {
        String status = CheckoutService.bookingQuery(Map.of(), false);
        assertTrue(status.contains("user=anonymous"));
        assertTrue(status.contains("plan=free"));
        assertFalse(status.contains("question="));
        assertFalse(status.contains("confirm="));

        String ask = CheckoutService.bookingQuery(Map.of(
                "user", "amelia",
                "name", "Amelia Smith",
                "tier", "enterprise",
                "question", "Saturday evening, 2 kids",
                "confirm", "true"), true);
        assertTrue(ask.contains("user=amelia"));
        assertTrue(ask.contains("plan=enterprise"));
        assertTrue(ask.contains("confirm=true"));
        assertTrue(ask.contains("question=Saturday+evening") || ask.contains("question=Saturday%20evening"));
    }

    @Test
    void parseAmountRejectsNullZeroNegativeAndHuge() {
        assertEquals(0, CheckoutService.parseAmount(null), 0.0001);
        assertEquals(0, CheckoutService.parseAmount("0"), 0.0001);
        assertEquals(0, CheckoutService.parseAmount("-5"), 0.0001);
        assertEquals(0, CheckoutService.parseAmount("100000"), 0.0001);
        assertEquals(99_999, CheckoutService.parseAmount("99999"), 0.0001);
        assertEquals(12.5, CheckoutService.parseAmount("12.5"), 0.0001);
    }

    @Test
    void contextFromDefaultsAnonymousBuyerOnFreeTier() {
        var ctx = CheckoutService.contextFrom(Map.of());
        assertEquals("user-anonymous", ctx.getIndividualContext("user").getKey());
        assertEquals("buyer", ctx.getIndividualContext("user").getValue("role").stringValue());
        assertEquals("free", ctx.getIndividualContext("user").getValue("plan").stringValue());
        assertEquals("org-ld", ctx.getIndividualContext("organization").getKey());
        assertEquals("free", ctx.getIndividualContext("organization").getValue("tier").stringValue());
    }

    @Test
    void loadPageIncludesAssistantJudgeAndBookingControls() throws IOException {
        String html = new String(CheckoutService.loadPage(), StandardCharsets.UTF_8);
        assertTrue(html.contains("babysitting-service-reply-accuracy"));
        assertTrue(html.contains("data-book=\"confirm\""));
        assertTrue(html.contains("data-book=\"ask\""));
        assertTrue(html.contains("full-booking-agent"));
        assertTrue(html.contains("/api/booking"));
        assertTrue(html.contains("/api/assistant"));
        assertTrue(html.contains("/api/events"));
        assertTrue(html.contains("EventSource"));
        assertTrue(html.contains("data-action=\"remediate\""));
        assertTrue(html.contains("One-Page Booking") || html.contains("one-page") || html.contains("Address, Payment, And Review Are All On This Page"));
        assertTrue(html.contains("X-LD-Demo"));
    }

    @Test
    void callBookingHelperExplainsWhenSidecarIsMissingOrUnreachable() {
        String missing = CheckoutService.callBookingHelper("", "GET", "/status");
        assertTrue(missing.contains("sidecar is not running"));
        String blank = CheckoutService.callBookingHelper("   ", "GET", "/status");
        assertTrue(blank.contains("sidecar is not running"));
        String down = CheckoutService.callBookingHelper("http://127.0.0.1:1", "GET", "/status?user=amelia");
        assertTrue(down.contains("Couldn't reach the booking agent sidecar"));
    }

    @Test
    void callBookingHelperProxiesStatusAndConfirmToALocalSidecar() throws Exception {
        HttpServer sidecar = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        sidecar.createContext("/status", ex -> write(ex, 200, "{\"ok\":true,\"variation\":\"full-booking-agent\",\"can_book\":true}"));
        sidecar.createContext("/ask", ex -> {
            String query = String.valueOf(ex.getRequestURI().getRawQuery());
            boolean confirm = query.contains("confirm=true");
            String body = confirm
                    ? "{\"ok\":true,\"booked\":true,\"reply\":\"Booked Maya Chen (bk-1).\"}"
                    : "{\"ok\":true,\"booked\":false,\"reply\":\"I can book after you confirm.\"}";
            write(ex, 200, body);
        });
        sidecar.createContext("/empty", ex -> write(ex, 200, ""));
        sidecar.start();
        try {
            String base = "http://127.0.0.1:" + sidecar.getAddress().getPort();
            String status = CheckoutService.callBookingHelper(base, "GET", "/status?user=amelia");
            assertTrue(status.contains("full-booking-agent"));
            assertTrue(status.contains("\"can_book\":true"));

            String quoted = CheckoutService.callBookingHelper(base, "POST",
                    "/ask?" + CheckoutService.bookingQuery(Map.of(
                            "user", "amelia", "name", "Amelia Smith", "plan", "enterprise",
                            "question", "need a sitter", "confirm", "false"), true));
            assertTrue(quoted.contains("after you confirm"));
            assertFalse(quoted.contains("\"booked\":true"));

            String booked = CheckoutService.callBookingHelper(base, "POST",
                    "/ask?" + CheckoutService.bookingQuery(Map.of(
                            "user", "amelia", "name", "Amelia Smith", "plan", "enterprise",
                            "question", "need a sitter", "confirm", "true"), true));
            assertTrue(booked.contains("Booked Maya Chen"));
            assertTrue(booked.contains("\"booked\":true"));

            String empty = CheckoutService.callBookingHelper(base, "GET", "/empty");
            assertTrue(empty.contains("Empty response from the booking agent"));
        } finally {
            sidecar.stop(0);
        }
    }

    private static void write(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
