# Amelia's Babysitting Service

A sample booking app that uses LaunchDarkly to change **who sees what** without a redeploy. Targeting decides who gets a feature, experiments measure whether it performs better, guarded rollouts watch for errors, and kill switches turn AI features off instantly.

The browser never talks to LaunchDarkly and never sees the SDK key. A local Java server evaluates flags and AI configs, then returns results to the page.

`.agents/skills` contains LaunchDarkly agent skills used with Cursor (flag create and targeting, AI configs, onboarding). They are not required to run the app.

## Lab requirements

| Requirement | Where it is implemented | How to run it |
|---|---|---|
| **Part 1 — Release** | Boolean flag `new-checkout-flow` switches classic vs one-page booking in `CheckoutService` and `index.html` | `./run.sh web`, then Book A Sitter |
| **Part 1 — Remediate** | Kill switch `ai-assistant-enabled` (REST toggle from the page). Guarded rollout on `new-payment-service` with metric `checkout-error` | Turn Off AI Assistant on the page, or `./run.sh guarded` |
| **Part 2 — Target** | Multi-context `user` + `organization`. Enterprise orgs (`org-ld`) get the new checkout; Liam’s free co-op does not | `./run.sh web` and switch Amelia / Harper / Liam, or `./run.sh flags` |
| **Extra credit — Experiment** | Metrics `checkout-completed` and `checkout-revenue`; `TrafficSimulator` on `new-checkout-flow` | `./run.sh experiment` then the experiment in LaunchDarkly |
| **Extra credit — AI Configs** | Completion config `support-assistant`, judge `babysitting-service-reply-accuracy`, agent config `booking-helper` (Python sidecar) | Ask and Book A Sitter on the page, or `./run.sh ai` / `./run.sh booking` |
| **Extra credit — Integrations** | LaunchDarkly hosted MCP (`.cursor/mcp.json`) and agent skills under `.agents/skills` | Open the project in Cursor with the LaunchDarkly MCP connected |

The web app is the main surface: `./run.sh web` then open [http://localhost:8080](http://localhost:8080).

## Architecture

```
Browser (index.html)
        |  GET/POST localhost:8080   header X-LD-Demo on POSTs
        v
Java CheckoutService          Python booking_helper.py
  LD Java server SDK            LD Python server SDK
  LD Java AI SDK 0.3.0          LD Python AI SDK 0.2.4
        |                              |
        +------ LaunchDarkly ----------+
        |  flags, AI configs, events   |
        v
  Anthropic (optional, if ANTHROPIC_API_KEY is set)
```

- **Java** evaluates feature flags, the support assistant (`completion` mode), and the accuracy judge. It cannot run AgentControl **agent** mode.
- **Python** evaluates `booking-helper` in agent mode, runs tools (`find_available_sitters`, `quote_price`, `create_booking`), and returns JSON. Java proxies `/api/booking-status` and `/api/booking` to `http://127.0.0.1:8081`.
- `./run.sh web` starts both processes and stops the sidecar when you Ctrl+C.

HTML is loaded once at Java startup (`CheckoutService.loadPage()`). After you edit `src/main/resources/web/index.html`, restart `./run.sh web`.

## Prerequisites

- JDK 11+ and Maven
- Python 3 (for the booking sidecar). A venv at `booking/.venv` is used automatically if present
- A LaunchDarkly account and a **server-side SDK key** (Project settings → Environments). Not the client-side ID
- Optional: `ANTHROPIC_API_KEY` for live model calls. Without it, the assistant and booking agent still evaluate configs and return simulated replies

## Setup

1. Copy `.env.example` to `.env` and fill in keys. Never commit `.env`.
2. Confirm the toolchain:

   ```bash
   ./run.sh check
   ./run.sh build
   ```

3. Create the LaunchDarkly objects below (or reuse ones already in project `default`, environment `test`).
4. Start the page:

   ```bash
   ./run.sh web
   ```

   Open [http://localhost:8080](http://localhost:8080). Logs print in that terminal.

### Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `LD_SDK_KEY` | Yes | Server-side SDK. Reads flags and AI configs |
| `ANTHROPIC_API_KEY` | No | Live Claude calls. Simulated replies if unset |
| `LD_API_TOKEN` | No | Makes the kill-switch button work (REST, not the SDK) |
| `LD_PROJECT_KEY` | With token | Usually `default` |
| `LD_ENV_KEY` | With token | Must match the SDK key’s environment (`test`, `production`, …) |
| `LD_API_BASE` | No | EU accounts: `https://app.eu.launchdarkly.com` |
| `PORT` | No | Java HTTP port, default `8080` |
| `HOST` | No | Bind address, default `127.0.0.1` |
| `FLAG_KEY` | No | Checkout flag, default `new-checkout-flow` |
| `APP_VERSION` | No | Shown in the inspector, default `1.0.0` |
| `BOOKING_PORT` | No | Sidecar port, default `8081` |
| `BOOKING_HELPER_URL` | Set by `run.sh web` | `http://127.0.0.1:${BOOKING_PORT}` |

`LD_API_TOKEN` is not an SDK key. The SDK key can only read. The token can change targeting. Use the narrowest role you can.

## How the SDKs are pulled in

Maven downloads the Java SDKs from Maven Central when you compile. Versions live in `pom.xml`:

```xml
<ld.server.version>7.17.2</ld.server.version>
<ld.ai.version>0.3.0</ld.ai.version>
```

| Artifact | Role |
|---|---|
| `com.launchdarkly:launchdarkly-java-server-sdk` | Flags, multi-contexts, evaluation reasons, `track()` events |
| `com.launchdarkly:launchdarkly-java-server-sdk-ai` | `completionConfig`, `judgeConfig`, AI metrics |

`CheckoutService` builds one `LDClient` (waits up to 5 seconds) and wraps it with `LDAIClientImpl`.

The booking sidecar installs Python packages from `booking/requirements.txt`:

```
launchdarkly-server-sdk>=9.0.0
launchdarkly-ai-server==0.2.4
launchdarkly-ai-claude-agents==0.2.4
```

Create a venv once if you want it isolated:

```bash
python3 -m venv booking/.venv
booking/.venv/bin/pip install -r booking/requirements.txt
```

`./run.sh web` prefers `booking/.venv/bin/python` when that file exists.

## Shoppers (multi-context)

Every evaluation is a **user + organization** multi-context. Account-level rules target `organization.tier`. AI and booking rules target `user.plan`. The page sends `plan` equal to the org `tier` so those stay in sync.

| Shopper | User key | Role | Org | Tier / plan |
|---|---|---|---|---|
| Amelia Smith | `user-amelia` | admin | `org-ld` Amelia's Babysitting Service | enterprise |
| Harper Reed | `user-harper` | parent | same org as Amelia | enterprise |
| Liam Carter | `user-liam` | parent | `org-bright` Parkside Parents Co-op | free |

Harper shows that checkout targeting is **account**-level: she is not an admin, but she still gets the enterprise flow.

## LaunchDarkly objects the code expects

### Feature flags

**`new-checkout-flow`** (boolean, used by the page and `./run.sh flags`)

1. Rule: context kind **organization**, attribute `tier`, is one of `enterprise` → `true`.
2. Default rule: `false`.
3. Targeting On.

Amelia and Harper get the one-page checkout. Liam gets the three-step classic flow.

**`new-payment-service`** (boolean, guarded-rollout demo)

Separate from the checkout experiment so the two stories do not collide. Targeting On. Default rule: guarded rollout serving `true`, monitoring **Checkout errors** with automatic rollback.

`./run.sh guarded` evaluates this flag and injects a 15% error rate on the new variation.

**`ai-assistant-enabled`** (boolean kill switch)

Serve `true` when targeting is On. Off variation must be `false`. If LaunchDarkly is unreachable, the SDK default is `false` (fails closed: no AI).

### Metrics

Create these with **randomization unit = user**:

| Metric | Type | Event key | Success |
|---|---|---|---|
| Checkout conversion | Custom conversion (binary) | `checkout-completed` | Higher is better |
| Checkout revenue | Custom numeric | `checkout-revenue` | Higher is better |
| Checkout errors | Custom conversion (binary) | `checkout-error` | Lower is better |

`Book Session` on the page tracks conversion and revenue. The traffic simulator tracks all three.

### Experiment

On `new-checkout-flow`, attach an experiment to the **default rule** (enterprise already has the feature via the targeting rule). Primary: Checkout revenue. Secondary: Checkout conversion. 50/50 `false` / `true`. Then:

```bash
./run.sh experiment 30
```

The simulator’s new flow converts better (about 32% vs 25%) and has a higher average order.

### Guarded rollout

On `new-payment-service`, guarded rollout serving `true`, metric Checkout errors, automatic rollback, shortest stages the UI allows. Then:

```bash
./run.sh guarded 30
```

With `--bad`, new-variation errors are 15% vs 2% on the old variation.

### Completion config `support-assistant`

AgentControl / AI Config in **completion** mode. Typical variations:

| Variation | Who gets it | Intent |
|---|---|---|
| `grounded` | `user.plan` is `enterprise` | Official rates and policies in the system prompt (Sonnet) |
| `concise` | default | Short, less grounded (Haiku) |
| `detailed` | optional | Longer answers |

Turn targeting **On in the same environment as the SDK key** (usually `test`).

### Judge `babysitting-service-reply-accuracy`

Judge-mode config. The Java AI SDK **does not** auto-run judges attached in the UI. After each Ask, `AiAssistant` calls `judgeConfig` and `Judge.evaluate`.

The judge and the grounded assistant need the **same official facts**. If the judge does not know those facts, it scores grounded answers as hallucinations.

The Accuracy card is the **latest Ask only**, not an average. Switching shoppers clears the last answer.

Targeting for the judge must be On in **test**, not only Production.

### Agent config `booking-helper`

Agent mode (tools). Java cannot evaluate this; the Python sidecar does.

| Variation | Tools | Who |
|---|---|---|
| `assistant` | find sitters, quote price | default (Liam) |
| `full-booking-agent` | those plus `create_booking` | enterprise parents (Amelia, Harper) |

Tools are definitions only. The app runs them (`booking/sitters.py`: Maya, Jordan, Sam).

**Find Sitters** is a live Claude turn when `ANTHROPIC_API_KEY` is set. **Confirm Booking** is deterministic: it applies tools locally (Maya next Saturday 18:00, or Jordan) so a new stateless POST does not lose the quote. Each `/api/booking` request is a new turn with no chat history.

The sidecar stays up for the life of `./run.sh web`. It does not book on a timer. The page polls `/api/booking-status` every 2 seconds; Find / Confirm POST `/api/booking`.

## Commands

```bash
./run.sh <command>
```

| Command | What it does |
|---|---|
| `check` | Java, Maven, and which keys are set |
| `build` | `mvn clean compile` |
| `web` | Sidecar on 8081 + page on 8080 |
| `flags` | Console multi-context demo |
| `experiment [min]` | Traffic for `new-checkout-flow` (default 30) |
| `guarded [min]` | Bad-release traffic for `new-payment-service` |
| `ai` | Console AI Config + kill switch |
| `booking [parent]` | One-shot booking helper (`amelia` or `liam`). Add `--confirm` to book |
| `demo` | `flags` then `ai` |

Equivalent Maven:

```bash
export LD_SDK_KEY=sdk-your-key-here
mvn -q compile exec:java
mvn -q compile exec:java -Dexec.mainClass=com.example.ldemo.TrafficSimulator -Dexec.args="--minutes=30"
mvn -q compile exec:java -Dexec.mainClass=com.example.ldemo.TrafficSimulator -Dexec.args="--flag=new-payment-service --bad --minutes=30"
mvn -q compile exec:java -Dexec.mainClass=com.example.ldemo.AiConfigDemo
```

## Web page

The inspector at the top shows checkout variation, assistant on/off, booking-agent variation, latest accuracy score, and the evaluation reason.

- **Booking As** switches Amelia / Harper / Liam. The layout, assistant variation, and booking tools change with that context.
- **Classic booking** (flag off): Address → Payment → Review.
- **One-page booking** (flag on): all three on one page.
- The page polls `/api/checkout` every 2 seconds. Flip a flag in the dashboard and the UI swaps without a refresh.
- **Ask** runs `support-assistant`, then the accuracy judge. Amelia’s grounded answers should score high; Liam’s concise answers often invent rates and score near 0%.
- **Turn Off AI Assistant** PATCHes `ai-assistant-enabled` through the REST API when `LD_API_TOKEN` is set. Otherwise the button is disabled and you flip the flag in the dashboard.
- **Find Sitters / Confirm Booking** go through Java to the Python sidecar.

### HTTP API

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Cached `index.html` |
| GET | `/api/config` | Kill-switch capability, judge key, live model, sidecar present |
| GET | `/api/checkout` | Flag + assistant state + reason for this shopper |
| POST | `/api/order` | `checkout-completed` + `checkout-revenue` |
| POST | `/api/assistant` | Kill switch + completion + judge |
| GET | `/api/booking-status` | Proxy to sidecar `GET /status` |
| POST | `/api/booking` | Proxy to sidecar `POST /ask` |
| POST | `/api/killswitch?state=on\|off` | REST toggle of `ai-assistant-enabled` |
| GET | `/health`, `/ready` | Liveness / SDK initialized |

POSTs require header `X-LD-Demo: 1` so a random website cannot drive the kill switch from your browser.

## Project layout

```
pom.xml                          Java SDKs and Maven exec
run.sh                           Demo runner (loads .env)
.env.example                     Keys to copy
.agents/skills/                  LaunchDarkly agent skills for Cursor
.cursor/mcp.json                 LaunchDarkly hosted MCP
src/main/java/com/example/ldemo/
  CheckoutService.java           Web server and LD client
  AiAssistant.java               Kill switch, completion, judge
  FeatureFlagDemo.java           Console targeting demo
  TrafficSimulator.java          Experiment / guarded traffic
  AiConfigDemo.java              Console AI demo
src/main/resources/web/index.html
src/test/java/com/example/ldemo/ Unit tests and ITs (ITs skip without LD_SDK_KEY)
booking/
  booking_helper.py              Agent sidecar and CLI
  sitters.py                     Tools: find, quote, book
  test_sitters.py
  requirements.txt
```

## Tests

```bash
mvn -q test
python3 booking/test_sitters.py
```

JUnit covers context shape, checkout query parsing, kill-switch / booking HTML markers, judge JSON, and sidecar proxying. `*IT.java` talks to a live SDK and skips if `LD_SDK_KEY` is unset. The sidecar bind test needs a real local socket (not a restricted sandbox).

## Notes

- **Guarded rollouts** are an Enterprise + Guardian add-on. Trials include a limited number.
- Shoppers and traffic in this sample are simulated. Each simulated user is a context against your MAU. Default pool is 3,000. Lower `--users` if the trial is tight.
- The **Java AI SDK is pre-1.0**. Model name and messages are read by reflection so a renamed getter does not break the build.
- **Judges do not auto-run** from the UI attachment. The app calls `judgeConfig`.
- **AI model names** in the config go to Anthropic as-is.
- **Fails closed.** Flags default `false`. If LaunchDarkly is down, new checkout and the assistant stay off.
- **Kill-switch token** can change a real flag. Keep it in `.env` and never commit it.
- Card numbers on the page are fake. No real payments.
- Shopper `plan` / `tier` are sent from the browser for this sample. A production service would look those up server-side.
