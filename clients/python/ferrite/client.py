"""
Ferrite Python Client Implementation.
Provides both HTTP REST and native binary TCP socket communication.
"""

import json
import socket
import struct
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Dict, List, Optional, Union


class Point:
    """Represents a single telemetry measurement point."""

    def __init__(self, metric_name: str):
        if not metric_name:
            raise ValueError("Metric name cannot be empty")
        self.metric_name = metric_name
        self.tags: Dict[str, str] = {}
        self.fields: Dict[str, Union[float, int, bool, str]] = {}
        self.timestamp_ns: Optional[int] = None

    def tag(self, key: str, value: str) -> "Point":
        """Adds a dimension label."""
        self.tags[key] = str(value)
        return self

    def field(self, key: str, value: Union[float, int, bool, str]) -> "Point":
        """Adds a field value."""
        self.fields[key] = value
        return self

    def time(self, timestamp_ms: int) -> "Point":
        """Sets point timestamp in epoch milliseconds."""
        self.timestamp_ns = int(timestamp_ms * 1_000_000)
        return self

    def time_ns(self, timestamp_ns: int) -> "Point":
        """Sets point timestamp in epoch nanoseconds."""
        self.timestamp_ns = int(timestamp_ns)
        return self

    def to_line_protocol(self) -> str:
        """Serializes point into InfluxDB line protocol format."""
        if not self.fields:
            raise ValueError("Point requires at least one field")

        buf = self.metric_name
        if self.tags:
            tag_pairs = [f"{k}={v}" for k, v in sorted(self.tags.items())]
            buf += "," + ",".join(tag_pairs)

        field_pairs = []
        for k, v in sorted(self.fields.items()):
            if isinstance(v, bool):
                field_pairs.append(f"{k}={'t' if v else 'f'}")
            elif isinstance(v, int):
                field_pairs.append(f"{k}={v}i")
            elif isinstance(v, float):
                field_pairs.append(f"{k}={v}")
            else:
                field_pairs.append(f'{k}="{v}"')
        buf += " " + ",".join(field_pairs)

        if self.timestamp_ns is not None:
            buf += f" {self.timestamp_ns}"
        return buf


class FerriteClient:
    """Synchronous and batching client for Ferrite server."""

    def __init__(self, host: str = "127.0.0.1", http_port: int = 8086, timeout_sec: float = 10.0):
        self.host = host
        self.http_port = http_port
        self.base_url = f"http://{host}:{http_port}"
        self.timeout = timeout_sec

    def __enter__(self) -> "FerriteClient":
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()

    def write_point(self, point: Point) -> None:
        """Writes a single point."""
        self.write_line(point.to_line_protocol())

    def write_points(self, points: List[Point]) -> None:
        """Writes a batch of points."""
        payload = "\n".join(p.to_line_protocol() for p in points) + "\n"
        self.write_line(payload)

    def write_line(self, line_protocol: str) -> None:
        """Posts raw line protocol text to the /write endpoint."""
        url = f"{self.base_url}/write"
        data = line_protocol.encode("utf-8")
        req = urllib.request.Request(url, data=data, headers={"Content-Type": "text/plain"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                if resp.status not in (200, 204):
                    raise RuntimeError(f"Write failed with HTTP {resp.status}")
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"Write failed: {e.read().decode('utf-8')}") from e

    def query(self, metric: str, from_ms: int = 0, to_ms: Optional[int] = None) -> Dict:
        """Queries raw metric points within a time range."""
        if to_ms is None:
            to_ms = int(time.time() * 1000)
        params = urllib.parse.urlencode({"metric": metric, "from": from_ms, "to": to_ms})
        url = f"{self.base_url}/query?{params}"
        req = urllib.request.Request(url, headers={"Accept": "application/json"}, method="GET")
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body)

    def query_windowed(self, metric: str, window: str, aggregate: str = "avg") -> Dict:
        """Queries windowed aggregate rollups."""
        params = urllib.parse.urlencode({"metric": metric, "window": window, "agg": aggregate})
        url = f"{self.base_url}/query?{params}"
        req = urllib.request.Request(url, headers={"Accept": "application/json"}, method="GET")
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body)

    def health(self) -> Dict:
        """Checks node health and storage stats."""
        url = f"{self.base_url}/health"
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def metrics(self) -> str:
        """Fetches Prometheus exposition metrics."""
        url = f"{self.base_url}/metrics"
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            return resp.read().decode("utf-8")

    def close(self) -> None:
        """Closes any persistent resources."""
        pass
