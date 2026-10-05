"""Mock sitter catalog and the three booking tools.

LaunchDarkly stores the tool definitions. This module runs them. A variation that
does not include create_booking cannot reach book(), because the caller only
invokes tools the resolved variation named.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta

SITTERS = (
    {
        "id": "maya",
        "name": "Maya Chen",
        "hourly_rate": 28,
        "extra_child": 6,
        "evenings": True,
        "weekends": True,
    },
    {
        "id": "jordan",
        "name": "Jordan Blake",
        "hourly_rate": 24,
        "extra_child": 5,
        "evenings": True,
        "weekends": True,
    },
    {
        "id": "sam",
        "name": "Sam Ortiz",
        "hourly_rate": 20,
        "extra_child": 4,
        "evenings": False,
        "weekends": False,
    },
)

_BOOKINGS: dict[str, dict] = {}


def reset_bookings() -> None:
    _BOOKINGS.clear()


def _sitter(sitter_id: str) -> dict | None:
    return next((s for s in SITTERS if s["id"] == sitter_id), None)


def _parse_date(value: str) -> date:
    return datetime.strptime(value, "%Y-%m-%d").date()


def _is_evening(start_time: str) -> bool:
    hour = int(start_time.split(":", 1)[0])
    return 17 <= hour <= 21


def next_saturday(today: date | None = None) -> str:
    today = today or date.today()
    days = (5 - today.weekday()) % 7
    if days == 0:
        days = 7
    return (today + timedelta(days=days)).isoformat()


def find_available_sitters(date: str, start_time: str, hours: float) -> dict:
    """Return sitters who can cover this window. Does not book anyone."""
    when = _parse_date(date)
    weekend = when.weekday() >= 5
    evening = _is_evening(start_time)
    matches = []
    for sitter in SITTERS:
        if weekend and not sitter["weekends"]:
            continue
        if evening and not sitter["evenings"]:
            continue
        if not weekend and not evening and (sitter["weekends"] or sitter["evenings"]):
            continue
        matches.append(
            {
                "id": sitter["id"],
                "name": sitter["name"],
                "hourly_rate": sitter["hourly_rate"],
            }
        )
    return {
        "date": date,
        "start_time": start_time,
        "hours": hours,
        "sitters": matches,
    }


def quote_price(sitter_id: str, hours: float, children: int) -> dict:
    """Quote a price. Does not create a booking."""
    sitter = _sitter(sitter_id)
    if sitter is None:
        return {"error": f"Unknown sitter {sitter_id}"}
    if children < 1:
        return {"error": "children must be at least 1"}
    if hours <= 0:
        return {"error": "hours must be positive"}
    price = sitter["hourly_rate"] * hours + sitter["extra_child"] * (children - 1)
    return {
        "sitter_id": sitter_id,
        "sitter_name": sitter["name"],
        "hours": hours,
        "children": children,
        "price": round(price, 2),
        "currency": "USD",
    }


def create_booking(sitter_id: str, date: str, start_time: str, hours: float) -> dict:
    """Book a sitter. Call only after the parent confirms."""
    sitter = _sitter(sitter_id)
    if sitter is None:
        return {"error": f"Unknown sitter {sitter_id}"}
    booking_id = f"bk-{len(_BOOKINGS) + 1}"
    booking = {
        "booking_id": booking_id,
        "sitter_id": sitter_id,
        "sitter_name": sitter["name"],
        "date": date,
        "start_time": start_time,
        "hours": hours,
        "status": "booked",
    }
    _BOOKINGS[booking_id] = booking
    return booking


def payload(args, kwargs) -> dict:
    """Accept either a single dict or keyword arguments from the SDK."""
    data = {}
    if args and isinstance(args[0], dict):
        data.update(args[0])
    data.update(kwargs)
    return data


def find_available_sitters_tool(*args, **kwargs) -> dict:
    data = payload(args, kwargs)
    return find_available_sitters(
        date=str(data["date"]),
        start_time=str(data["start_time"]),
        hours=float(data["hours"]),
    )


def quote_price_tool(*args, **kwargs) -> dict:
    data = payload(args, kwargs)
    return quote_price(
        sitter_id=str(data["sitter_id"]),
        hours=float(data["hours"]),
        children=int(data["children"]),
    )


def create_booking_tool(*args, **kwargs) -> dict:
    data = payload(args, kwargs)
    return create_booking(
        sitter_id=str(data["sitter_id"]),
        date=str(data["date"]),
        start_time=str(data["start_time"]),
        hours=float(data["hours"]),
    )


TOOL_HANDLERS = {
    "find_available_sitters": find_available_sitters_tool,
    "quote_price": quote_price_tool,
    "create_booking": create_booking_tool,
}
