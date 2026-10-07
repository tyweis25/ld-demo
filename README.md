# Amelia's Babysitting Service

A sample booking app that uses LaunchDarkly to change **who sees what** without a redeploy. Targeting decides who gets a feature, experiments measure whether it performs better, guarded rollouts watch for errors, and kill switches turn AI features off instantly.

The browser never talks to LaunchDarkly and never sees the SDK key. A local Java server evaluates flags and AI configs, then returns results to the page.

`.agents/skills` contains LaunchDarkly agent skills used with Cursor (flag create and targeting, AI configs, onboarding). They are not required to run the app.

## Lab requirements

Maps to the LaunchDarkly SE Technical Exercise. Java is the server SDK (one of the top 5). Python is used only for AgentControl **agent** mode, which the Java AI SDK cannot run.

| Requirement | Where it is implemented | How to run it |
|---|---|---|
| **Part 1 — Feature flag (checkout flow)** | Boolean `new-checkout-flow`: multi-step (false) vs one-page (true) **inside** whichever chrome is active. | Toggle `new-checkout-flow` or switch Amelia/Liam. |
| **Part 1 — Booking UI skin** | Boolean `new-booking-ui`: Harbor Dusk chrome (true) vs classic Amelia lavender (false). Fallthrough true for everyone when On. | Toggle `new-booking-ui` in LaunchDarkly. |
| **Part 1 — Instant release / rollback** | SDK `addFlagChangeListener` → **SSE** (`GET /api/events`). Skin and form both live-update without reload. | Flip either flag while the page is open. |
| **Part 1 — Remediate** | Generic flag trigger on **`new-booking-ui`** (not checkout). `LD_FLAG_TRIGGER_URL` + `./run.sh remediate` / in-UI Remediate → classic chrome. Checkout targeting unchanged. | Create trigger on `new-booking-ui` → `.env` → remediate. |
| **Guarded rollout** | Boolean `new-checkout-service` (not the page). Default rule: Guardian serving `true`, metric **Checkout errors**, auto rollback. `TrafficSimulator --bad` injects ~15% errors on `true`. | Start the rollout in Test, then `./run.sh guarded`. Watch **Monitoring → Releases**. |
| **Part 2 — Feature flag** | Same booking component and flag as Part 1. | Switch shoppers on the page. |
| **Part 2 — Context attributes** | Multi-context `user` + `organization`: `key`, `name`, `role`, `plan` on user; `tier` on org (`CheckoutService.contextFrom`). | Booking As: Amelia / Harper / Liam. |
| **Part 2 — Individual targeting** | Target user key `user-harper` to serve `true`. Inspector reason: `TARGET_MATCH` (individual targets are evaluated **before** rules). For a clearer “beats the free-tier rule” demo, temporarily add `user-liam` → `true`, then remove it again. | Select Harper (reason `TARGET_MATCH`). |
| **Part 2 — Rule-based targeting** | On `new-checkout-flow`: org `tier` enterprise → one-page; free → multi-step. Skin is separate (`new-booking-ui`). | Amelia = one-page; Liam = multi-step (both can be Harbor Dusk). |
| **Extra credit — Experimentation** | Same flag. Metrics `checkout-completed` / `checkout-revenue`. Experiment on the default rule. `TrafficSimulator` generates traffic. | `./run.sh experiment` then open the experiment in LaunchDarkly. |
| **Extra credit — AI Configs** | Completion config `support-assistant` (prompts and models by `user.plan`). Optional judge `babysitting-service-reply-accuracy`. Agent config `booking-helper` (Python sidecar). | Ask / Book A Sitter on the page, or `./run.sh ai` / `./run.sh booking` |
| **Extra credit — AI Config experiment** | Experiment **Support Assistant Prompt/Model A/B** (`support-assistant-prompt-model`) on the AI Config default rule: concise Haiku vs detailed Sonnet. Metrics `assistant-helpful` (primary), `assistant-reply`, `assistant-latency-ms`. `AiAssistant` tracks on every Ask. | `./run.sh ai-experiment` (free-tier traffic) or Ask as Liam on the page |
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
| `LD_FLAG_TRIGGER_URL` | No | Secret URL for the generic **turn off** trigger on **`new-booking-ui`**. Used by `./run.sh remediate`. Never commit |
| `UI_FLAG_KEY` | No | Skin flag, default `new-booking-ui` |
| `FLAG_KEY` | No | Checkout flag, default `new-checkout-flow` |
| `LD_API_BASE` | No | EU accounts: `https://app.eu.launchdarkly.com` |
| `PORT` | No | Java HTTP port, default `8080` |
| `HOST` | No | Bind address, default `127.0.0.1` |
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

Part 2 uses the same three shoppers to show both targeting styles: Amelia matches the **enterprise rule** (`RULE_MATCH` → one-page), Liam matches the **free rule** (`RULE_MATCH` → multi-step), and Harper is an **individual target** (`TARGET_MATCH` → one-page). Individual targeting is evaluated before rules — for a live “override free tier” beat, temporarily add `user-liam` → `true`, then remove it so free stays multi-step.

## LaunchDarkly objects the code expects

Recreate these in your trial project if they are not already there. The code keys are listed below; names in the UI can differ.

### Feature flags

**`new-checkout-flow`** (boolean, used by the page and `./run.sh flags`)

1. **Individual targeting:** add user key `user-harper` to serve `true` (shows `TARGET_MATCH` in the inspector). Optional demo: temporarily add `user-liam` → `true` to show individual targets beating the free-tier rule, then remove Liam again.
2. **Rule-based targeting:** context kind **organization**, attribute `tier`, is one of `enterprise` → `true` (one-page booking).
3. **Rule-based targeting:** context kind **organization**, attribute `tier`, is one of `free` → `false` (multi-step booking).
4. Default / fallthrough: `false` (multi-step).
5. Targeting On.

Amelia (enterprise) gets one-page checkout via the rule. Liam (`org-bright` / free) gets multi-step via the free rule. Harper gets one-page as an individual target (`TARGET_MATCH`). All of this stays inside the classic Amelia lavender UI.

**Flag trigger (Part 1 remediate — skin)**

1. Open **`new-booking-ui`** → environment overflow → **Configuration in environment**.
2. **Add trigger** → **Generic** → action **Turn flag off**.
3. Copy the secret URL into `.env` as `LD_FLAG_TRIGGER_URL` (never commit it).
4. Run `./run.sh remediate` (or the in-UI Remediate control) to turn the skin Off → classic chrome.

`new-checkout-flow` targeting is unchanged by remediate. The AI assistant button still uses REST (`LD_API_TOKEN`) against `ai-assistant-enabled`.

**`new-checkout-service`** (boolean, guarded rollout — not used by the web page)

Keeps Guardian off `new-checkout-flow` so the experiment and the unsafe-release story do not share a flag. The page never evaluates this key. Only `./run.sh guarded` does.

In **Test** (leave Production Off):

1. Targeting **On**.
2. Default rule: **Guarded rollout**, target variation **`true`** (control is `false`).
3. Metric **Checkout errors** (`checkout-error`, lower is better), **Auto rollback** on.
4. Target by **user**.
5. Custom stages: 5% / 10% / 25% / 50% for 5 minutes each (20 minutes, then 100%).

If Target variation is stuck on `false`, `true` is locked as the original. Serve a single variation **`false`**, save, then create the guarded rollout again.

`./run.sh guarded` runs `TrafficSimulator --flag=new-checkout-service --bad` (~15% errors on `true` vs ~2% on `false`). After a regression, Guardian sets the default rule back to **`false`**. Results: the flag’s **Monitoring → Releases** tab.

**`ai-assistant-enabled`** (boolean kill switch)

Serve `true` when targeting is On. Off variation must be `false`. If LaunchDarkly is unreachable, the SDK default is `false` (fails closed: no AI).

### Metrics

Create these with **randomization unit = user**:

| Metric | Type | Event key | Success |
|---|---|---|---|
| Checkout conversion | Custom conversion (binary) | `checkout-completed` | Higher is better |
| Checkout revenue | Custom numeric | `checkout-revenue` | Higher is better |
| Checkout errors | Custom conversion (binary) | `checkout-error` | Lower is better |
| Assistant helpful | Custom conversion (binary) | `assistant-helpful` | Higher is better |
| Assistant reply | Custom conversion (binary) | `assistant-reply` | Higher is better |
| Assistant latency (ms) | Custom numeric | `assistant-latency-ms` | Lower is better |

`Book Session` on the page tracks conversion and revenue. The traffic simulator tracks all three checkout metrics. Every successful Ask tracks the three assistant metrics (plus AI SDK tokens / duration / feedback for Monitoring).

### Experiment

On `new-checkout-flow`, attach an experiment to the **default rule** (enterprise already has the feature via the targeting rule). Primary: Checkout revenue. Secondary: Checkout conversion. 50/50 `false` / `true`. Then:

```bash
./run.sh experiment 30
```

The simulator’s new flow converts better (about 32% vs 25%) and has a higher average order.

### Guarded rollout

On **`new-checkout-service`** in Test (see flag setup above). Start the rollout in the LaunchDarkly UI first, then:

```bash
./run.sh guarded 30
```

That is 30 minutes of traffic, enough to cover the 20-minute custom ramp. With `--bad`, new-variation (`true`) errors are about 15% vs 2% on `false`. Guardian should detect **Checkout errors** and roll the default rule back to `false` without turning targeting Off.

Do not use `./run.sh experiment` for this flag; that command still drives `new-checkout-flow`.

### Completion config `support-assistant`

AgentControl / AI Config in **completion** mode. Typical variations:

| Variation | Who gets it | Intent |
|---|---|---|
| `grounded` | `user.plan` is `enterprise` | Official rates and policies in the system prompt (Sonnet) |
| `concise` | fallthrough experiment (50%, control) | Short, less grounded (Haiku) |
| `detailed` | fallthrough experiment (50%) | Longer answers (Sonnet) |

Turn targeting **On in the same environment as the SDK key** (usually `test`).

### AI Config experiment (`support-assistant-prompt-model`)

Experiment **Support Assistant Prompt/Model A/B** on the AI Config **default rule** (fallthrough): 50/50 **concise Haiku** (control) vs **detailed Sonnet**. Enterprise `plan` still matches the grounded rule and is outside the experiment.

| Metric | Role | Event key |
|---|---|---|
| Assistant helpful | Primary | `assistant-helpful` |
| Assistant reply | Secondary | `assistant-reply` |
| Assistant latency (ms) | Secondary | `assistant-latency-ms` |

`AiAssistant.ask` tracks these after every successful reply (web Ask, `./run.sh ai`, `./run.sh ai-experiment`). Live answers use the accuracy judge (≥ 0.6 = helpful); simulated answers treat detailed/grounded as helpful and concise as not, so the arms still separate without Anthropic.

```bash
./run.sh ai-experiment        # 120 free-tier users × 1 Ask (simulated unless ANTHROPIC_API_KEY is set)
./run.sh ai-experiment 60 2   # 60 users × 2 Asks
```

Results: LaunchDarkly → Experiments → **Support Assistant Prompt/Model A/B** (env `test`), or the `support-assistant` Monitoring tab for tokens / duration / feedback.

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
| `guarded [min]` | Bad-release traffic for `new-checkout-service` |
| `remediate` | POST `LD_FLAG_TRIGGER_URL` to turn off `new-booking-ui` (classic chrome) |
| `ai` | Console AI Config + kill switch |
| `ai-experiment [n] [asks]` | Free-tier Ask traffic for `support-assistant` experiment (default 120×1) |
| `booking [parent]` | One-shot booking helper (`amelia` or `liam`). Add `--confirm` to book |
| `demo` | `flags` then `ai` |

Equivalent Maven:

```bash
export LD_SDK_KEY=sdk-your-key-here
mvn -q compile exec:java
mvn -q compile exec:java -Dexec.mainClass=com.example.ldemo.TrafficSimulator -Dexec.args="--minutes=30"
mvn -q compile exec:java -Dexec.mainClass=com.example.ldemo.TrafficSimulator -Dexec.args="--flag=new-checkout-service --bad --minutes=30"
mvn -q compile exec:java -Dexec.mainClass=com.example.ldemo.AiConfigDemo
```

## Web page

The inspector at the top shows checkout variation, assistant on/off, booking-agent variation, latest accuracy score, and the evaluation reason.

- **Booking As** switches Amelia / Harper / Liam. The layout, assistant variation, and booking tools change with that context.
- **Chrome:** `new-booking-ui` on → Harbor Dusk skin; off → classic Amelia lavender.
- **Multi-step booking** (`new-checkout-flow` off): Address → Payment → Review (works in either chrome).
- **One-page booking** (`new-checkout-flow` on): all three on one page (works in either chrome).
- **SSE** (`/api/events`) live-updates both skin and form. A 3s poll remains as fallback.
- **Ask** runs `support-assistant`, then the accuracy judge. Amelia’s grounded answers should score high; Liam’s concise answers often invent rates and score near 0%.
- **Turn Off AI Assistant** PATCHes `ai-assistant-enabled` through the REST API when `LD_API_TOKEN` is set.
- **Remediate To Classic Chrome** (Harbor only) POSTs `/api/remediate` → turns off `new-booking-ui`. Same as `./run.sh remediate`. Does not change checkout flow.
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
| POST | `/api/remediate` | Server-side POST of `LD_FLAG_TRIGGER_URL` (turns `new-booking-ui` off → classic chrome) |
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
  AiAssistant.java               Kill switch, completion, judge, experiment metrics
  FeatureFlagDemo.java           Console targeting demo
  TrafficSimulator.java          Experiment / guarded traffic
  AiConfigDemo.java              Console AI demo
  AiExperimentTraffic.java       Free-tier Ask burst for AI Config experiment
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
