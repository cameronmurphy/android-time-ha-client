"""Shared base for the entities belonging to one tablet."""

from __future__ import annotations

from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity

from .const import CONF_DEVICE_ID, CONF_DEVICE_NAME, DOMAIN, KIND_TIMER, SIGNAL_TIMER


class TimerBridgeEntity(Entity):
    """An entity fed by the events one tablet pushes over its webhook."""

    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, entry: ConfigEntry, kind: str) -> None:
        self._entry = entry
        self._kind = kind
        device_id = entry.data[CONF_DEVICE_ID]
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, device_id)},
            name=entry.data.get(CONF_DEVICE_NAME) or entry.title,
            manufacturer="HA Android Timer Bridge",
            model="Android tablet",
        )

    async def async_added_to_hass(self) -> None:
        """Listen for events forwarded by the webhook handler."""
        await super().async_added_to_hass()
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass,
                f"{SIGNAL_TIMER}_{self._entry.entry_id}",
                self._dispatch,
            )
        )

    @callback
    def _dispatch(self, payload: dict[str, Any]) -> None:
        """Ignore anything that is not this entity's kind.

        The decorator matters: without it Home Assistant treats this as a blocking
        callback and runs it in an executor thread, where async_write_ha_state is illegal.
        """
        if payload.get("kind", KIND_TIMER) != self._kind:
            return
        self._handle_event(payload)

    def _handle_event(self, payload: dict[str, Any]) -> None:
        """Handle one alarm or timer. Overridden by each platform."""
        raise NotImplementedError
