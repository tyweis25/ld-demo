# Amelia's Babysitting Service

A sample booking app that uses LaunchDarkly to change **who sees what** without a redeploy. Targeting decides who gets a feature, experiments measure whether it performs better, guarded rollouts watch for errors, and kill switches turn AI features off instantly.

The browser never talks to LaunchDarkly and never sees the SDK key. A local Java server evaluates flags and AI configs, then returns results to the page.

`.agents/skills` contains LaunchDarkly agent skills used with Cursor (flag create and targeting, AI configs, onboarding). They are not required to run the app.

## Lab requirements

Maps to the LaunchDarkly SE Technical Exercise. Java is the server SDK (one of the top 5). Python is used only for AgentControl **agent** mode, which the Java AI SDK cannot run.

| Requirement | Where it is implemented | How to run it |
|---|---|---|
| **Part 1 — Feature flag** | Boolean `new-checkout-flow` wraps the booking form: **classic multi-step UI** (flag off) vs **Harbor Dusk express UI** (flag on). Recreate this flag in your project if it does not exist. | `./run.sh web` → Book A Sitter. Toggle the flag in LaunchDarkly. |
| **Part 1 — Instant release / rollback** | The Java SDK streams flag changes (`addFlagChangeListener`) and pushes them to the page over **Server-Sent Events** (`GET /api/events`). A slow poll remains as fallback. The **entire look-and-feel** swaps **without a reload**. | Flip `new-checkout-flow` in the dashboard while the page is open. |
| **Part 1 — Remediate** | LaunchDarkly **generic flag trigger** (turn off) on `new-checkout-flow`. URL stays in `.env` as `LD_FLAG_TRIGGER_URL`. `./run.sh remediate` or the discreet **Remediate** control on the new UI (`POST /api/remediate`) POSTs it. SSE flips the page back to classic. | Create trigger in LD → copy URL to `.env` → `./run.sh remediate` or click Remediate on the Harbor Dusk UI. |
| **Part 2 — Feature flag** | Same booking component and flag as Part 1. | Switch shoppers on the page. |
| **Part 2 — Context attributes** | Multi-context `user` + `organization`: `key`, `name`, `role`, `plan` on user; `tier` on org (`CheckoutService.contextFrom`). | Booking As: Amelia / Harper / Liam. |
| **Part 2 — Individual targeting** | Target user keys `user-harper` and `user-liam` to serve `true`. Individual targets are evaluated **before** rules, so Liam can get the new flow even when a free-tier rule would deny it. Inspector reason: `TARGET_MATCH`. | Select Harper or Liam. |
| **Part 2 — Rule-based targeting** | Rule: **organization** `tier` is one of `enterprise` → `true`. Inspector reason: `RULE_MATCH`. | Select Amelia. |
| **Extra credit — Experimentation** | Same flag. Metrics `checkout-completed` / `checkout-revenue`. Experiment on the default rule. `TrafficSimulator` generates traffic. | `./run.sh experiment` then open the experiment in LaunchDarkly. |
| **Extra credit — AI Configs** | Completion config `support-assistant` (prompts and models by `user.plan`). Optional judge `babysitting-service-reply-accuracy`. Agent config `booking-helper` (Python sidecar). | Ask / Book A Sitter on the page, or `./run.sh ai` / `./run.sh booking` |
| **Extra credit — Integrations** | (1) LaunchDarkly hosted MCP + agent skills (`.cursor/mcp.json`, `.agents/skills`) for Cursor. (2) Flag trigger remediate (`LD_FLAG_TRIGGER_URL` + `./run.sh remediate`). | Connect MCP in Cursor; run `./run.sh remediate` after setting the trigger URL. |

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
| `LD_API_TOKEN` | No | Makes the AI kill-switch button work (REST, not the SDK) |
| `LD_PROJECT_KEY` | With token | Usually `default` |
| `LD_ENV_KEY` | With token | Must match the SDK key’s environment (`test`, `production`, …) |
| `LD_FLAG_TRIGGER_URL` | No | Secret URL for the generic **turn off** trigger on `new-checkout-flow`. Used by `./run.sh remediate`. Never commit |
| `LD_API_BASE` | No | EU accounts: `https://app.eu.launchdarkly.com` |
| `PORT` | No | Java HTTP port, default `8080` |
| `HOST` | No | Bind address, default `127.0.0.1` |
| `FLAG_KEY` | No | Checkout flag, default `new-checkout-flow` |
| `APP_VERSION` | No | Shown in the inspector, default `1.0.0` |
| `BOOKING_PORT` | No | Sidecar port, default `8081` |
| `BOOKING_HELPER_URL` | Set by `run.sh web` | `http://127.0.0.1:${BOOKING_PORT}` |

`LD_API_TOKEN` and `LD_FLAG_TRIGGER_URL` are secrets. The SDK key can only read. Keep them in `.env` and never commit them.

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

Part 2 uses the same three shoppers to show both targeting styles: Amelia matches the **rule** (`RULE_MATCH`), Harper and Liam are **individual targets** (`TARGET_MATCH`). Individual targeting is evaluated before rules, so Liam (`free` org) still gets the new one-page flow when listed as an individual target even if a free-tier rule would otherwise serve classic. The inspector prints that reason.

## LaunchDarkly objects the code expects

Recreate these in your trial project if they are not already there. The code keys are listed below; names in the UI can differ.

### Feature flags

**`new-checkout-flow`** (boolean, used by the page and `./run.sh flags`)

1. **Individual targeting:** add user keys `user-harper` and `user-liam` to serve `true`. Individual targets beat rules.
2. **Rule-based targeting:** context kind **organization**, attribute `tier`, is one of `enterprise` → `true`.
3. Optional free-tier rule → `false` (so Liam without an individual target would get classic).
4. Default rule: `false` (or your experiment split).
5. Targeting On.

Amelia (enterprise) gets the one-page checkout via the rule. Harper and Liam get it because they are named as individual targets.

**Flag trigger (Part 1 remediate)**

1. Open `new-checkout-flow` → environment overflow → **Configuration in environment**.
2. **Add trigger** → **Generic** → action **Turn flag off**.
3. Copy the secret URL into `.env` as `LD_FLAG_TRIGGER_URL` (never commit it).
4. Run `./run.sh remediate` to POST the URL and turn targeting Off.

The AI assistant button still uses REST (`LD_API_TOKEN`) against `ai-assistant-enabled`. That is separate from the checkout flag trigger.

**`new-payment-service`** (boolean, optional guarded-rollout beyond the lab)

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
| `experiment [min]` | Traffic for `new-checkout-flow` (default 30 min, 5000 users) |
| `guarded [min]` | Bad-release traffic for `new-payment-service` |
| `remediate` | POST `LD_FLAG_TRIGGER_URL` to turn off `new-checkout-flow` |
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
- **Classic UI** (`new-checkout-flow` off): Address → Payment → Review (lavender / Fraunces look).
- **Harbor Dusk UI** (`new-checkout-flow` on): a visually distinct express booking sheet (teal dusk band, Literata/Sora, amber accent). Remediate / flag-off returns to classic.
- The page opens an **SSE** connection to `/api/events`. When the SDK sees a flag change, the UI refreshes immediately. A 10s poll remains as fallback.
- **Ask** runs `support-assistant`, then the accuracy judge. Amelia’s grounded answers should score high; Liam’s concise answers often invent rates and score near 0%.
- **Turn Off AI Assistant** PATCHes `ai-assistant-enabled` through the REST API when `LD_API_TOKEN` is set. Otherwise the button is disabled and you flip the flag in the dashboard.
- **Remediate To Classic UI** (new experience only) POSTs `/api/remediate`, which curls `LD_FLAG_TRIGGER_URL` on the server. Same effect as `./run.sh remediate`.
- **Find Sitters / Confirm Booking** go through Java to the Python sidecar.

### HTTP API

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Cached `index.html` |
| GET | `/api/config` | Kill-switch / remediate capability, judge key, live model, sidecar present |
| GET | `/api/checkout` | Flag + assistant state + reason for this shopper |
| GET | `/api/events` | SSE stream of `flag-change` events from the SDK |
| POST | `/api/order` | `checkout-completed` + `checkout-revenue` |
| POST | `/api/assistant` | Kill switch + completion + judge |
| GET | `/api/booking-status` | Proxy to sidecar `GET /status` |
| POST | `/api/booking` | Proxy to sidecar `POST /ask` |
| POST | `/api/killswitch?state=on\|off` | REST toggle of `ai-assistant-enabled` |
| POST | `/api/remediate` | Server-side POST of `LD_FLAG_TRIGGER_URL` (turns `new-checkout-flow` off) |
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

- **Assumptions:** macOS or Linux, JDK 11+, Maven, Python 3, a LaunchDarkly trial (or paid) project with a server-side SDK key. Guarded rollouts need Enterprise + Guardian (trials often include a limited allotment).
- Shoppers and traffic in this sample are simulated. Each simulated user is a context against your MAU. Default experiment pool is 5,000 (`./run.sh experiment`). Lower `--users` if the trial is tight.
- The **Java AI SDK is pre-1.0**. Model name and messages are read by reflection so a renamed getter does not break the build.
- **Judges do not auto-run** from the UI attachment. The app calls `judgeConfig`.
- **AI model names** in the config go to Anthropic as-is.
- **Fails closed.** Flags default `false`. If LaunchDarkly is down, new checkout and the assistant stay off.
- **Secrets** (`LD_SDK_KEY`, `LD_API_TOKEN`, `LD_FLAG_TRIGGER_URL`, `ANTHROPIC_API_KEY`) stay in `.env` and are never committed.
- Card numbers on the page are fake. No real payments.
- Shopper `plan` / `tier` are sent from the browser for this sample. A production service would look those up server-side.
