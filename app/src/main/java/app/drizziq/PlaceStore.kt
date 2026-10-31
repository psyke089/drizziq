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

    private fun json(p: Place) =
        JSONObject()
            .put("label", p.label)
            .put("lat", p.lat)
            .put("lon", p.lon)
            .put("zone", p.zone)
            .put("geocodeId", p.geocodeId)
            .put("geocodeLabelEdited", p.geocodeLabelEdited)

    private fun place(j: JSONObject) =
        Place(
            j.getString("label"),
            j.getDouble("lat"),
            j.getDouble("lon"),
            j.optString("zone", "Europe/Berlin"),
            (j.opt("geocodeId") as? String)?.takeIf { it.isNotBlank() },
            j.optBoolean("geocodeLabelEdited", false),
        )

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

    fun favourites(): List<Place> = runCatching {
        val a = JSONArray(prefs.get("favourites") ?: "[]")
        (0 until a.length()).map { place(a.getJSONObject(it)) }
    }
        .getOrDefault(emptyList())

    fun save(p: Place) {
        write(favourites().filterNot { it.lat == p.lat && it.lon == p.lon } + p)
    }

    fun delete(p: Place) = write(favourites().filterNot { it == p })

    private fun write(all: List<Place>) {
        val a = JSONArray()
        all.forEach { a.put(json(it)) }
        commit("favourites", a.toString())
    }
}
