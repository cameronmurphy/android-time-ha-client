"""Talking to the tablet's small pairing server."""

from __future__ import annotations

import logging
from typing import Any

import aiohttp

_LOGGER = logging.getLogger(__name__)

TIMEOUT = aiohttp.ClientTimeout(total=10)


class BridgeError(Exception):
    """The tablet could not be reached or refused the request."""


class InvalidPairingCode(BridgeError):
    """The tablet rejected the pairing code."""


def base_url(host: str, port: int) -> str:
    """Return the tablet's base URL."""
    return f"http://{host}:{port}"


async def async_get_info(
    session: aiohttp.ClientSession, host: str, port: int
) -> dict[str, Any]:
    """Ask the tablet what it is."""
    try:
        async with session.get(f"{base_url(host, port)}/info", timeout=TIMEOUT) as response:
            if response.status != 200:
                raise BridgeError(f"tablet returned HTTP {response.status}")
            return await response.json(content_type=None)
    except aiohttp.ClientError as err:
        raise BridgeError(f"cannot reach tablet at {host}:{port}") from err
    except TimeoutError as err:
        raise BridgeError(f"tablet at {host}:{port} timed out") from err


async def async_pair(
    session: aiohttp.ClientSession,
    host: str,
    port: int,
    code: str,
    webhook_url: str,
    instance_name: str,
) -> dict[str, Any]:
    """Hand the tablet the webhook it should post finished timers to."""
    payload = {
        "code": code,
        "webhook_url": webhook_url,
        "instance_name": instance_name,
    }
    try:
        async with session.post(
            f"{base_url(host, port)}/pair", json=payload, timeout=TIMEOUT
        ) as response:
            if response.status == 403:
                raise InvalidPairingCode("tablet rejected the pairing code")
            if response.status != 200:
                raise BridgeError(f"tablet returned HTTP {response.status}")
            return await response.json(content_type=None)
    except aiohttp.ClientError as err:
        raise BridgeError(f"cannot reach tablet at {host}:{port}") from err
    except TimeoutError as err:
        raise BridgeError(f"tablet at {host}:{port} timed out") from err


async def async_unpair(
    session: aiohttp.ClientSession, host: str, port: int, code: str
) -> None:
    """Best-effort: tell the tablet to forget us. Never raises."""
    try:
        async with session.post(
            f"{base_url(host, port)}/unpair", json={"code": code}, timeout=TIMEOUT
        ) as response:
            if response.status != 200:
                _LOGGER.debug("unpair returned HTTP %s", response.status)
    except (aiohttp.ClientError, TimeoutError) as err:
        _LOGGER.debug("could not unpair from %s:%s: %s", host, port, err)
