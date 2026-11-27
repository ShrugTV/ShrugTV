package com.shrug.shrugtv.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Cancelled coroutine callers kept their OkHttp request running until it finished or timed out
 *. The server never answers here, so only a real cancel ends the call early.
 */
class HttpCancelTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun runningCallToServer(): Call? =
        Http.client().dispatcher.runningCalls().firstOrNull { it.request().url.port == server.port }

    private fun waitUntilNoCallToServer() {
        val end = System.currentTimeMillis() + 5_000
        while (runningCallToServer() != null) {
            if (System.currentTimeMillis() > end) fail("the call is still running after the cancel")
            Thread.sleep(20)
        }
    }

    @Test
    fun `cancelling await cancels the OkHttp call`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.Default) { Http.load(server.url("/streams").toString()).asJsonObject().await() }
        assertNotNull("request reached the server", server.takeRequest(5, TimeUnit.SECONDS))
        val call = runningCallToServer()
        assertNotNull(call)
        job.cancelAndJoin()
        assertTrue(call!!.isCanceled())
        waitUntilNoCallToServer()
    }

    @Test
    fun `await returns the body and fails with the status`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"7"}]}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        val json = Http.load(server.url("/a").toString()).asJsonObject().await()
        assertEquals("7", json.getAsJsonArray("data")[0].asJsonObject.get("id").asString)
        try {
            Http.load(server.url("/b").toString()).asJsonObject().await()
            fail("401 must not be a result")
        } catch (e: Http.StatusException) {
            assertEquals(401, e.code)
        }
        assertNull(Http.load(server.url("/c").toString()).asJsonObject().awaitOrNull())
    }

    @Test
    fun `call group cancel aborts a blocking get`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val group = CallGroup()
        var error: Throwable? = null
        val thread = Thread {
            try {
                Http.load(server.url("/token").toString()).asJsonObject().get(group)
            } catch (e: ExecutionException) {
                error = e
            }
        }
        thread.start()
        assertNotNull("request reached the server", server.takeRequest(5, TimeUnit.SECONDS))
        group.cancel()
        thread.join(5_000) // the read timeout is 15 s, so this only ends in time through the cancel
        assertTrue("get still blocked after the cancel", !thread.isAlive)
        assertNotNull(error)
        assertTrue(group.isCancelled)

        // a later request of a cancelled group does not go out at all
        val before = server.requestCount
        try {
            Http.load(server.url("/playlist").toString()).asString().get(group)
            fail("a cancelled group must not send requests")
        } catch (e: ExecutionException) {
            assertEquals(before, server.requestCount)
        }
    }
}
