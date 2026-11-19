// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressAndOriginTest {
    private fun ok(input: String) = (ServerAddress.normalise(input) as AddressResult.Ok).origin.url
    private fun problem(input: String) = (ServerAddress.normalise(input) as AddressResult.Invalid).problem

    @Test
    fun normalisation() {
        assertEquals("http://192.168.1.20:8080", ok("http://192.168.1.20:8080"))
        assertEquals("http://192.168.1.20:8080", ok("192.168.1.20:8080"))
        assertEquals("http://192.168.1.20:8080", ok("  192.168.1.20:8080/  "))
        assertEquals("http://192.168.1.20:8080", ok("http://192.168.1.20:8080/library/photos?x=1#top"))
        assertEquals("http://192.168.1.20", ok("192.168.1.20"))
        assertEquals("http://192.168.1.20", ok("http://192.168.1.20:80/"))
        assertEquals("https://example.org", ok("HTTPS://Example.ORG:443"))
        assertEquals("https://example.org:8443", ok("https://example.org:8443/"))
        assertEquals("http://lpl.example.org", ok("lpl.example.org/"))
        assertEquals("http://[fd00::20]:8080", ok("http://[FD00::20]:8080/"))
        assertEquals("http://localhost:8080", ok("localhost:8080"))
    }

    @Test
    fun refusedAddresses() {
        assertEquals(AddressProblem.EMPTY, problem("   "))
        assertEquals(AddressProblem.SPACES, problem("192.168.1.20 8080"))
        assertEquals(AddressProblem.SCHEME, problem("ftp://192.168.1.20"))
        assertEquals(AddressProblem.SCHEME, problem("file:///sdcard/x"))
        assertEquals(AddressProblem.SCHEME, problem("javascript://x"))
        assertEquals(AddressProblem.USER_INFO, problem("http://admin:secret@192.168.1.20:8080"))
        assertEquals(AddressProblem.USER_INFO, problem("admin@192.168.1.20"))
        assertEquals(AddressProblem.NOT_AN_ADDRESS, problem("http://192.168.1.20:99999"))
        assertEquals(AddressProblem.NOT_AN_ADDRESS, problem("http://192.168.1.20:0"))
        assertEquals(AddressProblem.NOT_AN_ADDRESS, problem("http://"))
        assertEquals(AddressProblem.NOT_AN_ADDRESS, problem("http://exa\$mple.org"))
        assertEquals(AddressProblem.NOT_AN_ADDRESS, problem("http://a:1:2"))
        assertEquals(AddressProblem.NOT_AN_ADDRESS, problem("http://[fd00::1"))
    }

    @Test
    fun originOfUrls() {
        val saved = Origin("http", "192.168.1.20", 8080)
        assertEquals(saved, Origin.of("http://192.168.1.20:8080/photo?id=3#x"))
        assertEquals(saved, Origin.of("HTTP://192.168.1.20:8080"))
        assertEquals(Origin("http", "192.168.1.20", 80), Origin.of("http://192.168.1.20/"))
        assertEquals(Origin.of("http://192.168.1.20/"), Origin.of("http://192.168.1.20:80/"))
        // the host ends at a backslash, as in browsers: this is example.org, not the saved server
        assertEquals(Origin("http", "example.org", 80), Origin.of("http://example.org\\@192.168.1.20:8080/"))
        assertNull(Origin.of("http://192.168.1.20:8080@example.org/"))
        assertNull(Origin.of("http://user@192.168.1.20:8080/"))
        assertNull(Origin.of("ftp://192.168.1.20/"))
        assertNull(Origin.of("intent://edit?x#Intent;end"))
        assertNull(Origin.of("javascript:alert(1)"))
        assertNull(Origin.of("http:192.168.1.20"))
    }
}
