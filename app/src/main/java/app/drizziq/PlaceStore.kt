package app.drizziq

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

interface PlacePreferences {
    fun get(key: String): String?

    fun commit(key: String, value: String?): Boolean
}

private class AndroidPlacePreferences(context: Context) : PlacePreferences {
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)

    override fun get(key: String) = prefs.getString(key, null)

    override fun commit(key: String, value: String?) = prefs.edit().putString(key, value).commit()
}

class PlaceStore(private val prefs: PlacePreferences) {
    constructor(context: Context) : this(AndroidPlacePreferences(context))

    private fun json(p: Place): JSONObject {
        val zone = requireNotNull(Zones.canonical(p.zone)) { "Invalid time zone" }
        return JSONObject()
            .put("label", p.label)
            .put("lat", p.lat)
            .put("lon", p.lon)
            .put("zone", zone)
            .put("geocodeId", p.geocodeId)
            .put("geocodeLabelEdited", p.geocodeLabelEdited)
    }

    private fun place(j: JSONObject): Place {
        val zone =
            requireNotNull(Zones.canonical(j.optString("zone", "Europe/Berlin"))) {
                "Invalid time zone"
            }
        return Place(
            j.getString("label"),
            j.getDouble("lat"),
            j.getDouble("lon"),
            zone,
            (j.opt("geocodeId") as? String)?.takeIf { it.isNotBlank() },
            j.optBoolean("geocodeLabelEdited", false),
        )
    }

    fun selected(): Place = runCatching {
        place(JSONObject(prefs.get("selected")!!))
    }
        .getOrDefault(Place.MUNICH)

    private fun commit(key: String, value: String) {
        val previous = prefs.get(key)
        if (!prefs.commit(key, value)) {
            prefs.commit(key, previous)
            error("Location could not be saved. Please retry.")
        }
    }

    fun select(p: Place) {
        commit("selected", json(p).toString())
    }

    fun favourites(): List<Place> {
        val a =
            runCatching { JSONArray(prefs.get("favourites") ?: "[]") }.getOrNull()
                ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            runCatching { place(a.getJSONObject(i)) }.getOrNull()
        }
    }

    fun save(p: Place) {
        write(favourites().filterNot { it.lat == p.lat && it.lon == p.lon } + p)
    }

    /** False when the commit failed and the previous list was restored; the caller can retry. */
    fun delete(p: Place): Boolean =
        try {
            write(favourites().filterNot { it.lat == p.lat && it.lon == p.lon })
            true
        } catch (error: Exception) {
            false
        }

    private fun write(all: List<Place>) {
        val a = JSONArray()
        all.forEach { a.put(json(it)) }
        commit("favourites", a.toString())
    }
}
