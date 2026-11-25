// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lpl

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Brief P4a deliverable 2: what the sync bar (the list's server line) and the sync notification say about the connection. */
class P4aLinkWordsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stub = StubLpl()

    @After fun stop() = stub.close()

    private val gone = LplException(LplFailure.UNREACHABLE, "POST /api/photos/1/xmp: ConnectException")

    @Test fun theWords() {
        assertEquals("connected", LinkWords.connection(null, true, null, true, false))
        assertEquals("not contacted yet", LinkWords.connection(null, false, null, true, true))
        assertEquals("waiting for connection", LinkWords.connection(gone, true, null, false, true))
        assertEquals("waiting for connection", LinkWords.connection(null, true, null, false, true))
        assertEquals("offline with nothing to send: the old state", "connected", LinkWords.connection(null, true, null, false, false))
        assertEquals("retrying (attempt 2)", LinkWords.connection(gone, true, LplAgent.Retrying(1, 500), true, true))
        assertEquals("LightPhotoLibrary is not reachable right now · retrying", LinkWords.connection(gone, true, null, true, true))
        assertEquals("LightPhotoLibrary is not reachable right now", LinkWords.connection(gone, true, null, true, false))
        val refused = LplException(LplFailure.UNAUTHORIZED, "GET /api/status: 401", 401)
        assertEquals(refused.userText, LinkWords.connection(refused, true, null, true, true))

        assertEquals("waiting for connection", LinkWords.notice(true, false, false))
        assertEquals("retrying", LinkWords.notice(false, true, true))
        assertEquals("server not reachable · retrying", LinkWords.notice(true, false, true))
        assertNull(LinkWords.notice(false, false, true))
        assertEquals("LightPhotoLibrary · waiting for connection", SyncNotice.of(null, 3, 1, 0, null, "waiting for connection").text)
        assertEquals("LightPhotoLibrary", SyncNotice.of(null, 3, 1, 0, null).text)
    }

    @Test fun theAgentSaysRetryingWhileACallIsRepeatedAndForgetsItAfterwards() = runBlocking<Unit> {
        val servers = ServerStore(File(tmp.root, "lpl"), MemorySecretBox())
        servers.put(stub.origin, "Phone", null)
        stub.add(StubLpl.Photo(1, "A.RAF", fakeRaw(10_000, 1)))
        lateinit var agent: LplAgent
        val seen = ArrayList<LplAgent.Retrying?>()
        agent = LplAgent(servers, WorkStore(File(tmp.root, "work")), FakeEditor(), { "test" },
            clientFor = { o -> LplClient(o, { null }, settings = ClientSettings(backoffMs = listOf(7, 9)), sleep = { seen += agent.retrying.value[stub.origin] }) })
        // checkout: two attempts get no answer, the third works (each attempt is two requests: the JDK repeats a GET
        // once by itself when the connection closes without an answer)
        stub.dropNext = 4
        agent.take(stub.origin, 1)
        assertEquals(listOf(LplAgent.Retrying(1, 7), LplAgent.Retrying(2, 9)), seen)
        assertTrue("gone after the call", agent.retrying.value.isEmpty())
    }
}
