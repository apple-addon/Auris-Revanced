package com.music.bitchord

import com.music.bitchord.data.apple.AppleLibraryPage
import com.music.bitchord.data.apple.AppleSources
import com.music.bitchord.data.apple.followingOffset
import com.music.bitchord.data.sources.addon.AddonManifest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppleLibraryPaginationTest {
    private lateinit var server: MockWebServer
    private lateinit var handle: AppleSources.Handle

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        handle = AppleSources.Handle(
            configId = "apple-test",
            baseUrl = server.url("/").toString(),
            manifest = AddonManifest(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `one source call publishes one page and preserves its continuation`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"tracks":[{"type":"song","id":"library-1","catalogId":"catalog-1","title":"One"}],"offset":0,"hasMore":true,"nextOffset":1}""",
                ),
        )

        val page = AppleSources.librarySongPage(handle, offset = 0, limit = 1).getOrThrow()
        val request = server.takeRequest()

        assertEquals(1, page.items.size)
        assertEquals(1, page.nextOffset)
        assertEquals(1, server.requestCount)
        assertEquals("/library/songs", request.path?.substringBefore('?'))
        assertEquals("0", request.requestUrl?.queryParameter("offset"))
        assertEquals("1", request.requestUrl?.queryParameter("limit"))
        assertEquals("catalog-1", com.music.bitchord.data.sources.SourceRegistry.parseTrackKey(
            page.items.single().videoId,
        )?.second)
    }

    @Test
    fun `terminal empty and non advancing pages never spin`() {
        val song = com.music.bitchord.data.apple.AppleItem(id = "1", title = "One")

        assertNull(AppleLibraryPage(tracks = listOf(song), hasMore = false, nextOffset = 100).followingOffset(0))
        assertNull(AppleLibraryPage(tracks = emptyList(), hasMore = true, nextOffset = 100).followingOffset(0))
        assertNull(AppleLibraryPage(tracks = listOf(song), hasMore = true, nextOffset = 0).followingOffset(0))
        assertEquals(1, AppleLibraryPage(tracks = listOf(song), hasMore = true).followingOffset(0))
        assertEquals(7, AppleLibraryPage(tracks = listOf(song), hasMore = true, nextOffset = 7).followingOffset(0))
    }

    @Test
    fun `http failure remains failure rather than an empty page`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = AppleSources.libraryPlaylistPage(handle)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
    }
}
