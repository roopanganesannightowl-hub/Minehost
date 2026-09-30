package com.minehost.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun `recognises tailnet addresses`() {
        assertTrue(NetworkUtils.isTailnetAddress("100.64.0.1"))
        assertTrue(NetworkUtils.isTailnetAddress("100.101.102.103"))
        assertTrue(NetworkUtils.isTailnetAddress("100.127.255.254"))
    }

    @Test
    fun `leaves LAN, carrier and public addresses alone`() {
        assertFalse(NetworkUtils.isTailnetAddress("192.168.1.20"))
        assertFalse(NetworkUtils.isTailnetAddress("10.0.0.5"))
        assertFalse(NetworkUtils.isTailnetAddress("172.16.4.9"))
        // Just outside the 100.64.0.0/10 shared range.
        assertFalse(NetworkUtils.isTailnetAddress("100.63.255.255"))
        assertFalse(NetworkUtils.isTailnetAddress("100.128.0.0"))
        assertFalse(NetworkUtils.isTailnetAddress("8.8.8.8"))
    }

    @Test
    fun `rejects malformed input`() {
        assertFalse(NetworkUtils.isTailnetAddress("not-an-ip"))
        assertFalse(NetworkUtils.isTailnetAddress("100.64.0"))
        assertFalse(NetworkUtils.isTailnetAddress("100.64.0.999"))
        assertFalse(NetworkUtils.isTailnetAddress(""))
    }
}
