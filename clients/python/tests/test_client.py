"""
Unit tests for the Ferrite Python SDK.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from ferrite.client import Point


class TestPoint(unittest.TestCase):

    def test_line_protocol_formatting(self):
        p = Point("cpu_usage") \
            .tag("host", "srv-01") \
            .tag("region", "us-east") \
            .field("value", 82.5) \
            .field("cores", 16) \
            .time(1700000000000)

        lp = p.to_line_protocol()
        self.assertTrue(lp.startswith("cpu_usage,host=srv-01,region=us-east"))
        self.assertIn("cores=16i", lp)
        self.assertIn("value=82.5", lp)
        self.assertTrue(lp.endswith("1700000000000000000"))

    def test_validation(self):
        with self.assertRaises(ValueError):
            Point("")

        with self.assertRaises(ValueError):
            Point("empty").to_line_protocol()


if __name__ == "__main__":
    unittest.main()
