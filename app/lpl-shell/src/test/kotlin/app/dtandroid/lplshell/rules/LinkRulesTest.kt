// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRulesTest {
    private val saved = Origin("http", "192.168.1.20", 8080)
    private val q = "server=http%3A%2F%2F192.168.1.20%3A8080&ids=12,13,14&start=13&series=4&title=Spring%20walk"
    private val r1 = "intent://edit?$q#Intent;scheme=lpldt;package=app.dtandroid;" +
        "S.browser_fallback_url=http%3A%2F%2F192.168.1.20%3A8080%2Fhelp;end"

    private fun handOff(url: String) = (LinkRules.decide(saved, url) as LinkDecision.HandOffTo).handOff
    private fun refused(url: String) = assertTrue(url, LinkRules.decide(saved, url) is LinkDecision.Refuse)

    @Test
    fun sameOriginStaysOtherOriginsLeave() {
        assertEquals(LinkDecision.Stay, LinkRules.decide(saved, "http://192.168.1.20:8080/?photo=3"))
        assertEquals(LinkDecision.Stay, LinkRules.decide(saved, "about:blank"))
        assertEquals(LinkDecision.OpenOutside("https://example.org/"), LinkRules.decide(saved, "https://example.org/"))
        // other scheme, other port, other host form: all other origins
        assertEquals(LinkDecision.OpenOutside("https://192.168.1.20:8080/"), LinkRules.decide(saved, "https://192.168.1.20:8080/"))
        assertEquals(LinkDecision.OpenOutside("http://192.168.1.20:8081/"), LinkRules.decide(saved, "http://192.168.1.20:8081/"))
        assertEquals(LinkDecision.OpenOutside("http://192.168.1.20/"), LinkRules.decide(saved, "http://192.168.1.20/"))
        refused("http://192.168.1.20:8080@example.org/")
        refused("javascript:alert(1)")
        refused("file:///sdcard/Download/x.html")
        refused("content://media/external/images/1")
        refused("data:text/html,hi")
        refused("market://details?id=x")
        refused("about:config")
    }

    @Test
    fun r1LinkGivesOnlyTheQuery() {
        val h = handOff(r1)
        assertEquals(q, h.query)
        assertEquals("lpldt://edit?$q", h.data)
        assertEquals(emptyList<String>(), h.stripped)
    }

    @Test
    fun directLpldtLink() {
        assertEquals("lpldt://edit?$q", handOff("lpldt://edit?$q").data)
        assertEquals("lpldt://edit?$q", handOff("lpldt://edit/?$q").data)
        assertEquals("lpldt://edit?$q", handOff("LPLDT://edit?$q").data)
        refused("lpldt://edit")
        refused("lpldt://edit?")
        refused("lpldt://delete?$q")
        refused("lpldt://edit.example.org?$q")
        refused("lpldt://edit/other?$q")
        refused("lpldt://edit?$q#fragment")
        refused("lpldt:edit?$q")
        refused("lpldt://edit?a=<script>")
        refused("lpldt://edit?a=%zz")
        refused("lpldt://edit?a=b c")
    }

    @Test
    fun hostileIntentLinksAreStrippedToTheQuery() {
        // component, selector, flags, action, categories and extras are dropped
        val hostile = "intent://edit?$q#Intent;scheme=lpldt;package=app.dtandroid;" +
            "component=app.dtandroid/.internal.DeleteEverything;action=android.intent.action.DELETE;" +
            "category=android.intent.category.LAUNCHER;launchFlags=0x10008000;" +
            "S.extra=x;i.count=3;B.wipe=true;l.n=1;SEL;scheme=http;package=com.example.evil;" +
            "component=com.example.evil/.Steal;end"
        val h = handOff(hostile)
        assertEquals("lpldt://edit?$q", h.data)
        assertEquals(listOf("component", "action", "category", "launchFlags", "S.extra", "i.count", "B.wipe", "l.n", "SEL"), h.stripped)
    }

    @Test
    fun otherIntentLinksAreRefused() {
        // other package, other scheme, missing either, given twice
        refused("intent://edit?$q#Intent;scheme=lpldt;package=com.example.evil;end")
        refused("intent://edit?$q#Intent;scheme=http;package=app.dtandroid;end")
        refused("intent://edit?$q#Intent;scheme=lpldt;end")
        refused("intent://edit?$q#Intent;package=app.dtandroid;end")
        refused("intent://edit?$q#Intent;scheme=lpldt;package=app.dtandroid;package=com.example.evil;end")
        refused("intent://edit?$q#Intent;scheme=lpldt;scheme=http;package=app.dtandroid;end")
        refused("intent://edit?$q#Intent;package=app.dtandroid.g0;scheme=lpldt;end")
        // scheme and package only inside the selector
        refused("intent://edit?$q#Intent;SEL;scheme=lpldt;package=app.dtandroid;end")
        // structure
        refused("intent://edit?$q")
        refused("intent://edit?$q#Intent;scheme=lpldt;package=app.dtandroid")
        refused("intent://edit?$q#Intent;scheme=lpldt;package=app.dtandroid;end;component=x/.Y")
        refused("intent://edit?$q#Intent;scheme=lpldt;bogus;package=app.dtandroid;end")
        refused("intent:#Intent;scheme=lpldt;package=app.dtandroid;end")
        refused("intent://delete?$q#Intent;scheme=lpldt;package=app.dtandroid;end")
        refused("intent://edit#Intent;scheme=lpldt;package=app.dtandroid;end")
        refused("intent://edit?$q#x#Intent;scheme=lpldt;package=app.dtandroid;end")
        refused("intent://example.org/#Intent;scheme=https;end")
        refused("intent:#Intent;component=com.android.settings/.Settings;end")
        refused("intent://edit?$q#Intent;scheme=lpldt;package=app%2Gdtandroid;end")
        // too long
        refused("intent://edit?ids=" + "1,".repeat(9000) + "#Intent;scheme=lpldt;package=app.dtandroid;end")
    }

    @Test
    fun encodedAttributeValuesAreDecodedBeforeComparing() {
        assertEquals("lpldt://edit?$q", handOff("intent://edit?$q#Intent;scheme=lpld%74;package=app%2Edtandroid;end").data)
        assertEquals("lpldt://edit?$q", handOff("INTENT://edit?$q#Intent;scheme=lpldt;package=app.dtandroid;end").data)
    }

    @Test
    fun queryCharacters() {
        assertTrue(LinkRules.isValidQuery(q))
        assertTrue(LinkRules.isValidQuery("title=%C3%A9t%C3%A9&ids=1"))
        assertTrue(!LinkRules.isValidQuery("a=%4"))
        assertTrue(!LinkRules.isValidQuery("a=\"x\""))
        assertTrue(!LinkRules.isValidQuery("a=é"))
        assertEquals("été", LinkRules.percentDecode("%C3%A9t%C3%A9"))
        assertEquals(null, LinkRules.percentDecode("%C3"))
    }
}
