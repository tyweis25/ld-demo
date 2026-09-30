# LaunchDarkly Java Demo: Amelia's Babysitting Service

Five pieces, one story:

1. `FeatureFlagDemo` - roll out a new booking flow **account by account** using multi-contexts (user + organization), with evaluation reasons, metric events, and a live flag change.
2. `TrafficSimulator` - generates realistic booking traffic so LaunchDarkly has data for an **experiment** and a **guarded rollout**.
3. `AiConfigDemo` (extra credit) - an AI support assistant whose model and prompt come from an AI Config, with live model calls, real token metrics, and a kill switch.
4. `CheckoutService` - a web front end (Amelia's Babysitting Service) that switches booking layouts live when you change the flag, sends real booking events, and includes an AI assistant with a kill switch button.
5. The LaunchDarkly UI - experiment results and a guarded rollout that automatically rolls back a bad release.

The storyline: **targeting** decides who gets it, **experiments** prove it's better, **guarded rollouts** prove it's safe, and **kill switches** undo it instantly.

## Prerequisites

- JDK 11+ and Maven
- A LaunchDarkly account (trial works; all accounts include a limited trial of guarded rollouts)
- Your **server-side SDK key** (Project settings > Environments). Not the client-side ID or mobile key.
- Optional: `ANTHROPIC_API_KEY` for live AI calls

## Setup in the LaunchDarkly UI

### 1. Context kind
The code sends a multi-context with kinds `user` and `organization`. LaunchDarkly usually creates the `organization` kind automatically the first time it sees it. Run `FeatureFlagDemo` once, then check **Contexts** to confirm Amelia's Babysitting Service shows up as an organization.

### 2. Feature flag with account-level targeting
1. Create a **boolean** flag `new-checkout-flow`.
2. Add a rule: context kind **organization**, attribute `tier`, `is one of`, `enterprise`, serve `true`.
3. Default rule: `false`. Turn targeting On and save.

Result: Amelia (admin) and Harper (buyer) both get the new flow because their **account** is enterprise. Liam's startup doesn't. That's account-level targeting.

### 3. Metrics
Create three metrics (Metrics > Create), all with **randomization unit = user**:

| Metric | Type | Event key | Success |
|---|---|---|---|
| Checkout conversion | Custom conversion (binary) | `checkout-completed` | Higher is better |
| Checkout revenue | Custom numeric | `checkout-revenue` | Higher is better |
| Checkout errors | Custom conversion (binary) | `checkout-error` | Lower is better |

### 4. Experiment (is it better?)
1. On `new-checkout-flow`, create an experiment on the **default rule** (enterprise accounts already have the feature via the rule above, so you're experimenting on everyone else).
2. Primary metric: **Checkout revenue**. Secondary: **Checkout conversion**. Split 50/50 between `false` and `true`.
3. Start it, then run the simulator for 20+ minutes:
   ```bash
   mvn -q compile exec:java -Dexec.mainClass=com.example.ldemo.TrafficSimulator -Dexec.args="--minutes=30"
   ```
The simulated new flow converts better (32% vs 25%) and has a higher average order, so the experiment should find a real winner.

### 5. Guarded rollout (is it safe?)
Use a **second** flag so it doesn't collide with the experiment. Frame it as a risky backend change.
1. Create a **boolean** flag `new-payment-service`. Turn targeting On.
2. On the default rule, from the **Serve** menu, choose **Guarded rollout**. Serve `true`.
3. Metrics to monitor: **Checkout errors**, with **Automatic rollback** checked. Use the shortest stages the UI allows.
4. Start it, then run the simulator with the bad release:
   ```bash
   mvn -q compile exec:java -Dexec.mainClass=com.example.ldemo.TrafficSimulator -Dexec.args="--flag=new-payment-service --bad --minutes=30"
   ```
With `--bad`, the new variation errors at 15% vs 2%. LaunchDarkly should detect the regression and roll back. In the simulator output, watch the NEW share climb with each stage and then drop to 0% after rollback.

### 6. AI config (extra credit)
1. Create a **boolean** flag `ai-assistant-enabled`. Serve `true` when targeting is On, and make sure the **off variation is `false`**. This is the AI kill switch.
2. Create a config in **completion mode** with key `support-assistant`. (The docs now call these AgentControl configs; the UI may say AI Configs.)
3. Variation "concise": a small, fast model (e.g. Claude Haiku). System message: `You are a support assistant for {{product}}. Be concise. Address the user as {{ldctx.name}}.`
4. Variation "detailed": a stronger model (e.g. Claude Sonnet), step-by-step answers, larger max tokens.
5. Targeting: `plan` is one of `enterprise` serves "detailed"; default serves "concise". Turn targeting on.

### 7. Kill switch button on the web page (optional)
The page's "Turn off AI assistant" button changes the flag through LaunchDarkly's REST API. That needs an **API access token**, which is different from your SDK key. The SDK key can only read flags.
1. In LaunchDarkly, go to Account settings > Authorization > Access tokens and create a token. Use the narrowest role you can (a custom role that only allows updating this one flag's targeting is ideal).
2. Put it in `.env` as `LD_API_TOKEN`, along with `LD_PROJECT_KEY` and `LD_ENV_KEY`. The environment key must be the environment your SDK key belongs to.
3. **Delete the token after the interview.**

Without a token the button is disabled and says so. The page still works, and you can flip the flag in the dashboard instead.

## Run with the scripts (easiest)

1. Copy `.env.example` to `.env` and paste in your keys. The scripts load it automatically.
2. Run `./run.sh <command>`.

| Command | What it does |
|---|---|
| `check` | Verifies Java, Maven, and your keys |
| `build` | Compiles the project |
| `web` | Web front end at http://localhost:8080 |
| `flags` | Feature flag demo |
| `experiment` | Traffic for the experiment (30 min default) |
| `guarded` | Bad-release traffic for the guarded rollout |
| `ai` | AI Config demo with kill switch |
| `demo` | Interview run: flags, then AI |

Change the duration with `./run.sh experiment 45`.

## Run manually

```bash
export LD_SDK_KEY=sdk-your-key-here
export ANTHROPIC_API_KEY=sk-ant-your-key     # optional: live AI calls

mvn -q compile exec:java                                                         # flag demo
mvn -q compile exec:java -Dexec.mainClass=com.example.ldemo.TrafficSimulator     # traffic
mvn -q compile exec:java -Dexec.mainClass=com.example.ldemo.AiConfigDemo         # AI demo
```

Never commit your keys. They come from the environment on purpose.

**First thing to do:** build once and fix any version drift. Both SDK versions in `pom.xml` are properties; set them to the latest on Maven Central.

## Web front end

```bash
./run.sh web        # then open http://localhost:8080
```

It uses the same `new-checkout-flow` flag and the same three shoppers as `FeatureFlagDemo`, so your existing targeting rule works with no changes.

- **Shopping as** switches between Amelia and Harper (Amelia's Babysitting Service, enterprise) and Liam (Parkside Parents Co-op, free).
- **The inspector** at the top shows the deployed version, the flag state, which checkout this shopper sees, the context sent to LaunchDarkly, and why the flag resolved that way. "Show API response" reveals the raw JSON.
- **Legacy checkout** is a three-step flow (shipping, payment, review). **New checkout** puts everything on one page.
- **Live flips.** The page checks the server every 2 seconds. Change the flag in the dashboard and the layout swaps with a banner, with no refresh and no redeploy. Turn it off and it rolls back the same way.
- **Place order** sends `checkout-completed` and `checkout-revenue` to LaunchDarkly, so real clicks feed your experiment and guarded rollout.
- **AI assistant.** "Ask about your order" answers questions using the model and prompt LaunchDarkly serves to that shopper. Amelia (enterprise) and Liam (free) get different models. The reply shows the model, the tokens, and the latency.
- **Kill switch.** "Turn off AI assistant" sets `ai-assistant-enabled` off through the REST API. Within about 2 seconds the assistant disappears for every shopper, a banner announces it, and shoppers see a fallback message. "Turn AI assistant back on" reverses it. The inspector shows the flag's state too.
- **The SDK key never reaches the browser.** The Java server evaluates flags and returns only the results. That's a good security point for an enterprise buyer.
- The port defaults to 8080. Set `PORT` in `.env` to change it.
- The server only accepts connections from your own machine (`127.0.0.1`). Set `HOST` if you ever need otherwise.

**Quick demo flow:** open the page as Liam (legacy checkout), switch to Amelia (new checkout), then change the targeting rule in the dashboard and watch Amelia's page flip live. Ask the assistant a question, then click **Turn off AI assistant** and watch it disappear live. Turn it back on to reset for the next run.

**Notes**
- The page sends the shopper's plan and role to the server so targeting works in a demo. A real service would look those up server-side instead of trusting the browser.
- The fonts load from Google Fonts. If you're offline, the page falls back to your system fonts and still works.
- Card details on the page are fixed demo data. No real payment information is collected.
- The assistant gives a **simulated reply** unless `ANTHROPIC_API_KEY` is set. The page labels simulated replies, and you should say so too.
- Every POST from the page carries a custom header that the server requires. That stops other websites from making your browser call this local app, which matters because the kill switch button can change a real flag.

## IMPORTANT: run the experiment and guarded rollout BEFORE the interview

Both need real traffic and time. Experiments need enough samples to reach a result, and guarded rollouts step through stages and need a minimum number of contexts per stage. Don't try to do them live.

- Run the experiment the day before, so results are ready to show.
- Start the guarded rollout 30-60 minutes before the interview, so you can show either the rollback that already happened or one in progress.
- Screenshot both as a backup in case the UI is slow during the call.

## Demo script (about 12 minutes)

1. **Frame it (30 sec).** "Deploying code and releasing a feature are different things. LaunchDarkly separates them, so shipping is low-risk and reversible."
2. **Account-level targeting.** Run `FeatureFlagDemo`. Amelia and Harper both get the new flow because Amelia's Babysitting Service is an enterprise account, not because of who they are individually. "Enterprise customers want their whole org to change at once, not random users inside it." Point at the **reason** column: it names the rule that matched.
3. **Live change and kill switch.** Toggle the flag while the app runs. The listener prints the change immediately, with no restart and no redeploy.
4. **Experiment.** Switch to the experiment results. "We didn't guess the new flow was better. We measured revenue and conversion per variation, and here's the result."
5. **Guarded rollout.** Show `new-payment-service`. "This one's a riskier backend change, so it rolled out in stages while LaunchDarkly watched the error rate. When errors regressed, it rolled itself back. Nobody got paged at 2am."
6. **Web page.** Run `./run.sh web` and open the page. Switch between Amelia and Liam. Change the flag in the dashboard and watch the layout flip live.
7. **AI configs.** Ask the assistant a question as Amelia, then as Liam. Different models and prompts from the same code, with token counts and latency. (The console version is `./run.sh ai`.)
8. **AI kill switch (closing line).** Click **Turn off AI assistant**. Within seconds it's gone for every shopper, with no redeploy. "If the model starts giving bad answers, one click turns it off everywhere. That's how you ship AI fast without betting the business on it."

## Talk track: tie it to your background

- Harness is deployment automation, LaunchDarkly is release control. The pipeline ships the code, flags decide who sees it.
- Canary at the deploy layer, guarded rollout at the feature layer. Two safety nets, each with automatic rollback.
- Faster AI-generated code means more change hitting production, which makes guarded, reversible release more valuable.
- Stakeholder angles: CTO (risk and control), CFO (experiments prove ROI before full rollout), compliance (audit log, approvals), engineers (no release-night stress).

## Caveats to know before you present

- **Guarded rollouts** are an Enterprise + Guardian add-on feature. Your trial includes a limited number, so don't burn them on test runs you don't need.
- **Simulated traffic.** Say plainly that the experiment and rollout data comes from a traffic generator. The mechanics are real, the shoppers aren't.
- **Context usage.** Each simulated user counts as a context in your account. The default pool is 3,000. Lower `--users` if your trial has a tight limit.
- **The Java AI SDK is pre-1.0.** If it misbehaves live, say so and show the config in the UI instead. The model name and messages are read by reflection, so a renamed getter won't break the build.
- **The kill switch button uses an API token that can change flags.** Keep it in `.env`, never commit it, give it the narrowest role you can, and delete it after the interview.
- **Judges and online evaluations.** The Java AI SDK doesn't run judges automatically. Don't claim otherwise.
- **AI model names** in the AI Config are sent to Anthropic as-is. Test them before the interview.
- **Fails closed.** Flags default to `false`, so if LaunchDarkly is unreachable, new features and the AI assistant turn off rather than running unchecked.
