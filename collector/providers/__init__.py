"""Provider 包。"""
from .base import DataProvider
from .akshare_provider import AkshareProvider
from .baostock_provider import BaostockProvider

__all__ = ["DataProvider", "AkshareProvider", "BaostockProvider"]
