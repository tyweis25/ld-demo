import unittest
from datetime import date

from booking_helper import apply_tools, as_jsonable, context_for, simulate
from sitters import (
    create_booking,
    find_available_sitters,
    next_saturday,
    quote_price,
    reset_bookings,
)


class SitterToolsTest(unittest.TestCase):
    def setUp(self):
        reset_bookings()

    def test_saturday_evening_excludes_weekday_sitter(self):
        found = find_available_sitters("2026-10-10", "18:00", 4)
        ids = [s["id"] for s in found["sitters"]]
        self.assertEqual(ids, ["maya", "jordan"])

    def test_quote_adds_extra_child(self):
        quote = quote_price("maya", 4, 2)
        self.assertEqual(quote["price"], 28 * 4 + 6)

    def test_booking_returns_an_id(self):
        booked = create_booking("jordan", "2026-10-10", "18:00", 4)
        self.assertEqual(booked["status"], "booked")
        self.assertEqual(booked["booking_id"], "bk-1")

    def test_next_saturday_skips_a_saturday(self):
        self.assertEqual(next_saturday(date(2026, 10, 3)), "2026-10-10")

    def test_assistant_variation_cannot_book(self):
        text = simulate(
            "I need a sitter Saturday evening for two kids, about 4 hours.",
            {"find_available_sitters", "quote_price"},
            confirm=True,
        )
        self.assertIn("Maya Chen", text)
        self.assertIn("cannot book", text)
        self.assertNotIn("bk-", text)

    def test_full_agent_books_only_after_confirm(self):
        allowed = {"find_available_sitters", "quote_price", "create_booking"}
        message = "I need a sitter Saturday evening for two kids, about 4 hours."
        asking = simulate(message, allowed, confirm=False)
        self.assertIn("Reply yes to book", asking)
        self.assertNotIn("bk-", asking)
        booked = simulate(message, allowed, confirm=True)
        self.assertIn("bk-1", booked)

    def test_confirm_applies_booking_tools_without_a_model(self):
        applied = apply_tools(
            "I need a sitter Saturday evening for two kids, about 4 hours.",
            ["find_available_sitters", "quote_price", "create_booking"],
            confirm=True,
        )
        self.assertTrue(applied["booked"])
        self.assertIn("bk-1", applied["reply"])
        locked = apply_tools(
            "I need a sitter Saturday evening for two kids, about 4 hours.",
            ["find_available_sitters", "quote_price"],
            confirm=True,
        )
        self.assertFalse(locked["booked"])
        self.assertIn("cannot book", locked["reply"])

    def test_context_for_known_and_unknown_parents(self):
        amelia = context_for("amelia")
        self.assertEqual(amelia["key"], "user-amelia")
        self.assertEqual(amelia["plan"], "enterprise")
        guest = context_for("nina", "Nina Cole", "pro")
        self.assertEqual(guest["key"], "user-nina")
        self.assertEqual(guest["plan"], "pro")

    def test_as_jsonable_flattens_usage_objects(self):
        class UsageDict(dict):
            pass

        usage = UsageDict(input_tokens=12, output_tokens=34)
        self.assertEqual(as_jsonable(usage), {"input_tokens": 12, "output_tokens": 34})


if __name__ == "__main__":
    unittest.main()
