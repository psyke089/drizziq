package app.drizziq

import android.content.Context

/** CC0 tz-lookup quadtree; see AGENTS.md. Approximate at borders; user can override. */
class ZoneLookup(context: Context) {
    private val tree = context.assets.open("timezones.tree").bufferedReader().use { it.readText() }
    private val zones = context.assets.open("timezones.txt").bufferedReader().use { it.readLines() }

    fun at(lat: Double, lon: Double): String {
        require(validCoordinates(lat, lon))
        if (lat >= 90) return "Etc/GMT"
        var node = -1
        var x = (180 + lon) * 48 / 360.00000000000006
        var y = (90 - lat) * 24 / 180.00000000000003
        var u = x.toInt()
        var v = y.toInt()
        fun value(pos: Int) = tree[pos].code * 56 + tree[pos + 1].code - 1995
        var index = value(v * 96 + u * 2)
        while (index + zones.size < 3136) {
            node += index + 1
            x = ((x - u) * 2) % 2
            y = ((y - v) * 2) % 2
            u = x.toInt()
            v = y.toInt()
            index = value(node * 8 + v * 4 + u * 2 + 2304)
        }
        return zones[index + zones.size - 3136]
    }
}
