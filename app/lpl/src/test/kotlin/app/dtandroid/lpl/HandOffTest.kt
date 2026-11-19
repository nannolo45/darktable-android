// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-off link is untrusted input (brief P2e item 4): every part is checked. */
class HandOffTest {
    private fun ok(link: String) = (HandOff.parse(link) as? HandOffResult.Ok ?: error("refused: $link -> ${HandOff.parse(link)}")).handOff
    private fun refused(link: String) = assertTrue("should be refused: $link", HandOff.parse(link) is HandOffResult.Invalid)

    @Test fun theLinkLplBuilds() {
        // the example of LPL's hand-off notes (made-up ids, documentation address)
        val h = ok("lpldt://edit?server=http%3A%2F%2F192.0.2.10%3A8080&ids=12,7,31&start=7&series=4&title=Lake%20%26%20hills%2F2024")
        assertEquals(ServerOrigin("http", "192.0.2.10", 8080), h.server)
        assertEquals(listOf(12L, 7L, 31L), h.ids)       // LPL's order, kept
        assertEquals(7L, h.start)
        assertEquals(4L, h.series)
        assertEquals("Lake & hills/2024", h.title)
    }

    @Test fun startOutsideTheListGoesFirstAndRepeatsAreDropped() {
        val h = ok("lpldt://edit?server=https%3A%2F%2Fexample.org&ids=3,2,3,1&start=9")
        assertEquals(listOf(9L, 3L, 2L, 1L), h.ids)
        assertEquals(ServerOrigin("https", "example.org", 443), h.server)
        assertNull(h.series); assertNull(h.title)
        assertEquals(3L, ok("lpldt://edit?server=http%3A%2F%2Fexample.org&ids=3,2").start)
        val many = (1..200).joinToString(",")
        val h2 = ok("lpldt://edit?server=http%3A%2F%2Fexample.org&ids=$many&start=500")
        assertEquals(200, h2.ids.size)
        assertEquals(500L, h2.ids.first())
    }

    @Test fun refusedLinks() {
        val s = "server=http%3A%2F%2F192.0.2.10%3A8080"
        refused("lpldt://other?$s&ids=1")
        refused("https://edit?$s&ids=1")
        refused("lpldt://edit?ids=1")                                         // no server
        refused("lpldt://edit?$s")                                            // no ids
        refused("lpldt://edit?$s&ids=")
        refused("lpldt://edit?$s&ids=1,x")
        refused("lpldt://edit?$s&ids=-1")
        refused("lpldt://edit?$s&ids=0")
        refused("lpldt://edit?$s&ids=1.5")
        refused("lpldt://edit?$s&ids=1234567890123456")
        refused("lpldt://edit?$s&ids=" + (1..201).joinToString(","))
        refused("lpldt://edit?$s&ids=1&start=a")
        refused("lpldt://edit?$s&ids=1&series=x")
        refused("lpldt://edit?$s&ids=1&ids=2")                                // twice
        refused("lpldt://edit?$s&$s&ids=1")
        refused("lpldt://edit?$s&ids=1#frag")
        refused("lpldt://edit?server=ftp%3A%2F%2F192.0.2.10&ids=1")
        refused("lpldt://edit?server=file%3A%2F%2F%2Fsdcard&ids=1")
        refused("lpldt://edit?server=http%3A%2F%2Fuser%3Apw%40192.0.2.10&ids=1")   // credentials
        refused("lpldt://edit?server=http%3A%2F%2F192.0.2.10%2Fapi&ids=1")         // a path
        refused("lpldt://edit?server=http%3A%2F%2F192.0.2.10%3Fx%3D1&ids=1")       // a query
        refused("lpldt://edit?server=http%3A%2F%2F192.0.2.10%3A99999&ids=1")       // port
        refused("lpldt://edit?server=192.0.2.10&ids=1")                            // no scheme
        refused("lpldt://edit?server=http%3A%2F%2Fbad%20host&ids=1")
        refused("lpldt://edit?server=%ZZ&ids=1")
        refused("lpldt://edit?$s&ids=1&title=" + "a".repeat(20_000))
    }

    @Test fun titleIsCleanedAndUnknownParametersIgnored() {
        val h = ok("lpldt://edit?server=http%3A%2F%2F192.0.2.10&ids=1&title=a%0Ab%00c&x=y&series=")
        assertEquals("abc", h.title)
        assertNull(h.series)
        assertEquals(ServerOrigin("http", "192.0.2.10", 80), h.server)
        assertEquals(ServerOrigin("http", "192.0.2.10", 80), ok("lpldt://edit?server=http%3A%2F%2F192.0.2.10%2F&ids=1").server)
        assertEquals(ServerOrigin("http", "fd7a::1", 8080), ok("lpldt://edit?server=http%3A%2F%2F%5Bfd7a%3A%3A1%5D%3A8080&ids=1").server)
    }

    @Test fun addressesTypedByTheUser() {
        assertEquals(AddressInput.Ok(listOf(ServerOrigin("https", "192.0.2.10", 8080), ServerOrigin("http", "192.0.2.10", 8080))),
            ServerOrigin.candidates(" 192.0.2.10:8080/ "))
        assertEquals(AddressInput.Ok(listOf(ServerOrigin("http", "example.org", 80))), ServerOrigin.candidates("HTTP://Example.org/x?y"))
        assertEquals(AddressInput.Invalid(AddressProblem.USER_INFO), ServerOrigin.candidates("http://a:b@example.org"))
        assertEquals(AddressInput.Invalid(AddressProblem.SCHEME), ServerOrigin.candidates("ftp://example.org"))
        assertEquals(AddressInput.Invalid(AddressProblem.EMPTY), ServerOrigin.candidates("  "))
        assertEquals(AddressInput.Invalid(AddressProblem.SPACES), ServerOrigin.candidates("a b"))
        assertEquals(AddressInput.Invalid(AddressProblem.NOT_AN_ADDRESS), ServerOrigin.candidates("http://a..b"))
        assertEquals("http://192.0.2.10:8080", ServerOrigin("http", "192.0.2.10", 8080).url)
        assertEquals("http_192.0.2.10_8080", ServerOrigin("http", "192.0.2.10", 8080).key)
    }
}
