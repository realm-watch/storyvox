package `in`.jphe.storyvox.source.radio

import `in`.jphe.storyvox.source.radio.net.RadioBrowserStation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #417 — JSON-parse tests for the Radio Browser API client.
 *
 * The full HTTP round-trip is exercised at integration time (manual
 * verification on the tablet); these tests pin the JSON-shape contract
 * against a captured fixture, which is the bit that breaks loudly when
 * Radio Browser revs their API or the client's decoder drifts.
 *
 * The fixture below is a real Radio Browser response captured via
 * `curl https://de1.api.radio-browser.info/json/stations/byname/kcsb`
 * during PR authoring — 2026-05-14. Only the fields storyvox decodes
 * are guaranteed to round-trip; unknown fields are tolerated via
 * `Json { ignoreUnknownKeys = true }` in the production client.
 */
class RadioBrowserApiTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test fun `parses a real Radio Browser byName response into a RadioStation`() {
        val raw = KCSB_FIXTURE
        val stations = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(RadioBrowserStation.serializer()),
            raw,
        )
        assertEquals(1, stations.size)
        val kcsb = stations.first()
        assertEquals("KCSB UC Santa Barbara", kcsb.name)
        assertEquals("https://kcsb.streamguys1.com/live", kcsb.urlResolved)
        assertEquals("MP3", kcsb.codec)

        // Round-trip through the production mapper — yields a usable
        // RadioStation with the rb: prefix on the id.
        val station = kcsb.toRadioStation()
        assertNotNull("RadioBrowserStation should map cleanly to RadioStation", station)
        assertTrue("Mapped id must carry the rb: prefix", station!!.id.startsWith("rb:"))
        assertEquals("KCSB UC Santa Barbara", station.displayName)
        assertEquals("https://kcsb.streamguys1.com/live", station.streamUrl)
        assertTrue(
            "tags must split on comma",
            station.tags.contains("college radio"),
        )
    }

    @Test fun `non-HTTPS streams are filtered out`() {
        val raw = """
            {
                "stationuuid":"abc","name":"Cleartext Station",
                "url_resolved":"http://example.com/stream.mp3",
                "url":"http://example.com/stream.mp3",
                "lastcheckok":1,"ssl_error":0,"codec":"MP3",
                "tags":"","homepage":"","country":"","language":""
            }
        """.trimIndent()
        val station = json.decodeFromString(RadioBrowserStation.serializer(), raw)
            .toRadioStation()
        assertNull("Cleartext stations must drop out at the mapper", station)
    }

    @Test fun `broken stations (lastcheckok=0) drop out`() {
        val raw = """
            {
                "stationuuid":"abc","name":"Broken Station",
                "url_resolved":"https://example.com/stream.mp3",
                "lastcheckok":0,"ssl_error":0,"codec":"MP3",
                "tags":"","homepage":"","country":"","language":""
            }
        """.trimIndent()
        val station = json.decodeFromString(RadioBrowserStation.serializer(), raw)
            .toRadioStation()
        assertNull("Broken stations must drop out at the mapper", station)
    }

    @Test fun `ssl_error=1 stations drop out`() {
        val raw = """
            {
                "stationuuid":"abc","name":"Cert Broken Station",
                "url_resolved":"https://example.com/stream.mp3",
                "lastcheckok":1,"ssl_error":1,"codec":"MP3",
                "tags":"","homepage":"","country":"","language":""
            }
        """.trimIndent()
        val station = json.decodeFromString(RadioBrowserStation.serializer(), raw)
            .toRadioStation()
        assertNull("SSL-error stations must drop out at the mapper", station)
    }

    @Test fun `blank-name stations drop out`() {
        val raw = """
            {
                "stationuuid":"abc","name":"   ",
                "url_resolved":"https://example.com/stream.mp3",
                "lastcheckok":1,"ssl_error":0,"codec":"MP3",
                "tags":"","homepage":"","country":"","language":""
            }
        """.trimIndent()
        val station = json.decodeFromString(RadioBrowserStation.serializer(), raw)
            .toRadioStation()
        assertNull("Blank-name stations must drop out at the mapper", station)
    }

    @Test fun `falls back to url when url_resolved is blank`() {
        val raw = """
            {
                "stationuuid":"abc","name":"Direct URL Station",
                "url_resolved":"",
                "url":"https://example.com/direct.mp3",
                "lastcheckok":1,"ssl_error":0,"codec":"MP3",
                "tags":"a,b","homepage":"https://example.com","country":"US","language":"en"
            }
        """.trimIndent()
        val station = json.decodeFromString(RadioBrowserStation.serializer(), raw)
            .toRadioStation()
        assertNotNull(station)
        assertEquals("https://example.com/direct.mp3", station!!.streamUrl)
    }

    @Test fun `tolerates unknown fields in the wire shape`() {
        // Forward-compat — Radio Browser routinely adds fields. The
        // decoder must NOT throw on a field it doesn't recognize.
        val raw = """
            {
                "stationuuid":"abc","name":"Future Station",
                "url_resolved":"https://example.com/stream.mp3",
                "lastcheckok":1,"ssl_error":0,"codec":"MP3",
                "tags":"","homepage":"","country":"","language":"",
                "future_field":"some new metadata",
                "another_new_field":42
            }
        """.trimIndent()
        val station = json.decodeFromString(RadioBrowserStation.serializer(), raw)
            .toRadioStation()
        assertNotNull("Unknown fields must not break the parse", station)
    }

    private companion object {
        /** Captured 2026-05-14 from
         *  `curl https://de1.api.radio-browser.info/json/stations/byname/kcsb`. */
        const val KCSB_FIXTURE: String = """[
            {
                "changeuuid":"fdde1651-3fb6-410c-a623-53daed6b0a7b",
                "stationuuid":"4af97ba5-4546-4132-a94a-ebca09214cfb",
                "serveruuid":null,
                "name":"KCSB UC Santa Barbara",
                "url":"https://kcsb.streamguys1.com/live",
                "url_resolved":"https://kcsb.streamguys1.com/live",
                "homepage":"https://www.kcsb.org/",
                "favicon":"",
                "tags":"college radio",
                "country":"The United States Of America",
                "countrycode":"US",
                "iso_3166_2":"",
                "state":"California",
                "language":"american english",
                "languagecodes":"",
                "votes":40,
                "lastchangetime":"2026-01-14 22:54:04",
                "lastchangetime_iso8601":"2026-01-14T22:54:04Z",
                "codec":"MP3",
                "bitrate":128,
                "hls":0,
                "lastcheckok":1,
                "lastchecktime":"2026-01-15 04:34:41",
                "lastchecktime_iso8601":"2026-01-15T04:34:41Z",
                "lastcheckoktime":"2026-01-15 04:34:41",
                "lastcheckoktime_iso8601":"2026-01-15T04:34:41Z",
                "lastlocalchecktime":"2026-01-15 04:34:41",
                "lastlocalchecktime_iso8601":"2026-01-15T04:34:41Z",
                "clicktimestamp":"2026-05-14 16:09:22",
                "clicktimestamp_iso8601":"2026-05-14T16:09:22Z",
                "clickcount":1,
                "clicktrend":1,
                "ssl_error":0,
                "geo_lat":null,
                "geo_long":null,
                "geo_distance":null,
                "has_extended_info":false
            }
        ]"""
    }
}
