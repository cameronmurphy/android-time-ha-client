"""Constants for the HA Android Timer Bridge integration."""

DOMAIN = "ha_android_timer_bridge"

CONF_DEVICE_ID = "device_id"
CONF_DEVICE_NAME = "device_name"
CONF_PAIRING_CODE = "pairing_code"
CONF_WEBHOOK_ID = "webhook_id"

DEFAULT_PORT = 8127

# The tablet reports which of these it saw. Alarms and timers both ring through the same
# notification channel on Google Clock, so the app decides and we keep them apart here.
KIND_TIMER = "timer"
KIND_ALARM = "alarm"

EVENT_TYPE_TIMER_FINISHED = "timer_finished"
EVENT_TYPE_ALARM_FIRED = "alarm_fired"

EVENT_TYPES = {
    KIND_TIMER: EVENT_TYPE_TIMER_FINISHED,
    KIND_ALARM: EVENT_TYPE_ALARM_FIRED,
}

# Fired on the Home Assistant bus as well as updating entities, so existing automations can
# trigger on it directly: ha_android_timer_bridge_timer_finished / ha_android_timer_bridge_alarm_fired.
BUS_EVENT_PREFIX = DOMAIN

SIGNAL_TIMER = f"{DOMAIN}_event"
