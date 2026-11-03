package app.drizziq

import org.junit.Assert.*
import org.junit.Test

class PlaceStoreTest {
    private class Memory : PlacePreferences {
        val data = mutableMapOf<String, String>()
        var failNext = false

        override fun get(key: String) = data[key]

        override fun commit(key: String, value: String?): Boolean {
            if (value == null) data.remove(key) else data[key] = value
            if (failNext) {
                failNext = false
                return false
            }
            return true
        }
    }

    @Test
    fun legacyStoredPlaces_loadWithoutProvenanceOrDataMigration() {
        val memory = Memory()
        memory.data["selected"] =
            """{"label":"Munich","lat":48.138108,"lon":11.574726,"zone":"Europe/Berlin"}"""
        val selected = PlaceStore(memory).selected()
        assertEquals(Place.MUNICH, selected)
        assertNull(selected.geocodeId)
    }

    @Test
    fun searchedPlace_andEditedFavourite_surviveNewStoreWithoutProviderLookup() {
        val memory = Memory()
        val store = PlaceStore(memory)
        val original =
            Place("Berlin", 52.5243712345, 13.4105312345, "Europe/Berlin", "open-meteo:2950159")
        val edited = original.relabel("Home")
        store.select(edited)
        store.save(edited)
        val restarted = PlaceStore(memory)
        assertEquals(edited, restarted.selected())
        assertEquals(listOf(edited), restarted.favourites())
        assertEquals("open-meteo:2950159", edited.geocodeId)
        assertTrue(edited.geocodeLabelEdited)
        assertNull(edited.withCoordinates(52.0, 13.0, "Europe/Berlin").geocodeId)
    }

    @Test
    fun favourites_identityUsesCoordinatesNotProviderId() {
        val store = PlaceStore(Memory())
        val a = Place("A", 1.0, 2.0, "UTC", "open-meteo:1")
        store.save(a)
        store.save(a.copy(label = "B", geocodeId = "open-meteo:2"))
        assertEquals(1, store.favourites().size)
        store.save(a.copy(lat = 1.1))
        assertEquals(2, store.favourites().size)
    }

    @Test
    fun select_failedCommitRestoresPreviousSelection_includingInMemoryPreferences() {
        val memory = Memory()
        val store = PlaceStore(memory)
        store.select(Place.MUNICH)
        memory.failNext = true
        assertThrows(IllegalStateException::class.java) {
            store.select(Place("Berlin", 52.52, 13.405))
        }
        assertEquals(Place.MUNICH, store.selected())
        assertEquals(Place.MUNICH, PlaceStore(memory).selected())
    }

    @Test
    fun legacyIncompatibleZones_areRejectedAtReadAndWriteBoundaries() {
        val memory = Memory()
        memory.data["selected"] = """{"label":"Legacy","lat":1.0,"lon":2.0,"zone":"PST"}"""
        assertEquals(Place.MUNICH, PlaceStore(memory).selected())

        val store = PlaceStore(memory)
        store.select(Place.MUNICH)
        assertThrows(IllegalArgumentException::class.java) {
            store.select(Place("Alias", 1.0, 2.0, "PST"))
        }
        assertEquals(Place.MUNICH, store.selected())
        assertEquals(Place.MUNICH, PlaceStore(memory).selected())
    }

    @Test
    fun favourites_skipMalformedRowsIndependentlyAndRetainValidProvenance() {
        val memory = Memory()
        val home = Place("A", 48.1, 11.5, "Europe/Berlin", "open-meteo:1", false).relabel("Home")
        val work = Place("B", 52.5, 13.4, "Europe/Berlin", "open-meteo:2").relabel("Work")
        memory.data["favourites"] =
            """[
                {"label":"Home","lat":48.1,"lon":11.5,"zone":"Europe/Berlin","geocodeId":"open-meteo:1","geocodeLabelEdited":true},
                "not an object",
                {"label":"Broken","lat":"not-a-number","lon":2.0},
                {"label":"Bad zone","lat":3.0,"lon":4.0,"zone":"PST"},
                {"label":"Work","lat":52.5,"lon":13.4,"zone":"Europe/Berlin","geocodeId":"open-meteo:2","geocodeLabelEdited":true}
            ]"""
        val places = PlaceStore(memory).favourites()
        assertEquals(listOf(home, work), places)
        assertEquals("open-meteo:1", places.first().geocodeId)
        assertTrue(places.first().geocodeLabelEdited)
    }

    @Test
    fun favourites_doNotTruncateValidRowsAfterMalformedEntries() {
        val memory = Memory()
        val valid = (1..12).map { Place("P$it", it.toDouble(), it.toDouble(), "UTC") }
        val rows = valid.mapIndexed { i, p ->
            val row = """{"label":"${p.label}","lat":${p.lat},"lon":${p.lon},"zone":"UTC"}"""
            if (i == 0) "$row, null, {\"label\":\"x\"}" else row
        }
        memory.data["favourites"] = rows.joinToString(prefix = "[", postfix = "]")
        assertEquals(valid, PlaceStore(memory).favourites())
    }

    @Test
    fun delete_failedCommit_keepsFavouritesAndProvenance_andRetrySucceeds() {
        val memory = Memory()
        val store = PlaceStore(memory)
        val home = Place("A", 1.0, 2.0, "UTC", "open-meteo:1", false).relabel("Home")
        val work = Place("B", 3.0, 4.0, "Europe/Berlin")
        store.save(home)
        store.save(work)

        memory.failNext = true
        assertFalse(store.delete(home))
        assertEquals(listOf(home, work), store.favourites())
        assertEquals(listOf(home, work), PlaceStore(memory).favourites())
        assertEquals("open-meteo:1", store.favourites().first().geocodeId)

        assertTrue(store.delete(home))
        assertEquals(listOf(work), store.favourites())
        assertEquals(listOf(work), PlaceStore(memory).favourites())
    }
}
