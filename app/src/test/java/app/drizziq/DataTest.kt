package app.drizziq

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class DataTest {
    @Test fun coordinatesRejectNonFiniteAndOutOfRange() {
        assertTrue(validCoordinates(-90.0,180.0))
        assertFalse(validCoordinates(Double.NaN,0.0))
        assertFalse(validCoordinates(48.0,Double.POSITIVE_INFINITY))
        assertFalse(validCoordinates(91.0,11.0))
        assertFalse(validCoordinates(48.0,-181.0))
    }
    @Test fun liveDwdKmzRetainsTimesAndMissingHumidity() {
        val f=Mosmix.parseKmz(javaClass.getResourceAsStream("/munich-20261022.kmz")!!)
        assertEquals("10865",f.station.id)
        assertEquals(Times.parse("2026-10-22T15:00:00.000Z"),f.issue)
        assertTrue(f.hours.size>=168)
        assertTrue(f.hours.take(24).all {it.temperature!=null && it.wind!=null})
        assertTrue(f.hours.all {it.humidity==null})
        assertTrue(f.station.distance(Place.MUNICH)<6)
        assertEquals(7,f.days(f.hours.first().time,"Europe/Berlin").size)
    }
    @Test fun missingValuesAreAbsentAndNoCurrentValueIsExtrapolated() {
        val xml="""<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:d="urn:test"><Document><d:IssueTime>2026-10-22T15:00:00Z</d:IssueTime><d:TimeStep>2026-10-22T16:00:00Z</d:TimeStep><Placemark><name>10865</name><description>Munich</description><Point><coordinates>11.533,48.167,515</coordinates></Point><d:Forecast d:elementName="TTT"><d:value>-</d:value></d:Forecast><d:Forecast d:elementName="RR1c"><d:value>0.00</d:value></d:Forecast></Placemark></Document></kml>"""
        val f=Mosmix.parseXml(xml.byteInputStream())
        assertNull(f.hours.single().temperature)
        assertEquals(0.0,f.hours.single().get("RR1c")!!,0.0)
        assertNull(f.current(Times.parse("2026-10-23T16:00:00Z")))
        assertNull(f.days(f.issue,"UTC").single().rain)
    }
    @Test fun dailyTotalsRespectThe25HourAutumnDay() {
        val start=Times.parse("2026-10-24T22:00:00Z")
        val hours=(1..25).map {Hour(start+it*3600000L,mapOf("RR1c" to 1.0,"SunD1" to 60.0,"TTT" to 280.0))}
        val f=Forecast(Station("t","t",0.0,0.0,null),start,hours)
        val day=f.days(start,"Europe/Berlin").single()
        assertTrue(day.complete);assertEquals(25.0,day.rain!!,0.0001)
        val incomplete=f.copy(hours=hours.filterIndexed {i,_->i!=5}).days(start,"Europe/Berlin").single()
        assertFalse(incomplete.complete);assertNull(incomplete.rain)
    }
    @Test fun currentOdimUint32DataAndProjectionDecode() {
        val radar=Odim.decode(javaClass.getResourceAsStream("/rv-observed-20261022.h5")!!.readBytes())
        assertEquals(1100,radar.grid.width);assertEquals(1200,radar.grid.height)
        assertEquals(Times.parse("2026-10-22T20:00:00Z"),radar.time)
        assertEquals(-500.0,radar.grid.left,.02);assertEquals(500.0,radar.grid.top,.02)
        assertTrue(radar.grid.contains(Place.MUNICH))
        assertFalse(radar.grid.contains(Place("New York",40.7,-74.0)))
        assertTrue(radar.colors.any {it==0});assertTrue(radar.colors.any {it==RadarPalette.colors[1]})
        val (x,y)=RadarProjection.project(48.138108,11.574726)
        val (lat,lon)=RadarProjection.unproject(x,y)
        assertEquals(48.138108,lat,.000001);assertEquals(11.574726,lon,.000001)
    }
    @Test fun forecastFrameUsesActualValidityTime() {
        val radar=Odim.decode(javaClass.getResourceAsStream("/rv-forecast-20261022.h5")!!.readBytes())
        assertEquals(Times.parse("2026-10-22T22:15:00Z"),radar.time)
    }
    @Test(expected=IllegalArgumentException::class) fun truncatedTarIsRejected() {
        val header=ByteArray(512);"test".toByteArray().copyInto(header);"00000000010".toByteArray().copyInto(header,124)
        Tar.read((header+byteArrayOf(1,2)).inputStream()) {fail("Truncated entry was accepted")}
    }
}
