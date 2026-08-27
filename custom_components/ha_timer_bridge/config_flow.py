"""Config flow: discover a tablet over mDNS and pair with it."""

from __future__ import annotations

import logging
from typing import Any

import voluptuous as vol

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.network import NoURLAvailableError, get_url
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo

from . import api
from .const import (
    CONF_DEVICE_ID,
    CONF_DEVICE_NAME,
    CONF_PAIRING_CODE,
    CONF_WEBHOOK_ID,
    DEFAULT_PORT,
    DOMAIN,
)

_LOGGER = logging.getLogger(__name__)


class HaTimerBridgeConfigFlow(ConfigFlow, domain=DOMAIN):
    """Pair Home Assistant with a tablet running the HA Timer Bridge app."""

    VERSION = 1

    def __init__(self) -> None:
        self._host: str | None = None
        self._port: int = DEFAULT_PORT
        self._device_id: str | None = None
        self._device_name: str | None = None

    async def async_step_zeroconf(
        self, discovery_info: ZeroconfServiceInfo
    ) -> ConfigFlowResult:
        """Handle a tablet advertising itself on the network."""
        properties = {
            key.decode() if isinstance(key, bytes) else key:
            value.decode() if isinstance(value, bytes) else value
            for key, value in (discovery_info.properties or {}).items()
        }

        device_id = properties.get("id")
        if not device_id:
            return self.async_abort(reason="no_device_id")

        await self.async_set_unique_id(device_id)
        self._abort_if_unique_id_configured(
            updates={
                CONF_HOST: str(discovery_info.ip_address),
                CONF_PORT: discovery_info.port or DEFAULT_PORT,
            }
        )

        self._host = str(discovery_info.ip_address)
        self._port = discovery_info.port or DEFAULT_PORT
        self._device_id = device_id
        self._device_name = properties.get("device") or discovery_info.name

        self.context["title_placeholders"] = {"name": self._device_name}
        return await self.async_step_pair()

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Add a tablet by address, for when mDNS does not get through."""
        errors: dict[str, str] = {}

        if user_input is not None:
            host = user_input[CONF_HOST]
            port = user_input[CONF_PORT]
            try:
                info = await api.async_get_info(
                    async_get_clientsession(self.hass), host, port
                )
            except api.BridgeError as err:
                _LOGGER.debug("cannot reach tablet: %s", err)
                errors["base"] = "cannot_connect"
            else:
                device_id = info.get("id")
                if not device_id:
                    errors["base"] = "not_a_bridge"
                else:
                    await self.async_set_unique_id(device_id)
                    self._abort_if_unique_id_configured()
                    self._host = host
                    self._port = port
                    self._device_id = device_id
                    self._device_name = info.get("device") or host
                    return await self.async_step_pair()

        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_HOST): str,
                    vol.Required(CONF_PORT, default=DEFAULT_PORT): int,
                }
            ),
            errors=errors,
        )

    async def async_step_pair(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Ask for the code on the tablet's screen, then hand it a webhook."""
        errors: dict[str, str] = {}
        assert self._host is not None

        if user_input is not None:
            webhook_id = webhook.async_generate_id()
            try:
                webhook_url = self._webhook_url(webhook_id)
            except NoURLAvailableError:
                errors["base"] = "no_internal_url"
            else:
                try:
                    await api.async_pair(
                        async_get_clientsession(self.hass),
                        self._host,
                        self._port,
                        user_input[CONF_PAIRING_CODE],
                        webhook_url,
                        self.hass.config.location_name,
                    )
                except api.InvalidPairingCode:
                    errors["base"] = "invalid_code"
                except api.BridgeError as err:
                    _LOGGER.debug("pairing failed: %s", err)
                    errors["base"] = "cannot_connect"
                else:
                    return self.async_create_entry(
                        title=self._device_name or "Timer bridge",
                        data={
                            CONF_HOST: self._host,
                            CONF_PORT: self._port,
                            CONF_DEVICE_ID: self._device_id,
                            CONF_DEVICE_NAME: self._device_name,
                            CONF_PAIRING_CODE: user_input[CONF_PAIRING_CODE],
                            CONF_WEBHOOK_ID: webhook_id,
                        },
                    )

        return self.async_show_form(
            step_id="pair",
            data_schema=vol.Schema({vol.Required(CONF_PAIRING_CODE): str}),
            errors=errors,
            description_placeholders={"name": self._device_name or self._host},
        )

    def _webhook_url(self, webhook_id: str) -> str:
        """Build a webhook URL the tablet can actually reach on the LAN."""
        base = get_url(
            self.hass,
            allow_external=False,
            allow_cloud=False,
            allow_ip=True,
            prefer_external=False,
        )
        return f"{base}{webhook.async_generate_path(webhook_id)}"
