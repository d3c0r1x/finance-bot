"""Compatibility entry point for the receipt evaluation CLI."""

import asyncio

from tools.evaluation.receipt_inventory import main


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
