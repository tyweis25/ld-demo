package com.example.ldemo;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.server.LDClient;
import com.launchdarkly.sdk.server.ai.LDAIClient;
import com.launchdarkly.sdk.server.ai.LDAIClientImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Amelia's Babysitting Service as a small web app: a front end plus the API behind it.
 *
 *   GET  /                 the checkout page (src/main/resources/web/index.html)
 *   GET  /api/config       what this server can do (for example, whether the kill switch button works)
 *   GET  /api/checkout     which checkout flow, and is the AI assistant on, for this shopper?
 *   POST /api/order        place an order, sending events to LaunchDarkly
 *   POST /api/assistant    ask the AI assistant (kill switch + AI Config + model call)
 *   GET  /api/booking-status  which booking-helper variation this shopper gets (Python sidecar)
 *   POST /api/booking      ask the booking agent (Python sidecar evaluates the agent config)
 *   POST /api/killswitch   turn the AI assistant flag off or on through the LaunchDarkly REST API
 *   GET  /health, /ready   liveness and readiness
 *
 * The browser never talks to LaunchDarkly and never sees your SDK key. This server evaluates flags
 * with the server-side SDK and returns only the results.
 *
 * Config (environment variables):
 *   LD_SDK_KEY                required
 *   ANTHROPIC_API_KEY         optional, makes the assistant call a real model
 *   LD_API_TOKEN, LD_PROJECT_KEY, LD_ENV_KEY
 *                             optional, together they enable the kill switch button
 *   LD_API_BASE               optional, defaults to https://app.launchdarkly.com (EU: https://app.eu.launchdarkly.com)
 *   FLAG_KEY, APP_VERSION, PORT, HOST (HOST defaults to 127.0.0.1 so only this machine can reach it)
 *
 * Run: ./run.sh web   then open http://localhost:8080
 */
public class CheckoutService {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final String JSON = "application/json";

    public static void main(String[] args) throws Exception {
        String sdkKey = env("LD_SDK_KEY");
        if (sdkKey.isEmpty()) {
            System.err.println("Missing LD_SDK_KEY.");
            System.exit(1);
        }
        String flagKey = System.getenv().getOrDefault("FLAG_KEY", "new-checkout-flow");
        String version = System.getenv().getOrDefault("APP_VERSION", "1.0.0");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String host = System.getenv().getOrDefault("HOST", "127.0.0.1");
        String anthropicKey = env("ANTHROPIC_API_KEY");

        // The kill switch button needs a LaunchDarkly API token. It is different from the SDK key:
        // an SDK key can read flags, only an API token can change them.
        String apiToken = env("LD_API_TOKEN");
        String projectKey = env("LD_PROJECT_KEY");
        String envKey = env("LD_ENV_KEY");
        String apiBase = System.getenv().getOrDefault("LD_API_BASE", "https://app.launchdarkly.com");
        boolean canToggle = !apiToken.isEmpty() && projectKey.matches("[A-Za-z0-9._-]+") && !envKey.isEmpty();
        String bookingHelperUrl = env("BOOKING_HELPER_URL");

        byte[] page = loadPage();

        LDClient client = new LDClient(sdkKey); // waits up to 5 seconds to connect
        System.out.println("LaunchDarkly client initialized: " + client.isInitialized());
        LDAIClient aiClient = new LDAIClientImpl(client);

        // Watch releases happen in the terminal, with no redeploy and no restart.
        client.getFlagTracker().addFlagChangeListener(event ->
                System.out.println("Flag changed in LaunchDarkly: " + event.getKey()));

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));

        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                send(ex, 200, "text/html; charset=utf-8", page);
            } else {
                send(ex, 404, JSON, "{\"error\":\"not found\"}");
            }
        });
        server.createContext("/health", ex -> send(ex, 200, JSON, "{\"status\":\"ok\"}"));
        server.createContext("/ready", ex -> {
            boolean ready = client.isInitialized();
            send(ex, ready ? 200 : 503, JSON, "{\"ready\":" + ready + "}");
        });

        server.createContext("/api/config", ex -> send(ex, 200, JSON, LDValue.buildObject()
                .put("can_toggle", canToggle)
                .put("assistant_flag", AiAssistant.KILL_SWITCH_FLAG)
                .put("judge_key", AiAssistant.JUDGE_KEY)
                .put("live_model", !anthropicKey.isEmpty())
                .put("booking_helper", !bookingHelperUrl.isEmpty())
                .build().toJsonString()));

        server.createContext("/api/checkout", ex -> {
            if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, JSON, "{\"error\":\"use GET\"}"); return; }
            LDContext ctx = contextFrom(query(ex.getRequestURI().getRawQuery()));
            EvaluationDetail<Boolean> d = client.boolVariationDetail(flagKey, ctx, false);
            // The kill switch defaults to false: if LaunchDarkly is unreachable, the assistant stays off.
            boolean assistantOn = client.boolVariation(AiAssistant.KILL_SWITCH_FLAG, ctx, false);
            send(ex, 200, JSON, LDValue.buildObject()
                    .put("app_version", version)
                    .put("flag", flagKey)
                    .put("enabled", d.getValue())
                    .put("flow", flowName(d.getValue()))
                    .put("reason_kind", d.getReason().getKind().name())
                    .put("reason", String.valueOf(d.getReason()))
                    .put("assistant_enabled", assistantOn)
                    .build().toJsonString());
        });

        server.createContext("/api/order", ex -> {
            if (!requirePost(ex)) return;
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            LDContext ctx = contextFrom(q);
            double amount = parseAmount(q.get("amount"));
            boolean enabled = client.boolVariation(flagKey, ctx, false);

            // Real events from a real click. These feed metrics, experiments, and guarded rollouts.
            client.track("checkout-completed", ctx);
            client.trackMetric("checkout-revenue", ctx, LDValue.ofNull(), amount);

            System.out.printf("Booking confirmed: %s, %s, $%.2f%n", q.getOrDefault("name", "anonymous"), flowName(enabled), amount);
            send(ex, 200, JSON, LDValue.buildObject()
                    .put("ok", true)
                    .put("flow", flowName(enabled))
                    .put("amount", amount)
                    .build().toJsonString());
        });

        server.createContext("/api/assistant", ex -> {
            if (!requirePost(ex)) return;
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String question = q.getOrDefault("question", "").trim();
            if (question.isEmpty()) { send(ex, 400, JSON, "{\"error\":\"Type a question first.\"}"); return; }
            if (question.length() > 500) question = question.substring(0, 500);
            try {
                AiAssistant.Answer a = AiAssistant.ask(client, aiClient, contextFrom(q), question, anthropicKey);
                if (!a.available) {
                    send(ex, 200, JSON, LDValue.buildObject().put("available", false).put("reason", a.reason).build().toJsonString());
                } else if (a.error != null) {
                    send(ex, 200, JSON, LDValue.buildObject().put("available", true).put("ok", false)
                            .put("model", a.model).put("error", String.valueOf(a.error)).build().toJsonString());
                } else {
                    ObjectBuilder reply = LDValue.buildObject().put("available", true).put("ok", true)
                            .put("model", a.model).put("reply", a.text)
                            .put("input_tokens", a.inputTokens).put("output_tokens", a.outputTokens)
                            .put("latency_ms", a.latencyMs).put("live", a.live);
                    if (a.variation != null) reply.put("variation", a.variation);
                    if (a.judgeKey != null) reply.put("judge_key", a.judgeKey).put("judge_live", a.judgeLive);
                    if (a.judgeScore != null) reply.put("judge_score", a.judgeScore);
                    if (a.judgeReasoning != null) reply.put("judge_reasoning", a.judgeReasoning);
                    if (a.judgeError != null) reply.put("judge_error", a.judgeError);
                    send(ex, 200, JSON, reply.build().toJsonString());
                }
            } catch (RuntimeException e) {
                send(ex, 200, JSON, LDValue.buildObject().put("available", true).put("ok", false)
                        .put("error", "The assistant hit an error: " + e.getMessage()).build().toJsonString());
            }
        });

        server.createContext("/api/booking-status", ex -> {
            if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, JSON, "{\"error\":\"use GET\"}"); return; }
            send(ex, 200, JSON, callBookingHelper(bookingHelperUrl, "GET", "/status?" + bookingQuery(query(ex.getRequestURI().getRawQuery()), false)));
        });

        server.createContext("/api/booking", ex -> {
            if (!requirePost(ex)) return;
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String question = q.getOrDefault("question", "").trim();
            if (question.isEmpty()) { send(ex, 400, JSON, "{\"ok\":false,\"error\":\"Type a request first.\"}"); return; }
            if (question.length() > 500) q.put("question", question.substring(0, 500));
            send(ex, 200, JSON, callBookingHelper(bookingHelperUrl, "POST", "/ask?" + bookingQuery(q, true)));
        });

        server.createContext("/api/killswitch", ex -> {
            if (!requirePost(ex)) return;
            if (!canToggle) {
                send(ex, 400, JSON, LDValue.buildObject().put("ok", false)
                        .put("error", "The kill switch button isn't set up. Add LD_API_TOKEN, LD_PROJECT_KEY, and LD_ENV_KEY to .env, or flip the flag in the dashboard.")
                        .build().toJsonString());
                return;
            }
            String state = query(ex.getRequestURI().getRawQuery()).getOrDefault("state", "");
            if (!state.equals("on") && !state.equals("off")) {
                send(ex, 400, JSON, "{\"ok\":false,\"error\":\"state must be on or off\"}");
                return;
            }
            send(ex, 200, JSON, setKillSwitch(apiBase, apiToken, projectKey, envKey, state.equals("on")));
        });

        // Flush events and close cleanly on Ctrl+C or SIGTERM.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            try { client.close(); } catch (IOException ignored) { }
        }));

        server.start();
        System.out.printf("Amelia's Babysitting Service %s is running at http://localhost:%d (flag: %s)%n", version, port, flagKey);
        System.out.println("AI assistant model calls: " + (anthropicKey.isEmpty() ? "SIMULATED" : "LIVE (Anthropic)"));
        System.out.println("Kill switch button: " + (canToggle ? "enabled" : "disabled (no LD_API_TOKEN / LD_PROJECT_KEY / LD_ENV_KEY)"));
        System.out.println("Booking agent sidecar: " + (bookingHelperUrl.isEmpty() ? "not configured" : bookingHelperUrl));
    }

    // ---------------------------------------------------------------------------------------------

    /** Turns the kill switch flag's targeting on or off with LaunchDarkly's REST API (semantic patch). */
    private static String setKillSwitch(String apiBase, String token, String project, String env, boolean on) {
        String url = apiBase + "/api/v2/flags/" + project + "/" + AiAssistant.KILL_SWITCH_FLAG;
        String body = LDValue.buildObject()
                .put("environmentKey", env)
                .put("comment", "Toggled from Amelia's Babysitting Service demo")
                .put("instructions", LDValue.buildArray()
                        .add(LDValue.buildObject().put("kind", on ? "turnFlagOn" : "turnFlagOff").build())
                        .build())
                .build().toJsonString();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", token)
                    .header("Content-Type", "application/json; domain-model=launchdarkly.semanticpatch")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                System.out.println("Kill switch: turned " + AiAssistant.KILL_SWITCH_FLAG + (on ? " on" : " off") + " through the REST API");
                return LDValue.buildObject().put("ok", true).put("state", on ? "on" : "off").build().toJsonString();
            }
            String why;
            if (code == 401 || code == 403) why = "LaunchDarkly rejected the API token. Check that it can update flags in this project.";
            else if (code == 404) why = "LaunchDarkly couldn't find the project or the flag. Check LD_PROJECT_KEY and that " + AiAssistant.KILL_SWITCH_FLAG + " exists.";
            else if (code == 400) why = "LaunchDarkly rejected the request. Check that LD_ENV_KEY is a real environment key.";
            else why = "LaunchDarkly returned HTTP " + code + ".";
            return LDValue.buildObject().put("ok", false).put("error", why).build().toJsonString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"ok\":false,\"error\":\"The request was interrupted.\"}";
        } catch (IOException | RuntimeException e) {
            return LDValue.buildObject().put("ok", false)
                    .put("error", "Couldn't reach LaunchDarkly's API. Check your internet connection (and LD_API_BASE if you set it).")
                    .build().toJsonString();
        }
    }

    /**
     * POST only, and only with our custom header. Browsers won't let another website add a custom
     * header to a cross-site request without permission, so this stops a random web page from
     * making your browser call this local server.
     */
    private static boolean requirePost(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            send(ex, 405, JSON, "{\"error\":\"use POST\"}");
            return false;
        }
        if (ex.getRequestHeaders().getFirst("X-LD-Demo") == null) {
            send(ex, 403, JSON, "{\"error\":\"missing X-LD-Demo header\"}");
            return false;
        }
        return true;
    }

    /** Same multi-context shape as the other demos: a user who belongs to an organization. */
    static LDContext contextFrom(Map<String, String> q) {
        String user = q.getOrDefault("user", "anonymous");
        // plan mirrors tier so AI Config rules that target user.plan (enterprise vs free) match the shopper switcher.
        String plan = q.getOrDefault("plan", q.getOrDefault("tier", "free"));
        LDContext userCtx = LDContext.builder("user-" + user)
                .name(q.getOrDefault("name", user))
                .set("role", q.getOrDefault("role", "buyer"))
                .set("plan", plan).build();
        // A real service would look these attributes up server-side instead of trusting the caller.
        LDContext orgCtx = LDContext.builder(FeatureFlagDemo.ORGANIZATION, q.getOrDefault("org", "org-ld"))
                .set("tier", q.getOrDefault("tier", "free")).build();
        return LDContext.createMulti(userCtx, orgCtx);
    }

    static String flowName(boolean enabled) {
        return enabled ? "new one-page booking" : "classic multi-step booking";
    }

    static String bookingQuery(Map<String, String> q, boolean includeQuestion) {
        String user = q.getOrDefault("user", "anonymous");
        String name = q.getOrDefault("name", user);
        String plan = q.getOrDefault("plan", q.getOrDefault("tier", "free"));
        StringBuilder out = new StringBuilder();
        out.append("user=").append(enc(user))
                .append("&name=").append(enc(name))
                .append("&plan=").append(enc(plan));
        if (includeQuestion) {
            out.append("&question=").append(enc(q.getOrDefault("question", "")))
                    .append("&confirm=").append(enc(q.getOrDefault("confirm", "false")));
        }
        return out.toString();
    }

    static String callBookingHelper(String base, String method, String pathAndQuery) {
        if (base == null || base.isBlank()) {
            return "{\"ok\":false,\"error\":\"The booking agent sidecar is not running. Start the page with ./run.sh web.\"}";
        }
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + pathAndQuery))
                    .timeout(Duration.ofSeconds(45));
            if ("POST".equals(method)) {
                req.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                req.GET();
            }
            HttpResponse<String> response = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            return (body == null || body.isBlank()) ? "{\"ok\":false,\"error\":\"Empty response from the booking agent.\"}" : body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"ok\":false,\"error\":\"The booking agent request was interrupted.\"}";
        } catch (IOException | RuntimeException e) {
            return "{\"ok\":false,\"error\":\"Couldn't reach the booking agent sidecar. Start the page with ./run.sh web.\"}";
        }
    }

    static double parseAmount(String raw) {
        try {
            double v = Double.parseDouble(raw);
            return (v > 0 && v < 100_000) ? v : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.trim();
    }

    static byte[] loadPage() throws IOException {
        try (InputStream in = CheckoutService.class.getResourceAsStream("/web/index.html")) {
            if (in == null) throw new IOException("web/index.html not found on the classpath");
            return in.readAllBytes();
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        send(ex, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
