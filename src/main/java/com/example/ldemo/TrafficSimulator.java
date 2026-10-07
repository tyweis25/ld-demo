package com.example.ldemo;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.LDClient;

import java.util.Random;

/**
 * Generates realistic checkout traffic so LaunchDarkly has data for:
 *   - an EXPERIMENT on checkout-revenue / checkout-completed ("is the new flow better?")
 *   - a GUARDED ROLLOUT watching checkout-error ("is the new flow safe?")
 *
 * Every simulated shopper is a multi-context (user + organization), same as FeatureFlagDemo.
 *
 * Options (all optional):
 *   --flag=new-checkout-flow   flag to evaluate (create this boolean flag; use new-checkout-service for guarded)
 *   --minutes=15               how long to run
 *   --rate=20                  sessions per second
 *   --users=3000               size of the simulated user pool (each counts as a context in LD)
 *   --bad                      make the NEW variation throw many more errors, to trigger a rollback
 *
 * Requires LD_SDK_KEY (server-side SDK key in .env).
 *
 * Run:
 *   mvn -q compile exec:java -Dexec.mainClass=com.example.ldemo.TrafficSimulator -Dexec.args="--minutes=20"
 *   mvn -q compile exec:java -Dexec.mainClass=com.example.ldemo.TrafficSimulator -Dexec.args="--flag=new-checkout-service --bad"
 */
public class TrafficSimulator {

    private static final String[][] ORGS = {
            {"org-ld",       "Amelia's Babysitting Service", "enterprise"},
            {"org-globex",   "Sunshine Sitters Club",        "enterprise"},
            {"org-initech",  "Little Oaks Family Hub",       "pro"},
            {"org-umbrella", "Neighborhood Nanny Network",   "pro"},
            {"org-bright",   "Parkside Parents Co-op",       "free"},
    };

    // Baseline behavior. The NEW variation converts a bit better and spends a bit more,
    // so an experiment has a real winner to find.
    private static final double LEGACY_CONVERSION = 0.25, NEW_CONVERSION = 0.32;
    private static final double LEGACY_AVG_ORDER = 55.0,  NEW_AVG_ORDER = 62.0;
    private static final double BASE_ERROR_RATE = 0.02,   BAD_ERROR_RATE = 0.15;

    /** CLI options for the simulator (package-visible for unit tests). */
    static final class Options {
        final String flagKey;
        final int minutes;
        final int rate;
        final int users;
        final boolean bad;

        Options(String flagKey, int minutes, int rate, int users, boolean bad) {
            this.flagKey = flagKey;
            this.minutes = minutes;
            this.rate = rate;
            this.users = users;
            this.bad = bad;
        }
    }

    static Options parseOptions(String[] args) {
        String flagKey = "new-checkout-flow";
        int minutes = 15, rate = 20, users = 3000;
        boolean bad = false;
        for (String a : args) {
            if (a.startsWith("--flag=")) flagKey = a.substring(7);
            else if (a.startsWith("--minutes=")) minutes = Integer.parseInt(a.substring(10));
            else if (a.startsWith("--rate=")) rate = Integer.parseInt(a.substring(7));
            else if (a.startsWith("--users=")) users = Integer.parseInt(a.substring(8));
            else if (a.equals("--bad")) bad = true;
        }
        return new Options(flagKey, minutes, rate, users, bad);
    }

    static double newErrorRate(boolean bad) {
        return bad ? BAD_ERROR_RATE : BASE_ERROR_RATE;
    }

    /** One simulated shopper multi-context (same shape as the main loop). */
    static LDContext simContext(int userIndex) {
        String[] org = ORGS[Math.floorMod(userIndex, ORGS.length)];
        LDContext user = LDContext.builder("sim-user-" + userIndex)
                .set("role", userIndex % 10 == 0 ? "admin" : "buyer").build();
        LDContext orgCtx = LDContext.builder(FeatureFlagDemo.ORGANIZATION, org[0])
                .name(org[1]).set("tier", org[2]).build();
        return LDContext.createMulti(user, orgCtx);
    }

    public static void main(String[] args) throws Exception {
        String sdkKey = System.getenv("LD_SDK_KEY");
        if (sdkKey == null || sdkKey.isBlank()) {
            System.err.println("Missing LD_SDK_KEY. Export your server-side SDK key first.");
            System.exit(1);
        }

        Options opt = parseOptions(args);
        String flagKey = opt.flagKey;
        int minutes = opt.minutes, rate = opt.rate, users = opt.users;
        double newErrorRate = newErrorRate(opt.bad);

        System.out.printf("Simulating %d sessions/sec for %d min on flag '%s' (%d users). NEW error rate: %.0f%%%n%n",
                rate, minutes, flagKey, users, newErrorRate * 100);

        Random rnd = new Random();
        try (LDClient client = new LDClient(sdkKey)) {
            if (!client.isInitialized()) {
                System.err.println("Could not initialize the LaunchDarkly client.");
                System.exit(1);
            }

            // Per-report-window counters, index 0 = legacy (false), 1 = new (true)
            long[] sessions = new long[2], conversions = new long[2], errors = new long[2];
            double[] revenue = new double[2];

            long end = System.currentTimeMillis() + minutes * 60_000L;
            long nextReport = System.currentTimeMillis() + 10_000L;

            while (System.currentTimeMillis() < end) {
                for (int i = 0; i < rate; i++) {
                    int u = rnd.nextInt(users);
                    LDContext ctx = simContext(u);

                    boolean isNew = client.boolVariation(flagKey, ctx, false);
                    int v = isNew ? 1 : 0;
                    sessions[v]++;

                    if (rnd.nextDouble() < (isNew ? newErrorRate : BASE_ERROR_RATE)) {
                        client.track("checkout-error", ctx);
                        errors[v]++;
                        continue;
                    }
                    if (rnd.nextDouble() < (isNew ? NEW_CONVERSION : LEGACY_CONVERSION)) {
                        double avg = isNew ? NEW_AVG_ORDER : LEGACY_AVG_ORDER;
                        double amount = Math.round(avg * (0.5 + rnd.nextDouble()) * 100) / 100.0;
                        client.track("checkout-completed", ctx);
                        client.trackMetric("checkout-revenue", ctx, LDValue.ofNull(), amount);
                        conversions[v]++;
                        revenue[v] += amount;
                    }
                }
                Thread.sleep(1000);

                if (System.currentTimeMillis() >= nextReport) {
                    report(sessions, conversions, errors, revenue);
                    sessions = new long[2]; conversions = new long[2]; errors = new long[2]; revenue = new double[2];
                    nextReport = System.currentTimeMillis() + 10_000L;
                }
            }
            client.flush();
        }
        System.out.println("\nDone. Results appear on the flag's Monitoring tab and in the experiment within a few minutes.");
    }

    private static void report(long[] s, long[] c, long[] e, double[] r) {
        long total = s[0] + s[1];
        double newShare = total == 0 ? 0 : 100.0 * s[1] / total;
        System.out.printf("Last 10s | NEW share %5.1f%% | %s | %s%n", newShare, line("LEGACY", 0, s, c, e, r), line("NEW", 1, s, c, e, r));
    }

    private static String line(String label, int v, long[] s, long[] c, long[] e, double[] r) {
        if (s[v] == 0) return String.format("%-6s no traffic", label);
        return String.format("%-6s conv %4.1f%% err %4.1f%% $/session %5.2f",
                label, 100.0 * c[v] / s[v], 100.0 * e[v] / s[v], r[v] / s[v]);
    }
}
