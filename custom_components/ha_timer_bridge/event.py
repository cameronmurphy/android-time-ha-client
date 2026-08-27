"""Event entities: one for finished timers, one for alarms going off."""

from __future__ import annotations

from typing import Any

from homeassistant.components.event import EventEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import (
    CONF_DEVICE_ID,
    EVENT_TYPE_ALARM_FIRED,
    EVENT_TYPE_TIMER_FINISHED,
    KIND_ALARM,
    KIND_TIMER,
)
from .entity import TimerBridgeEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the timer and alarm event entities."""
    async_add_entities(
        [
            ClockEvent(entry, KIND_TIMER, "Timer", EVENT_TYPE_TIMER_FINISHED, "mdi:timer-outline"),
            ClockEvent(entry, KIND_ALARM, "Alarm", EVENT_TYPE_ALARM_FIRED, "mdi:alarm"),
        ]
    )


class ClockEvent(TimerBridgeEntity, EventEntity):
    """Fires when a timer finishes, or when an alarm goes off."""

    def __init__(
        self,
        entry: ConfigEntry,
        kind: str,
        name: str,
        event_type: str,
        icon: str,
    ) -> None:
        super().__init__(entry, kind)
        self._attr_name = name
        self._attr_icon = icon
        self._attr_event_types = [event_type]
        self._event_type = event_type
        self._attr_unique_id = f"{entry.data[CONF_DEVICE_ID]}_{kind}"

    @callback
    def _handle_event(self, payload: dict[str, Any]) -> None:
        self._trigger_event(
            self._event_type,
            {
                "timer_name": payload.get("timer_name"),
                "duration": payload.get("duration"),
                "device": payload.get("device"),
                "is_test": payload.get("is_test", False),
                "match_reason": payload.get("match_reason"),
                "kind_reason": payload.get("kind_reason"),
                "raw": payload.get("raw"),
            },
        )
        self.async_write_ha_state()
