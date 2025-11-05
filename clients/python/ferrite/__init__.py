"""
Ferrite Python Client SDK
Zero-dependency, high-throughput client for Ferrite time-series clusters.
"""

from .client import FerriteClient, Point

__all__ = ["FerriteClient", "Point"]
__version__ = "1.0.0"
