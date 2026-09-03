"""HA Android Timer Bridge — finished tablet timers, pushed over a webhook."""

from __future__ import annotations

import logging
from typing import Any

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST, CONF_PORT, Platform
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.typing import ConfigType

from . import api
from .const import (
    BUS_EVENT_PREFIX,
    CONF_PAIRING_CODE,
    CONF_WEBHOOK_ID,
    EVENT_TYPES,
    KIND_TIMER,
    DOMAIN,
    SIGNAL_TIMER,
)

_LOGGER = logging.getLogger(__name__)

PLATFORMS: list[Platform] = [Platform.EVENT, Platform.SENSOR]


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Register the webhook this tablet posts to and bring up its entities."""
    webhook_id = entry.data[CONF_WEBHOOK_ID]

    webhook.async_register(
        hass,
        DOMAIN,
        entry.title,
        webhook_id,
        _make_handler(entry),
        local_only=True,
        allowed_methods=["POST"],
    )

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Tear the webhook and entities back down."""
    webhook.async_unregister(hass, entry.data[CONF_WEBHOOK_ID])
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Tell the tablet to forget this instance when the device is deleted."""
    await api.async_unpair(
        async_get_clientsession(hass),
        entry.data[CONF_HOST],
        entry.data[CONF_PORT],
        entry.data[CONF_PAIRING_CODE],
    )


def _make_handler(entry: ConfigEntry):
    """Build the webhook handler bound to one tablet."""

    async def handle(hass: HomeAssistant, webhook_id: str, request) -> None:
        try:
            payload: dict[str, Any] = await request.json()
        except ValueError:
            _LOGGER.warning("Ignoring non-JSON payload from %s", entry.title)
            return

        if not isinstance(payload, dict):
            _LOGGER.warning("Ignoring unexpected payload from %s", entry.title)
            return

        _LOGGER.debug("timer event from %s: %s", entry.title, payload)

        # Entities first, then the bus event, so an automation triggering on the event
        # sees entity state that already reflects it.
        async_dispatcher_send(hass, f"{SIGNAL_TIMER}_{entry.entry_id}", payload)

        kind = payload.get("kind", KIND_TIMER)
        event_type = EVENT_TYPES.get(kind, EVENT_TYPES[KIND_TIMER])
        hass.bus.async_fire(
            f"{BUS_EVENT_PREFIX}_{event_type}", {"entry_id": entry.entry_id, **payload}
        )

    return handle


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Nothing to do — this integration is config-entry only."""
    return True
