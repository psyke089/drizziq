package app.drizziq

import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class GeocodingTest {
    private val candidate =
        GeocodeCandidate(
            2950159,
            "Berlin",
            52.5243712345,
            13.4105312345,
            "Berlin",
            "Deutschland",
            "DE",
            "Europe/Berlin",
        )

    private fun body(name: String = "Berlin") =
        """{"results":[{"id":2950159,"name":"$name","latitude":52.5243712345,"longitude":13.4105312345,"timezone":"Europe/Berlin","unknown":42}]}"""
            .toByteArray()

    @Test
    fun query_unicodeWhitespaceEncodingAndBoundaries_areExplicitAndLocaleIndependent() {
        assertEquals("München, A&B", Geocoding.query("  München,\u00a0 A&B\n"))
        val url = Geocoding.url("München, A&B")
        assertTrue(url.contains("M%C3%BCnchen"))
        assertTrue(url.contains("%26"))
        assertTrue(url.endsWith("&count=10&language=de&format=json"))
        assertEquals(
            "München, A&B",
            URLDecoder.decode(URL(url).query.substringAfter("name=").substringBefore('&'), "UTF-8"),
        )
        for (bad in listOf("", "x", "x".repeat(121))) assertThrows(
            IllegalArgumentException::class.java
        ) {
            Geocoding.query(bad)
        }
        assertEquals("😀😀", Geocoding.query("😀😀"))
        assertEquals(120, Geocoding.query("😀".repeat(120)).codePointCount(0, 240))
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(Geocoding.key("École ISTANBUL"), Geocoding.key("E\u0301COLE istanbul"))
        } finally {
            Locale.setDefault(previous)
        }
        assertNotEquals(Geocoding.key("Munchen"), Geocoding.key("München"))
    }

    @Test
    fun parse_fixture_optionalFieldsAndUnknownData_preserveProviderOrderAndPrecision() {
        val fixture =
            javaClass
                .getResourceAsStream("/geocoding-v1-de-muenchen.json")!!
                .readBytes()
                .toString(Charsets.UTF_8)
        val parsed = Geocoding.parse(fixture)
        assertTrue(parsed.isNotEmpty())
        assertTrue(parsed.size <= 10)
        val one = Geocoding.parse(body().toString(Charsets.UTF_8)).single()
        assertEquals(52.5243712345, one.lat, 0.0)
        assertEquals(13.4105312345, one.lon, 0.0)
        assertNull(one.admin1)
        assertEquals("Europe/Berlin", one.timezone)
    }

    @Test
    fun parse_missingEmptyMalformedErrorAndWrongShapes_areDistinct() {
        assertTrue(Geocoding.parse("{}").isEmpty())
        assertTrue(Geocoding.parse("{\"results\":[]}").isEmpty())
        for (bad in
            listOf(
                "{",
                "[]",
                "null",
                "{\"error\":true,\"reason\":\"bad query\"}",
                "{\"results\":null}",
                "{\"results\":{}}",
                "{} trailing",
            )) {
            assertThrows(IllegalArgumentException::class.java) { Geocoding.parse(bad) }
        }
    }

    @Test
    fun parse_badCandidatesAndDuplicateIds_keepFirstValidAndAtMostTen() {
        val good = body().toString(Charsets.UTF_8).substringAfter('[').substringBeforeLast(']')
        val mixed =
            """{"results":[{"id":2950159,"name":"invalid","latitude":91,"longitude":0},$good,${good.replace("Berlin","Duplicate")},false,{"id":0},${good.replace("2950159","1.5")}]}"""
        assertEquals("Berlin", Geocoding.parse(mixed).single().name)
        assertThrows(IllegalArgumentException::class.java) {
            Geocoding.parse("{\"results\":[false,{\"id\":0}]}")
        }
        val many = (1..12).joinToString(",") { good.replace("2950159", it.toString()) }
        assertEquals((1L..10L).toList(), Geocoding.parse("{\"results\":[$many]}").map { it.id })
    }

    @Test
    fun place_zoneFallbackAndLabelTruncation_preserveProvenanceWithoutSurrogateSplits() {
        val p = candidate.toPlace { _, _ -> error("Should not need fallback") }!!
        assertEquals("open-meteo:2950159", p.geocodeId)
        assertEquals(candidate.lat, p.lat, 0.0)
        assertEquals(
            "Pacific/Auckland",
            candidate.copy(timezone = "invalid").toPlace { _, _ -> "Pacific/Auckland" }!!.zone,
        )
        assertNull(candidate.copy(timezone = null).toPlace { _, _ -> "invalid" })
        val long = candidate.copy(name = "X".repeat(59) + "😀", admin1 = null, country = null).label
        assertEquals(59, long.length)
        assertFalse(Character.isHighSurrogate(long.last()))
        assertNotEquals(candidate.fullLabel, candidate.copy(admin1 = "Other region").fullLabel)
    }

    @Test
    fun cache_expiryBoundaryLruAndClear_followMonotonicClock() {
        var now = 0L
        val cache = SearchCache({ now }, capacity = 2, ttlMillis = 100)
        cache.put("a", listOf(candidate))
        cache.put("b", listOf(candidate))
        assertNotNull(cache.get("a"))
        cache.put("c", listOf(candidate))
        assertNull(cache.get("b"))
        now = 100
        assertNotNull(cache.get("a"))
        now = 101
        assertNull(cache.get("a"))
        cache.clear()
        assertNull(cache.get("c"))
    }

    @Test
    fun search_cacheOfflineRefreshAndEmptyResponse_doNotInventResults() {
        var now = 0L
        var requests = 0
        var reply = GeocodeReply(200, body())
        val search =
            CitySearch(
                GeocodeTransport {
                    requests++
                    reply
                },
                { now },
                { 0 },
            )
        assertTrue(search.search("Berlin") is SearchResult.Results)
        val cached = search.search(" BERLIN ", online = false) as SearchResult.Results
        assertTrue(cached.cached)
        assertEquals(1, requests)
        assertTrue(search.search("Hamburg", online = false) is SearchResult.Failure)
        reply = GeocodeReply(500, byteArrayOf())
        val failed = search.search("Berlin", refresh = true) as SearchResult.Failure
        assertEquals(1, failed.candidates.size)
        now = 1_800_001
        assertTrue(
            (search.search("Berlin", refresh = true) as SearchResult.Failure).candidates.isEmpty()
        )
        reply = GeocodeReply(200, body())
        search.search("Berlin")
        reply = GeocodeReply(200, "{}".toByteArray())
        assertTrue(search.search("Berlin", refresh = true) is SearchResult.Empty)
        assertTrue(search.search("Berlin", online = false) is SearchResult.Failure)
    }

    @Test
    fun search_failedRefreshRechecksExpiryAfterNetworkAndIgnoresObsoleteResponses() {
        var now = 0L
        var current = true
        var slowFailure = false
        val search =
            CitySearch(
                GeocodeTransport {
                    if (slowFailure) {
                        now = 1_800_001
                        throw java.io.IOException("unsafe URL")
                    }
                    GeocodeReply(200, body())
                },
                { now },
                { 0 },
            )
        search.search("Berlin")
        slowFailure = true
        assertTrue(
            (search.search("Berlin", refresh = true) as SearchResult.Failure).candidates.isEmpty()
        )
        val stale =
            CitySearch(
                GeocodeTransport {
                    current = false
                    GeocodeReply(200, body())
                },
                { now },
                { 0 },
            )
        assertTrue(stale.search("Berlin", isCurrent = { current }) is SearchResult.Obsolete)
        current = true
        assertTrue(stale.search("Berlin", online = false) is SearchResult.Failure)
    }

    @Test
    fun search_retryAfterBlocksNetworkButNotCache_andResponseLimitIsEnforced() {
        var now = 0L
        var count = 0
        var limited = false
        val search =
            CitySearch(
                GeocodeTransport {
                    count++
                    if (limited) GeocodeReply(429, byteArrayOf(), "2")
                    else GeocodeReply(200, body())
                },
                { now },
                { 0 },
            )
        search.search("Berlin")
        limited = true
        val failure = search.search("Hamburg") as SearchResult.Failure
        assertEquals(2000L, failure.retryInMillis)
        assertTrue(search.search("Berlin") is SearchResult.Results)
        search.search("Paris")
        assertEquals(2, count)
        now = 2000
        search.search("Paris")
        assertEquals(3, count)
        val big =
            CitySearch(GeocodeTransport { GeocodeReply(200, ByteArray(262145)) }, { 0 }, { 0 })
        assertTrue(big.search("Berlin") is SearchResult.Failure)
    }

    @Test
    fun endpointAndRetryAfter_restrictRedirectTargetsAndHandleHttpDates() {
        assertTrue(Geocoding.allowed(URL(Geocoding.url("Berlin"))))
        for (url in
            listOf(
                "http://geocoding-api.open-meteo.com/v1/search",
                "https://evil.test/v1/search",
                "https://geocoding-api.open-meteo.com/v1/search/other",
                "https://geocoding-api.open-meteo.com:444/v1/search",
                "https://user@geocoding-api.open-meteo.com/v1/search",
            )) assertFalse(Geocoding.allowed(URL(url)))
        assertEquals(1000L, Geocoding.retryDelay("Thu, 01 Jan 1970 00:00:01 GMT", 0))
        assertEquals(0L, Geocoding.retryDelay("0", 0))
        assertNull(Geocoding.retryDelay("bad", 0))
        assertNull(Geocoding.retryDelay("-1", 0))
    }
}
