"""Sensors naming the most recent timer and alarm."""

from __future__ import annotations

from typing import Any

from homeassistant.components.sensor import SensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import CONF_DEVICE_ID, KIND_ALARM, KIND_TIMER
from .entity import TimerBridgeEntity

# Home Assistant rejects states longer than this.
MAX_STATE_LENGTH = 255

UNNAMED = {KIND_TIMER: "Unnamed timer", KIND_ALARM: "Unnamed alarm"}


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the last-timer and last-alarm sensors."""
    async_add_entities(
        [
            LastNameSensor(entry, KIND_TIMER, "Last timer", "mdi:timer-check-outline"),
            LastNameSensor(entry, KIND_ALARM, "Last alarm", "mdi:alarm-check"),
        ]
    )


class LastNameSensor(TimerBridgeEntity, SensorEntity):
    """Name of the timer or alarm that most recently went off."""

    def __init__(self, entry: ConfigEntry, kind: str, name: str, icon: str) -> None:
        super().__init__(entry, kind)
        self._attr_name = name
        self._attr_icon = icon
        self._attr_unique_id = f"{entry.data[CONF_DEVICE_ID]}_last_{kind}"
        self._attr_native_value: str | None = None
        self._attr_extra_state_attributes: dict[str, Any] = {}

    @callback
    def _handle_event(self, payload: dict[str, Any]) -> None:
        name = payload.get("timer_name") or UNNAMED[self._kind]
        self._attr_native_value = str(name)[:MAX_STATE_LENGTH]
        raw = payload.get("raw") or {}
        self._attr_extra_state_attributes = {
            "duration": payload.get("duration"),
            "is_test": payload.get("is_test", False),
            "match_reason": payload.get("match_reason"),
            "kind_reason": payload.get("kind_reason"),
            "raw_view_texts": raw.get("view_texts"),
            "raw_channel_id": raw.get("channel_id"),
        }
        self.async_write_ha_state()
