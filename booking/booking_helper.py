#!/usr/bin/env python3
"""Booking helper driven by the LaunchDarkly agent config `booking-helper`.

v1 (assistant) can look up sitters and quote a price.
v2 (full-booking-agent) can also call create_booking, and only after the parent confirms.
LaunchDarkly chooses the variation. This process runs the tools.

Without ANTHROPIC_API_KEY the script still evaluates the config and runs the mock
tools locally, and it labels the reply as simulated. With the key, the Claude
agent handler runs the tool loop and LaunchDarkly records duration and tokens.

CLI:  python booking_helper.py [amelia|liam] [--confirm]
HTTP: python booking_helper.py --serve   (used by ./run.sh web)
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import sys
from datetime import date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from sitters import (
    TOOL_HANDLERS,
    create_booking,
    find_available_sitters,
    next_saturday,
    quote_price,
    reset_bookings,
)

CONFIG_KEY = "booking-helper"
ROOT = Path(__file__).resolve().parents[1]

PARENTS = {
    "amelia": {"kind": "user", "key": "user-amelia", "name": "Amelia Smith", "plan": "enterprise"},
    "harper": {"kind": "user", "key": "user-harper", "name": "Harper Reed", "plan": "enterprise"},
    "liam": {"kind": "user", "key": "user-liam", "name": "Liam Carter", "plan": "free"},
}

SAMPLE = "I need a sitter Saturday evening for two kids, about 4 hours."
_NUMBERS = {"one": 1, "two": 2, "three": 3, "four": 4}


def load_env() -> None:
    env_file = ROOT / ".env"
    if not env_file.is_file():
        return
    for line in env_file.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def context_for(user: str, name: str | None = None, plan: str | None = None) -> dict:
    preset = PARENTS.get(user, {})
    return {
        "kind": "user",
        "key": preset.get("key", f"user-{user}"),
        "name": name or preset.get("name", user),
        "plan": plan or preset.get("plan", "free"),
    }


def parse_request(message: str, today: date | None = None) -> tuple[str, str, float, int]:
    text = message.lower()
    children = 1
    for word, count in _NUMBERS.items():
        if f"{word} kid" in text or f"{word} child" in text:
            children = count
    hours = 4.0
    match = re.search(r"(\d+(?:\.\d+)?)\s*hour", text)
    if match:
        hours = float(match.group(1))
    start = "18:00" if "evening" in text else "10:00"
    when = next_saturday(today) if "saturday" in text else (today or date.today()).isoformat()
    return when, start, hours, children


def simulate(message: str, allowed: set[str], confirm: bool) -> str:
    """Run only the tools the resolved variation attached."""
    if "find_available_sitters" not in allowed:
        return "This variation has no lookup tool, so I can't search for a sitter."

    when, start, hours, children = parse_request(message)
    found = find_available_sitters(when, start, hours)
    sitters = found["sitters"]
    if not sitters:
        return f"Nobody is free on {when} at {start}."

    lines = [f"Available on {when} at {start} for {hours:g} hours:"]
    quotes = []
    if "quote_price" in allowed:
        for sitter in sitters:
            quote = quote_price(sitter["id"], hours, children)
            quotes.append(quote)
            lines.append(f"- {quote['sitter_name']}: ${quote['price']:.0f} for {children} children")
    else:
        for sitter in sitters:
            lines.append(f"- {sitter['name']}")

    if "create_booking" not in allowed:
        lines.append("This plan can find a sitter and quote a price. It cannot book.")
        return "\n".join(lines)

    if not confirm:
        lines.append(f"I can book {quotes[0]['sitter_name']} after you confirm. Reply yes to book.")
        return "\n".join(lines)

    choice = quotes[0]
    booked = create_booking(choice["sitter_id"], when, start, hours)
    lines.append(
        f"Booked {booked['sitter_name']} on {booked['date']} at {booked['start_time']} "
        f"({booked['booking_id']})."
    )
    return "\n".join(lines)


def _attr(obj, name, default=None):
    if isinstance(obj, dict):
        return obj.get(name, default)
    return getattr(obj, name, default)


def variation_tools(info) -> tuple[bool, str, list[str]]:
    enabled = bool(_attr(info, "enabled", False))
    meta = _attr(info, "meta") or {}
    config = _attr(info, "config") or {}
    key = _attr(meta, "variationKey") or _attr(config, "key") or "unknown"
    tools = _attr(config, "tools") or {}
    if isinstance(tools, dict):
        names = [str(name) for name in tools]
    else:
        names = []
        for tool in tools:
            name = _attr(tool, "key") or _attr(tool, "name")
            if name:
                names.append(str(name))
    return enabled, str(key), names


def summary(enabled: bool, variation: str, tools: list[str]) -> dict:
    return {
        "ok": True,
        "enabled": enabled,
        "variation": variation,
        "tools": tools,
        "can_book": "create_booking" in tools,
        "live": bool(os.environ.get("ANTHROPIC_API_KEY")),
    }


async def inspect(context: dict) -> dict:
    from launchdarkly_ai_server import inspect_config

    info = await inspect_config(CONFIG_KEY, context)
    enabled, variation, tools = variation_tools(info)
    out = summary(enabled, variation, tools)
    if not enabled:
        out["ok"] = False
        out["error"] = "LaunchDarkly returned enabled=False for booking-helper."
    return out


async def run_turn(context: dict, message: str, confirm: bool) -> dict:
    from launchdarkly_ai_server import inspect_config

    info = await inspect_config(CONFIG_KEY, context)
    enabled, variation, tools = variation_tools(info)
    out = summary(enabled, variation, tools)
    if not enabled:
        out["ok"] = False
        out["error"] = "LaunchDarkly returned enabled=False for booking-helper."
        out["reply"] = out["error"]
        return out

    live = bool(os.environ.get("ANTHROPIC_API_KEY"))
    # Each POST /ask is a new turn with no chat history. Confirm cannot ask the
    # model to "book the sitter you quoted" — there is no quote in context.
    # Run the same tools the variation attached so Confirm actually books.
    if confirm or not live:
        applied = apply_tools(message, tools, confirm)
        out.update(applied)
        return out

    from launchdarkly_ai_claude_agents import create_claude_agents_handler
    from launchdarkly_ai_server import config

    handlers = {name: TOOL_HANDLERS[name] for name in tools if name in TOOL_HANDLERS}
    result = await config(
        key=CONFIG_KEY,
        handler=[create_claude_agents_handler()],
        tool_handlers=handlers,
    ).invoke(message, context)
    out["reply"] = str(_attr(result, "response") or "")
    usage = as_jsonable(_attr(result, "usage"))
    if usage:
        out["usage"] = usage
    return out


def apply_tools(message: str, tools: list[str], confirm: bool) -> dict:
    reply = simulate(message, set(tools), confirm)
    return {
        "reply": reply,
        "booked": "create_booking" in tools and "bk-" in reply,
    }


def as_jsonable(value):
    """Turn SDK objects (UsageDict, etc.) into plain JSON types."""
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, dict):
        return {str(k): as_jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [as_jsonable(v) for v in value]
    to_dict = getattr(value, "to_dict", None)
    if callable(to_dict):
        return as_jsonable(to_dict())
    items = getattr(value, "items", None)
    if callable(items):
        try:
            return {str(k): as_jsonable(v) for k, v in items()}
        except TypeError:
            pass
    return str(value)


def print_turn(context: dict, result: dict) -> None:
    print(f"Parent: {context['name']} ({context['plan']})")
    print(f"Config: {CONFIG_KEY}  enabled={result.get('enabled')}  variation={result.get('variation')}")
    tools = result.get("tools") or []
    print(f"Tools:  {', '.join(tools) if tools else '(none)'}")
    print()
    if not result.get("enabled"):
        print(result.get("error") or "enabled=False")
        print("Turn targeting on and point the default rule at assistant or full-booking-agent.")
        return
    print("Mode: LIVE (Anthropic)" if result.get("live") else "Mode: SIMULATED (set ANTHROPIC_API_KEY for a live Claude tool loop)")
    print(result.get("reply") or "")
    if result.get("usage"):
        print(f"Usage: {result['usage']}")


class BookingHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        # The GUI polls /status every 2s; skip that so iTerm stays readable.
        if urlparse(self.path).path in {"/status", "/health"}:
            return
        sys.stderr.write("booking-helper: " + (fmt % args) + "\n")

    def _query(self) -> dict[str, str]:
        parsed = urlparse(self.path)
        raw = parse_qs(parsed.query, keep_blank_values=True)
        return {k: (v[0] if v else "") for k, v in raw.items()}

    def _json(self, status: int, body: dict) -> None:
        payload = json.dumps(body, default=str).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/health":
            self._json(200, {"ok": True})
            return
        if path == "/status":
            q = self._query()
            ctx = context_for(q.get("user", "anonymous"), q.get("name"), q.get("plan") or q.get("tier"))
            self._json(200, asyncio.run(inspect(ctx)))
            return
        self._json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        if path != "/ask":
            self._json(404, {"ok": False, "error": "not found"})
            return
        q = self._query()
        ctx = context_for(q.get("user", "anonymous"), q.get("name"), q.get("plan") or q.get("tier"))
        message = (q.get("question") or SAMPLE).strip() or SAMPLE
        confirm = q.get("confirm", "").lower() in {"1", "true", "yes"}
        self._json(200, asyncio.run(run_turn(ctx, message, confirm)))


def serve(host: str, port: int) -> None:
    load_env()
    if not os.environ.get("LD_SDK_KEY"):
        print("LD_SDK_KEY is not set. Add it to .env or export it first.", file=sys.stderr)
        sys.exit(1)
    httpd = ThreadingHTTPServer((host, port), BookingHandler)
    print(f"Booking helper listening on http://{host}:{port}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


async def evaluate(parent_key: str, message: str, confirm: bool) -> int:
    load_env()
    if not os.environ.get("LD_SDK_KEY"):
        print("LD_SDK_KEY is not set. Add it to .env or export it first.", file=sys.stderr)
        return 1

    from launchdarkly_ai_server import shutdown

    context = context_for(parent_key)
    reset_bookings()
    result = await run_turn(context, message, confirm)
    print_turn(context, result)
    await shutdown()
    return 0 if result.get("enabled") else 2


def main() -> None:
    parent = "amelia"
    confirm = False
    message = SAMPLE
    serve_http = False
    host = "127.0.0.1"
    port = int(os.environ.get("BOOKING_PORT", "8081"))
    args = sys.argv[1:]
    while args:
        arg = args.pop(0)
        if arg in PARENTS:
            parent = arg
        elif arg == "--confirm":
            confirm = True
        elif arg == "--serve":
            serve_http = True
        elif arg == "--port" and args:
            port = int(args.pop(0))
        elif arg.startswith("--port="):
            port = int(arg.split("=", 1)[1])
        elif arg == "--message" and args:
            message = args.pop(0)
        else:
            print(f"Unknown argument: {arg}", file=sys.stderr)
            print("Usage: booking_helper.py [amelia|liam] [--confirm] [--serve]", file=sys.stderr)
            sys.exit(1)
    if serve_http:
        serve(host, port)
        return
    try:
        code = asyncio.run(evaluate(parent, message, confirm))
    except KeyboardInterrupt:
        code = 130
    except Exception as exc:
        print(f"Booking helper failed: {exc}", file=sys.stderr)
        code = 1
    sys.exit(code)


if __name__ == "__main__":
    main()
