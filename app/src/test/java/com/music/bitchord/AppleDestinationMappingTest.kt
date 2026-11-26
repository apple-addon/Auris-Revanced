package com.music.bitchord

import com.music.bitchord.data.apple.AppleSources
import com.music.bitchord.data.model.AppleEditorialDestination
import com.music.bitchord.data.sources.addon.AddonManifest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppleDestinationMappingTest {
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
    fun `chart destination keeps full ids and nested typed routes`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{
                      "page":"charts","title":"Top Charts",
                      "destination":{"kind":"charts","id":"34","popId":"11","route":"browse/destination"},
                      "elements":[{
                        "id":"11","kind":"327","title":"Top Songs","resourceTypes":["songs"],
                        "itemIds":["1","2","missing-preview"],
                        "destination":{"kind":"charts","id":"34","popId":"11","route":"browse/destination"},
                        "items":[{"id":"1","type":"songs","name":"One","artistName":"Artist"}],
                        "links":[{"label":"More","url":"https://itunes.apple.com/in/collection/dance/id171952?fcId=6737578221&mt=14","destination":{"kind":"room","id":"6737578221","route":"browse/destination"}}]
                      }]
                    }""",
                ),
        )

        val content = AppleSources.editorialDestination(
            handle,
            AppleEditorialDestination(kind = "charts", id = "34", popId = "11"),
        ).getOrThrow()
        val element = content.elements.single()

        assertEquals("Top Charts", content.title)
        assertEquals(listOf("1", "2", "missing-preview"), element.itemIds)
        assertEquals(1, element.items.size)
        assertEquals("11", element.destination?.popId)
        assertEquals("room", element.links.single().destination?.kind)
        assertEquals("6737578221", element.links.single().destination?.id)
        val request = server.takeRequest()
        assertEquals("charts", request.requestUrl?.queryParameter("kind"))
        assertEquals("34", request.requestUrl?.queryParameter("id"))
        assertEquals("11", request.requestUrl?.queryParameter("popId"))
    }

    @Test
    fun `concert hub maps real artists venues dates and ticket providers`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{
                      "page":"concerts","locationRequired":false,"geoHashLocation":"gcpv",
                      "concertHub":{"order":["popular"],"containers":[{
                        "id":"popular","title":"Popular","data":[{
                          "id":"ce.one","type":"concerts",
                          "attributes":{"name":"Live Show","startISODateTime":"2026-11-23T19:00:00Z","endISODateTime":"2026-11-23T21:00:00Z","timezone":"Asia/Kolkata","tickets":[{"vendor":"Ticketmaster","url":"https://tickets.example/show"}]},
                          "relationships":{
                            "artists":{"data":[{"attributes":{"name":"Artist","artwork":{"url":"https://img/artist/{w}x{h}.jpg"}}}]},
                            "venues":{"data":[{"attributes":{"name":"Venue","structuredAddress":{"city":"Mumbai","countryCode":"IN","latitude":19.0}}}]}
                          }
                        }]
                      }]}
                    }""",
                ),
        )

        val content = AppleSources.editorialDestination(
            handle,
            AppleEditorialDestination(kind = "concerts", geo = "gcpv"),
        ).getOrThrow()
        val hub = requireNotNull(content.concertHub)
        val event = hub.containers.single().events.single()

        assertFalse(hub.locationRequired)
        assertEquals("gcpv", hub.geoHashLocation)
        assertEquals("Artist", event.artistName)
        assertEquals("https://img/artist/{w}x{h}.jpg", event.artworkUrl)
        assertEquals("Venue", event.venueName)
        assertEquals("Mumbai, IN", event.address)
        assertEquals("Ticketmaster", event.ticketVendor)
        assertEquals("https://tickets.example/show", event.ticketUrl)
        assertEquals("gcpv", server.takeRequest().requestUrl?.queryParameter("geo"))
    }

    @Test
    fun `concerts without a geohash retain location requirement`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"page":"concerts","locationRequired":true,"geoHashLocation":null,"concertHub":{"order":[],"containers":[]}}""",
                ),
        )

        val hub = AppleSources.editorialDestination(
            handle,
            AppleEditorialDestination(kind = "concerts"),
        ).getOrThrow().concertHub

        assertTrue(hub?.locationRequired == true)
        assertNull(hub?.geoHashLocation)
        assertTrue(hub?.containers?.isEmpty() == true)
        assertNull(server.takeRequest().requestUrl?.queryParameter("geo"))
    }
}
