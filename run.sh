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
    ;;
  web)
    need_key
    echo "Starting Amelia's Babysitting Service. Open http://localhost:${PORT:-8080} in your browser. Ctrl+C to stop."
    run CheckoutService
    ;;
  flags)       need_key; run FeatureFlagDemo ;;
  experiment)  need_key; run TrafficSimulator "--minutes=${2:-30}" ;;
  guarded)     need_key; run TrafficSimulator "--flag=new-payment-service --bad --minutes=${2:-30}" ;;
  ai)          need_key; run AiConfigDemo ;;
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
  experiment [min]   Traffic for the experiment on new-checkout-flow (default 30 min)
  guarded [min]      Bad-release traffic for the guarded rollout on new-payment-service
  ai                 AI Config demo with kill switch
  demo               Interview run: flags, then AI
USAGE
    ;;
esac
