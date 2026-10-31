package app.drizziq

data class StationHit(val station: Station, val distanceKm: Double)

/** Immutable, MOSMIX-only catalogue. IDs are opaque and exact ties use their lexical order. */
class StationIndex(stations: List<Station>) {
    private val points = stations.toList()

    init {
        val ids = HashSet<String>()
        for (s in points) {
            require(
                s.id.matches(VALID_ID) &&
                    s.name.isNotBlank() &&
                    validCoordinates(s.lat, s.lon) &&
                    (s.elevation == null || s.elevation.isFinite())
            ) {
                "Invalid MOSMIX catalogue row"
            }
            require(ids.add(s.id)) { "Duplicate MOSMIX ID" }
        }
    }

    fun nearest(place: Place): StationHit? {
        var best: StationHit? = null
        for (station in points) {
            val distance = station.distance(place)
            val previous = best
            if (
                previous == null ||
                    distance < previous.distanceKm ||
                    (distance == previous.distanceKm && station.id < previous.station.id)
            )
                best = StationHit(station, distance)
        }
        return best
    }

    fun nearestInCoverage(place: Place, maxKm: Double = 50.0): StationHit? {
        require(maxKm.isFinite() && maxKm >= 0) { "Invalid coverage radius" }
        return nearest(place)?.takeIf { it.distanceKm <= maxKm }
    }

    companion object {
        private val VALID_ID = Regex("[A-Za-z0-9]+")

        fun fromTsv(text: String): StationIndex {
            val rows =
                text
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .map { line ->
                        val p = line.split('\t')
                        require(p.size == 5) { "Invalid station columns" }
                        Station(
                            p[0],
                            p[1],
                            p[2].toDouble(),
                            p[3].toDouble(),
                            p[4].takeIf { it.isNotBlank() }?.toDouble(),
                        )
                    }
                    .toList()
            require(rows.isNotEmpty()) { "Empty station catalogue" }
            return StationIndex(rows)
        }
    }
}
