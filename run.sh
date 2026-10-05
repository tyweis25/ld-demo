#!/usr/bin/env bash
# Demo runner.  Usage: ./run.sh <command>
set -euo pipefail
cd "$(dirname "$0")"

# Load keys from .env if present (copy .env.example to .env and fill it in)
if [[ -f .env ]]; then set -a; source .env; set +a; fi

MAIN=com.example.ldemo

need_key() {
  if [[ -z "${LD_SDK_KEY:-}" ]]; then
    echo "LD_SDK_KEY is not set. Add it to .env or export it first." >&2; exit 1
  fi
}

run() {
  local args=(-q compile exec:java "-Dexec.mainClass=$MAIN.$1")
  [[ -n "${2:-}" ]] && args+=("-Dexec.args=$2")
  mvn "${args[@]}"
}

case "${1:-help}" in
  build)       mvn -q clean compile && echo "Build OK" ;;
  check)
    need_key
    command -v java >/dev/null && java -version 2>&1 | head -1 || echo "Java not found"
    command -v mvn  >/dev/null && mvn -v | head -1 || echo "Maven not found"
    echo "LD_SDK_KEY:        set"
    [[ -n "${ANTHROPIC_API_KEY:-}" ]] && echo "ANTHROPIC_API_KEY: set (live AI)" || echo "ANTHROPIC_API_KEY: not set (simulated AI)"
    [[ -n "${LD_FLAG_TRIGGER_URL:-}" ]] && echo "LD_FLAG_TRIGGER_URL: set (./run.sh remediate)" || echo "LD_FLAG_TRIGGER_URL: not set"
    ;;
  web)
    need_key
    py="$(command -v python3)"
    if [[ -x booking/.venv/bin/python ]]; then py="$(pwd)/booking/.venv/bin/python"; fi
    booking_port="${BOOKING_PORT:-8081}"
    export BOOKING_HELPER_URL="http://127.0.0.1:${booking_port}"
    echo "Starting the booking agent sidecar on ${BOOKING_HELPER_URL}"
    (cd booking && "$py" booking_helper.py --serve --port "$booking_port") &
    booking_pid=$!
    cleanup() { kill "$booking_pid" 2>/dev/null || true; }
    trap cleanup EXIT INT TERM
    echo "Starting Amelia's Babysitting Service. Open http://localhost:${PORT:-8080} in your browser. Ctrl+C to stop."
    run CheckoutService
    ;;
  flags)       need_key; run FeatureFlagDemo ;;
  experiment)  need_key; run TrafficSimulator "--minutes=${2:-30} --users=${3:-5000}" ;;
  guarded)     need_key; run TrafficSimulator "--flag=new-payment-service --bad --minutes=${2:-30}" ;;
  remediate)
    # Remediate skin: POST the LaunchDarkly generic "turn off" trigger URL for new-booking-ui.
    # Create the trigger on flag new-booking-ui, copy the secret URL into .env as LD_FLAG_TRIGGER_URL.
    # Never commit the URL. Checkout flow (new-checkout-flow) is unchanged.
    if [[ -z "${LD_FLAG_TRIGGER_URL:-}" ]]; then
      echo "LD_FLAG_TRIGGER_URL is not set." >&2
      echo "In LaunchDarkly: open flag new-booking-ui → environment configuration →" >&2
      echo "Add trigger → Generic → Turn flag off → copy the URL into .env as LD_FLAG_TRIGGER_URL." >&2
      exit 1
    fi
    echo "Posting generic turn-off trigger for new-booking-ui..."
    # Do not echo the URL (it is a secret).
    code="$(curl -sS -o /tmp/ld-remediate-body.txt -w "%{http_code}" -X POST "$LD_FLAG_TRIGGER_URL" || true)"
    if [[ "$code" =~ ^2 ]]; then
      echo "OK (HTTP $code). new-booking-ui targeting should now be Off (classic chrome)."
    else
      echo "Trigger request failed (HTTP ${code:-curl-error}). Check the URL is still valid in the LD UI." >&2
      exit 1
    fi
    ;;
  ai)          need_key; run AiConfigDemo ;;
  booking)
    need_key
    py="$(command -v python3)"
    if [[ -x booking/.venv/bin/python ]]; then py="$(pwd)/booking/.venv/bin/python"; fi
    (cd booking && "$py" booking_helper.py "${2:-amelia}" ${3:+"$3"})
    ;;
  demo)
    need_key
    echo "== 1/2 Feature flags =="; run FeatureFlagDemo
    echo; echo "== 2/2 AI Configs ==";  run AiConfigDemo
    ;;
  *)
    cat <<USAGE
Usage: ./run.sh <command>

  check              Verify Java, Maven, and keys
  build              Compile the project
  web                Web front end at http://localhost:8080
  flags              Feature flag demo (multi-context targeting, live change)
  experiment [min]   Traffic for the experiment on new-checkout-flow (default 30 min, 5000 users)
  guarded [min]      Bad-release traffic for the guarded rollout on new-payment-service
  remediate          POST LD_FLAG_TRIGGER_URL to turn off new-booking-ui (classic chrome)
  ai                 AI Config demo with kill switch
  booking [parent]   Agent booking helper (amelia or liam). Add --confirm to book.
  demo               Interview run: flags, then AI
USAGE
    ;;
esac
