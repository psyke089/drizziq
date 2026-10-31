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
}
