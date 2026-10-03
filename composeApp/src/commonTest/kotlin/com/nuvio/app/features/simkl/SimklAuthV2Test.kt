package com.nuvio.app.features.simkl

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklAuthV2Test {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `token version is read from the prefix`() {
        assertTrue(SimklAuthState(accessToken = "simkl_at_0123456789abcdefghijklmnopqrstuv").isV2)
        assertFalse(SimklAuthState(accessToken = "a".repeat(64)).isV2)
        assertFalse(SimklAuthState().isV2)
    }

    @Test
    fun `payloads saved before AUTH V2 still load as a connected V1 account`() {
        val legacy = """{"accessToken":"${"f".repeat(64)}","username":"jordan","accountType":"vip"}"""
        val state = json.decodeFromString<SimklAuthState>(legacy)

        assertTrue(state.isAuthenticated)
        assertFalse(state.isV2)
        assertFalse(state.needsReconnect)
        assertNull(state.clientId)
        assertNull(state.refreshToken)
    }

    @Test
    fun `device code response parses the RFC 8628 shape`() {
        val body = """
            {"device_code":"dc","user_code":"BDWP-HQPK","verification_uri":"https://simkl.com/pin",
             "verification_uri_complete":"https://simkl.com/pin?user_code=BDWP-HQPK",
             "expires_in":900,"interval":5}
        """.trimIndent()
        val device = json.decodeFromString<SimklDeviceCodeResponse>(body)

        assertEquals("BDWP-HQPK", device.userCode)
        assertEquals("https://simkl.com/pin?user_code=BDWP-HQPK", device.verificationUriComplete)
        assertEquals(900, device.expiresIn)
        assertEquals(5, device.interval)
    }

    @Test
    fun `token poll errors parse from a 400 body`() {
        val pending = json.decodeFromString<SimklTokenResponse>("""{"error":"authorization_pending"}""")
        assertEquals("authorization_pending", pending.error)
        assertNull(pending.accessToken)

        val granted = json.decodeFromString<SimklTokenResponse>(
            """{"access_token":"simkl_at_x","refresh_token":"simkl_rt_y","expires_in":604800,
               "token_type":"Bearer","scope":"media:read media:write"}""",
        )
        assertEquals("simkl_rt_y", granted.refreshToken)
        assertEquals(604_800L, granted.expiresIn)
        assertEquals("media:read media:write", granted.scope)
    }

    @Test
    fun `form encoding escapes the scope separator and device grant colons`() {
        assertEquals("media%3Aread+media%3Awrite", simklUrlEncode("media:read media:write"))
        assertEquals(
            "urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code",
            simklUrlEncode("urn:ietf:params:oauth:grant-type:device_code"),
        )
        assertEquals("simkl_rt_AZaz09-._~", simklUrlEncode("simkl_rt_AZaz09-._~"))
    }

    @Test
    fun `old registrations say notinteresting where new ones say dropped`() {
        assertTrue(isSimklDroppedStatus("dropped"))
        assertTrue(isSimklDroppedStatus("notinteresting"))
        assertFalse(isSimklDroppedStatus("hold"))
        assertFalse(isSimklDroppedStatus(null))
    }
}
