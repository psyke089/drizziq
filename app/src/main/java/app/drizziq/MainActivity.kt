package app.drizziq

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity:Activity() {
    private lateinit var repo:Repository
    private lateinit var store:PlaceStore
    private lateinit var zones:ZoneLookup
    private lateinit var root:LinearLayout
    private lateinit var content:FrameLayout
    private lateinit var placeTitle:TextView
    private lateinit var coordinates:TextView
    private lateinit var clock:TextView
    private lateinit var connection:TextView
    private lateinit var forecastTab:Button
    private lateinit var radarTab:Button
    private lateinit var forecastScroll:ScrollView
    private lateinit var radarPanel:LinearLayout
    private lateinit var map:RadarMap
    private lateinit var radarStatus:TextView
    private lateinit var frameTime:TextView
    private lateinit var frameType:TextView
    private lateinit var radarMeta:TextView
    private lateinit var timeline:RadarTimeline
    private lateinit var playButton:Button
    private lateinit var windowLabel:TextView
    private lateinit var mapStatus:TextView
    private val handler=Handler(Looper.getMainLooper())
    private val decoder=Executors.newSingleThreadExecutor()
    private val frames=object:android.util.LruCache<String,Bitmap>(3) {}
    private var sequence:RadarSequence?=null
    private var frameIndex=0
    private var decodeToken=0
    private var playing=false
    private var active=false
    private var radarVisible=false
    private var ready=false
    private var dead=false
    private var forecastVersion:Reading<Forecast>?=null
    private var lastForecastPlace:Place?=null
    private var lastRefresh=0L
    private val networkCallback=object:ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network:Network) = changedNetwork()
        override fun onLost(network:Network) = changedNetwork()
        override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities) = changedNetwork()
    }
    private fun changedNetwork() { handler.post { if(ready&&!dead) { val was=repo.online;val now=repo.connection();if(!was&&now)refresh() } } }
    private val tick=object:Runnable {
        override fun run() {
            if(!active || dead)return
            repo.age();updateClock();render()
            if(repo.online && System.currentTimeMillis()-lastRefresh>300000)refresh()
            handler.postDelayed(this,30000)
        }
    }
    private val advance=Runnable {
        if(playing && active && radarVisible) sequence?.let { requestFrame((frameIndex+1)%it.frames.size) }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        store=PlaceStore(this);zones=ZoneLookup(this)
        repo=Repository(this) { if(ready)render() }
        createLayout()
        ready=true
        repo.connection();choose(store.selected(),false)
        (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(networkCallback)
        showRadar(savedInstanceState?.getBoolean("radar")?:false)
    }
    private fun createLayout() {
        root=column().apply { setBackgroundColor(Hud.bg);setPadding(dp(20),dp(12),dp(20),dp(12)) }
        if(android.os.Build.VERSION.SDK_INT>=30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        root.setOnApplyWindowInsetsListener { v,insets ->
            v.setPadding(dp(20),insets.systemWindowInsetTop+dp(12),dp(20),insets.systemWindowInsetBottom+dp(12));insets
        }
        setContentView(root)
        root.requestApplyInsets()
        val header=row()
        header.addView(WeatherGlyph(this,logo=true),LinearLayout.LayoutParams(dp(36),dp(36)))
        val wordmark=label("DRIZZIQ",19f).apply { letterSpacing=.22f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);gravity=Gravity.CENTER_VERTICAL }
        header.addView(wordmark,LinearLayout.LayoutParams(0,dp(36),1f).apply {leftMargin=dp(10)})
        connection=label("CONNECTING",9f,Hud.muted,true);header.addView(connection)
        root.addView(header);root.space(18)
        val location=row(); val placeColumn=column()
        placeTitle=label("Munich",28f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);maxLines=2 }
        coordinates=label("",11f,Hud.muted,true)
        placeColumn.addView(placeTitle);placeColumn.space(6);placeColumn.addView(coordinates)
        location.addView(placeColumn,LinearLayout.LayoutParams(0,-2,1f))
        location.addView(action("PLACES","Locations and favourites") { locations() })
        root.addView(location);root.space(8)
        clock=label("",10f,Hud.muted,true);root.addView(clock);root.space(16)
        val nav=row()
        forecastTab=action("01  FORECAST") { showRadar(false) }
        radarTab=action("02  RADAR") { showRadar(true) }
        nav.addView(forecastTab,LinearLayout.LayoutParams(0,dp(48),1f).apply {rightMargin=dp(6)})
        nav.addView(radarTab,LinearLayout.LayoutParams(0,dp(48),1f).apply {leftMargin=dp(6)})
        root.addView(nav);root.space(12)
        content=FrameLayout(this);root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        forecastScroll=ScrollView(this).apply { isFillViewport=true;clipToPadding=false }
        content.addView(forecastScroll,FrameLayout.LayoutParams(-1,-1))
        createRadar()
    }
    private fun section(title:String,tag:String?=null):LinearLayout = row().apply {
        addView(label(title,11f,Hud.muted,true),LinearLayout.LayoutParams(0,-2,1f))
        if(tag!=null)addView(label(tag,9f,Hud.cyan,true))
    }
    private fun buildForecast() {
        val scroll=forecastScroll.scrollY
        val data=repo.forecast; val forecast=data.data;val place=repo.place;val state=data.state
        val body=column();forecastScroll.removeAllViews();forecastScroll.addView(body)
        val status=section("DWD  /  MOSMIX",state.name)
        (status.getChildAt(1) as TextView).setTextColor(Hud.color(state))
        body.addView(status);body.space(12)
        if(data.message!=null) {body.addView(label(data.message,12f,if(state==Freshness.ERROR)Hud.red else Hud.amber));body.space(10)}
        val now=System.currentTimeMillis()
        if(forecast==null) {
            val empty=column().apply {pad(24);background=panel()}
            empty.addView(WeatherGlyph(this,logo=true),LinearLayout.LayoutParams(dp(100),dp(100)))
            empty.space(20);empty.addView(label(if(state==Freshness.ERROR)"No forecast received" else "Receiving the atmosphere",24f))
            empty.space(12);empty.addView(label("Direct from Deutscher Wetterdienst.\nYour selected coordinates stay on this device.",14f,Hud.muted))
            empty.space(24);empty.addView(action("RETRY") {refresh()});body.addView(empty);return
        }
        val current=forecast.current(now)
        val hero=column().apply {pad(20);background=panel()}
        hero.addView(section("CURRENT FORECAST",state.name).apply {(getChildAt(1) as TextView).setTextColor(Hud.color(state))})
        if(current!=null) {
            val main=row()
            val value=column()
            current.temperature?.let { value.addView(label("${number(it,1)}°",76f).apply {typeface=Typeface.create("sans-serif-light",Typeface.NORMAL);contentDescription="Temperature ${number(it,1)} degrees Celsius"}) }
            weatherLabel(current.condition)?.let {value.addView(label(it,19f,Hud.cyan))}
            main.addView(value,LinearLayout.LayoutParams(0,-2,1f))
            main.addView(WeatherGlyph(this,current.condition),LinearLayout.LayoutParams(dp(98),dp(98)))
            hero.addView(main);hero.space(14)
            current.wind?.let { hero.addView(label("WIND  ${number(it,0)} km/h"+(current.get("DD")?.let {d->"  ·  from ${number(d,0)}°"}?:""),12f,Hud.ink,true)) }
            hero.space(8);hero.addView(label("Valid ${Times.format(current.time,"EEE dd MMM, HH:mm z",place.zone)}",11f,Hud.muted))
        } else {
            hero.space(20);hero.addView(label("No current forecast hour",23f));hero.space(8)
            hero.addView(label("The last successful forecast remains below.",13f,Hud.amber))
        }
        hero.space(14)
        hero.addView(label("${forecast.station.name}  /  ${forecast.station.id}",10f,Hud.muted,true))
        hero.space(6)
        hero.addView(label("Nearest forecast point · ${number(forecast.station.distance(place),1)} km away"+(forecast.station.elevation?.let {" · ${number(it,0)} m elevation"}?:""),11f,Hud.muted))
        hero.space(5);hero.addView(label("Station ${coords(forecast.station.lat,forecast.station.lon)}",10f,Hud.muted,true))
        body.addView(hero);body.space(22)
        body.addView(section("7-DAY OUTLOOK","SWIPE TO EXPLORE"));body.space(10)
        val daily=HorizontalScrollView(this).apply {isHorizontalScrollBarEnabled=false}
        val cards=row().apply {gravity=Gravity.TOP}
        forecast.days(now,place.zone).forEach {day ->
            val card=column().apply {pad(14);background=panel()}
            card.addView(label(Times.format(day.time,"EEE",place.zone).uppercase(),12f,Hud.cyan,true));card.space(4)
            card.addView(label(Times.format(day.time,"dd MMM",place.zone),11f,Hud.muted));card.space(8)
            card.addView(WeatherGlyph(this,day.condition),LinearLayout.LayoutParams(dp(64),dp(62)))
            day.high?.let {card.addView(label("${number(it,0)}° / ${day.low?.let {lo->number(lo,0)}?:"—"}°",24f))}
            card.space(6);weatherLabel(day.condition)?.let {card.addView(label(it,12f,Hud.muted).apply {minHeight=dp(30)})}
            card.space(8)
            day.rain?.let {card.addView(label("RAIN  ${number(it,1)} mm",11f,Hud.ink,true));card.space(7)}
            day.wind?.let {card.addView(label("WIND  ${number(it,0)} km/h",11f,Hud.ink,true));card.space(7)}
            day.sun?.let {card.addView(label("SUN   ${number(it,1)} h",11f,Hud.ink,true));card.space(7)}
            card.addView(label(if(day.complete)"HOURLY RANGE" else "PARTIAL DAY",9f,Hud.muted,true));card.space(8)
            card.addView(label(state.name,9f,Hud.color(state),true))
            cards.addView(card,LinearLayout.LayoutParams(dp(156),-2).apply {rightMargin=dp(10)})
        }
        daily.addView(cards);body.addView(daily)
        body.space(10);body.addView(label("High / low from available hourly temperatures; wind is the hourly maximum. Rain and sunshine totals appear only for complete local days.",11f,Hud.muted))
        body.space(22)
        val hours=forecast.hours.filter { it.time>=now }.take(48)
        body.addView(section("HOURLY DETAIL","${hours.size} HOURS"));body.space(10)
        body.addView(TemperatureTrace(this,hours),LinearLayout.LayoutParams(-1,dp(64)))
        body.space(10)
        val horizontal=HorizontalScrollView(this)
        val table=column()
        val humidity=hours.any {it.humidity!=null}
        fun tableRow(values:List<String>,head:Boolean=false):LinearLayout = row().apply {
            setPadding(0,dp(12),0,dp(12));if(head)background=panel()
            values.forEachIndexed {i,v -> addView(label(v,if(head)10f else 12f,if(head)Hud.muted else if(i==1)Hud.cyan else Hud.ink,true),LinearLayout.LayoutParams(dp(if(i==0)110 else if(i==values.lastIndex)65 else 80),-2)) }
        }
        table.addView(tableRow(listOf("LOCAL TIME","°C","RAIN mm","km/h","FROM °")+(if(humidity)listOf("RH %") else emptyList())+listOf("STATE"),true))
        hours.forEach {h ->
            table.addView(tableRow(listOf(Times.format(h.time,"EEE HH:mm",place.zone),h.temperature?.let {number(it,1)}?:"—",h.get("RR1c")?.let {number(it,1)}?:"—",h.wind?.let {number(it,0)}?:"—",h.get("DD")?.let {number(it,0)}?:"—")+(if(humidity)listOf(h.humidity?.let {number(it,0)}?:"—")else emptyList())+state.name))
            table.addView(View(this).apply {setBackgroundColor(Hud.line)},LinearLayout.LayoutParams(-1,1))
        }
        horizontal.addView(table);body.addView(horizontal);body.space(12)
        body.addView(label("— = no DWD value. Rain refers to the preceding hour."+if(!humidity)" Relative humidity is not supplied by this forecast and is omitted." else "",11f,Hud.muted))
        body.space(20)
        val footer=column().apply {pad(14);background=panel()}
        footer.addView(label("DATA TRANSMISSION  /  ${state.name}",10f,Hud.color(state),true));footer.space(8)
        footer.addView(label("Issue  ${Times.format(forecast.issue,"dd MMM HH:mm")} UTC\nValid  ${Times.format(forecast.hours.first().time,"dd MMM HH:mm")} – ${Times.format(forecast.hours.last().time,"dd MMM HH:mm")} UTC",11f,Hud.muted,true))
        data.fetched?.let { footer.space(6);footer.addView(label("Received ${Times.format(it,"dd MMM HH:mm:ss")} UTC",11f,Hud.muted,true)) }
        footer.space(10);footer.addView(action(if(data.refreshing)"REFRESHING…" else "REFRESH DWD") {refresh()}.apply {isEnabled=!data.refreshing})
        body.addView(footer);body.space(12)
        forecastScroll.post {forecastScroll.scrollTo(0,scroll)}
    }
    private fun createRadar() {
        radarPanel=column()
        radarStatus=label("LOADING · DWD RADAR",10f,Hud.muted,true);radarPanel.addView(radarStatus);radarPanel.space(8)
        val frameHeader=row()
        frameTime=label("Waiting for DWD",22f);frameHeader.addView(frameTime,LinearLayout.LayoutParams(0,-2,1f))
        frameType=label("",9f,Hud.cyan,true);frameHeader.addView(frameType)
        radarPanel.addView(frameHeader);radarPanel.space(8)
        map=RadarMap(this)
        radarPanel.addView(map,LinearLayout.LayoutParams(-1,0,1f))
        val attribution=label("© OpenStreetMap contributors",10f,Hud.muted).apply {
            gravity=Gravity.END;setPadding(0,dp(4),0,dp(4));contentDescription="OpenStreetMap attribution and licence"
            setOnClickListener {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright")))}
        }
        radarPanel.addView(attribution)
        mapStatus=label("BASEMAP · CONNECTING",9f,Hud.muted,true);radarPanel.addView(mapStatus)
        map.onMapStatus={mapStatus.text=it}
        radarPanel.space(6)
        val controls=row()
        playButton=action("PLAY","Play or pause radar animation") {setPlaying(!playing)}
        controls.addView(playButton,LinearLayout.LayoutParams(0,dp(48),1f).apply {rightMargin=dp(6)})
        controls.addView(action("−","Zoom out") {map.zoomBy(-1.0)},LinearLayout.LayoutParams(dp(48),dp(48)).apply {rightMargin=dp(6)})
        controls.addView(action("+","Zoom in") {map.zoomBy(1.0)},LinearLayout.LayoutParams(dp(48),dp(48)).apply {rightMargin=dp(6)})
        controls.addView(action("CENTER","Recenter on selected location") {map.recenter()},LinearLayout.LayoutParams(0,dp(48),1f))
        radarPanel.addView(controls)
        timeline=RadarTimeline(this).apply {onSelect={setPlaying(false);requestFrame(it)}}
        radarPanel.addView(timeline,LinearLayout.LayoutParams(-1,dp(58)))
        windowLabel=label("Observations → available forecast",10f,Hud.muted,true);radarPanel.addView(windowLabel)
        radarPanel.space(8)
        val legend=row()
        for(i in 1..5) {
            val cell=column();cell.addView(View(this).apply {setBackgroundColor(RadarPalette.colors[i])},LinearLayout.LayoutParams(-1,dp(4)))
            cell.space(4);cell.addView(label("${RadarPalette.limits[i]}",9f,Hud.muted,true))
            legend.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=dp(3)})
        }
        legend.addView(label("mm / 5 min",9f,Hud.muted,true));radarPanel.addView(legend)
        radarPanel.space(5);radarPanel.addView(label("<0.1: faint teal · hatched: no radar data",9f,Hud.muted))
        radarPanel.space(8)
        radarMeta=label("",10f,Hud.muted).apply {setOnClickListener {repo.refreshRadar()};contentDescription="Radar status; tap to refresh"}
        radarPanel.addView(radarMeta)
        content.addView(radarPanel,FrameLayout.LayoutParams(-1,-1))
    }
    private fun renderRadar() {
        val reading=repo.radar
        radarStatus.text="${reading.state.name}  /  DWD RV  ·  ${if(repo.online)"CONNECTED" else "OFFLINE"}"
        radarStatus.setTextColor(Hud.color(reading.state));map.online=repo.online;map.invalidate()
        val data=reading.data
        if(data!=sequence && data!=null) {
            val oldTime=sequence?.frames?.getOrNull(frameIndex)?.time
            sequence=data;timeline.frames=data.frames
            frameIndex=if(oldTime==null)data.frames.indexOfLast {it.lead==0}.coerceAtLeast(0) else data.frames.indices.minBy {abs(data.frames[it].time-oldTime)}
            requestFrame(frameIndex)
        }
        playButton.isEnabled=data!=null
        radarMeta.text=listOfNotNull(
            data?.let {"Issue ${Times.format(it.issue,"dd MMM HH:mm")} UTC · ${it.frames.size} frames"},
            reading.fetched?.let {"Received ${Times.format(it,"HH:mm:ss")} UTC · tap to refresh"},
            if(reading.refreshing) "Receiving DWD frames…" else reading.message
        ).joinToString("\n")
        if(data==null)frameTime.text=if(reading.state==Freshness.ERROR)"Radar unavailable" else "Receiving radar…"
    }
    private fun requestFrame(index:Int) {
        val seq=sequence?:return;val frame=seq.frames.getOrNull(index)?:return
        val token=++decodeToken;handler.removeCallbacks(advance)
        val display:(Bitmap)->Unit={image ->
            if(!dead&&token==decodeToken) {
                map.show(frame,image);frameIndex=index;timeline.selected=index
                frameTime.text=Times.format(frame.time,"dd MMM · HH:mm",repo.place.zone)
                frameType.text=if(frame.lead>0)"FORECAST +${frame.lead}m" else "OBSERVED"
                val previous=seq.frames.getOrNull(index-1)
                val gap=previous!=null&&frame.time-previous.time>300000
                windowLabel.text="${Times.format(seq.frames.first().time,"HH:mm",repo.place.zone)}  —  ${Times.format(seq.frames.last().time,"HH:mm z",repo.place.zone)}${if(gap)" · GAP BEFORE FRAME" else ""}"
                timeline.contentDescription="Radar frame ${Times.format(frame.time,"dd MMM HH:mm z",repo.place.zone)}, ${frameType.text}"
                if(playing&&active&&radarVisible)handler.postDelayed(advance,if(index==seq.frames.lastIndex)1400 else 700)
            }
        }
        frames.get(frame.file)?.let {display(it);return}
        frameType.text="LOADING FRAME"
        decoder.execute {
            val image=BitmapFactory.decodeFile(frame.file)
            handler.post {
                if(!dead) {
                    if(image!=null) {frames.put(frame.file,image);display(image)}
                    else if(token==decodeToken) {setPlaying(false);frameType.text="FRAME ERROR";radarMeta.text="Cached frame could not be read · tap to refresh"}
                }
            }
        }
    }
    private fun setPlaying(value:Boolean) {playing=value;playButton.text=if(value)"PAUSE" else "PLAY";handler.removeCallbacks(advance);if(value)handler.postDelayed(advance,700)}
    private fun showRadar(value:Boolean) {
        radarVisible=value;radarPanel.visibility=if(value)View.VISIBLE else View.GONE;forecastScroll.visibility=if(value)View.GONE else View.VISIBLE
        forecastTab.background=panel(if(!value)Hud.line else Hud.panel,if(!value)Hud.cyan else Hud.line)
        radarTab.background=panel(if(value)Hud.line else Hud.panel,if(value)Hud.cyan else Hud.line)
        if(!value)setPlaying(false)
    }
    private fun updateClock() {clock.text="LOCAL  ${Times.format(System.currentTimeMillis(),"EEE HH:mm z",repo.place.zone)}  /  ${repo.place.zone}"}
    private fun render() {
        repo.age()
        placeTitle.text=repo.place.label;coordinates.text=coords(repo.place.lat,repo.place.lon)
        connection.text=if(repo.online)"DWD / ONLINE" else "OFFLINE"
        connection.setTextColor(if(repo.online)Hud.cyan else Hud.amber)
        updateClock()
        if(forecastVersion!=repo.forecast || lastForecastPlace!=repo.place) {forecastVersion=repo.forecast;lastForecastPlace=repo.place;buildForecast()}
        renderRadar()
    }
    private fun refresh() {lastRefresh=System.currentTimeMillis();repo.connection();repo.refreshForecast();repo.refreshRadar()}
    private fun choose(place:Place,persist:Boolean=true) {
        if(persist)store.select(place)
        map.place=place;setPlaying(false);lastRefresh=System.currentTimeMillis();repo.select(place);updateClock()
    }
    private fun locations() {
        val body=column().apply {pad(20)}
        body.addView(label("SELECTED COORDINATES",10f,Hud.muted,true));body.space(8)
        body.addView(label(repo.place.label,22f));body.space(5);body.addView(label(coords(repo.place.lat,repo.place.lon),12f,Hud.muted,true));body.space(16)
        val dialog=AlertDialog.Builder(this).setTitle("Places").setView(ScrollView(this).apply {addView(body)}).setNegativeButton("Close",null).create()
        body.addView(action("ENTER COORDINATES") {dialog.dismiss();locationEntry()});body.space(8)
        body.addView(action("SAVE CURRENT AS FAVOURITE") {dialog.dismiss();saveFavourite()});body.space(22)
        body.addView(label("FAVOURITES",11f,Hud.cyan,true));body.space(10)
        val favourites=store.favourites()
        if(favourites.isEmpty())body.addView(label("Saved places will appear here.",13f,Hud.muted))
        favourites.forEach {place ->
            val card=column().apply {pad(12);background=panel()}
            card.addView(label(place.label,17f));card.space(4);card.addView(label(coords(place.lat,place.lon),10f,Hud.muted,true));card.space(8)
            val actions=row();actions.addView(action("SELECT","Select favourite ${place.label}") {choose(place);dialog.dismiss()},LinearLayout.LayoutParams(0,dp(48),1f))
            actions.addView(action("DELETE","Delete favourite ${place.label}") {store.delete(place);dialog.dismiss();locations()},LinearLayout.LayoutParams(0,dp(48),1f).apply {leftMargin=dp(8)})
            card.addView(actions);body.addView(card);body.space(10)
        }
        body.space(12);body.addView(action("DATA & LICENCES") {dialog.dismiss();notices()})
        dialog.show()
    }
    private fun notices() {
        val text=label(assets.open("notices.txt").bufferedReader().use {it.readText()},13f,Hud.muted).apply {pad(20);android.text.util.Linkify.addLinks(this,android.text.util.Linkify.WEB_URLS);movementMethod=android.text.method.LinkMovementMethod.getInstance()}
        AlertDialog.Builder(this).setTitle("Data & licences").setView(ScrollView(this).apply {addView(text)}).setPositiveButton("Close",null).show()
    }
    private fun field(body:LinearLayout,title:String,value:String,numeric:Boolean=false):EditText {
        body.addView(label(title,11f,Hud.muted,true));body.space(4)
        val input=EditText(this).apply {
            setText(value);setTextColor(Hud.ink);textSize=16f;setSingleLine(true);contentDescription=title
            inputType=if(numeric)InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
            setPadding(dp(10),dp(8),dp(10),dp(8));background=panel();minimumHeight=dp(48)
        }
        body.addView(input);body.space(14);return input
    }
    private fun locationEntry() {
        val body=column().apply {pad(20)}
        val name=field(body,"LABEL (OPTIONAL)","")
        val lat=field(body,"LATITUDE · −90 TO 90",repo.place.lat.toString(),true)
        val lon=field(body,"LONGITUDE · −180 TO 180",repo.place.lon.toString(),true)
        val zone=field(body,"TIME ZONE (OPTIONAL IANA ID)","")
        body.addView(label("Time zone is estimated offline when blank. Override near a time-zone border, e.g. Europe/Berlin. No GPS permission is used.",12f,Hud.muted))
        val dialog=AlertDialog.Builder(this).setTitle("Enter location").setView(ScrollView(this).apply {addView(body)}).setNegativeButton("Cancel",null).setPositiveButton("Use location",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a=lat.text.toString().trim().replace(',','.').toDoubleOrNull()
            val b=lon.text.toString().trim().replace(',','.').toDoubleOrNull()
            if(a==null || !a.isFinite() || a !in -90.0..90.0) {lat.error="Latitude must be between −90 and 90";return@setOnClickListener}
            if(b==null || !b.isFinite() || b !in -180.0..180.0) {lon.error="Longitude must be between −180 and 180";return@setOnClickListener}
            val tz=zone.text.toString().trim().ifBlank {zones.at(a,b)}
            if(tz !in TimeZone.getAvailableIDs()) {zone.error="Use a valid IANA zone, such as Europe/Berlin";return@setOnClickListener}
            choose(Place(name.text.toString().trim().take(60).ifBlank {"${number(a,3)}, ${number(b,3)}"},a,b,tz));dialog.dismiss()
        }};dialog.show()
    }
    private fun saveFavourite() {
        val body=column().apply {pad(20)};val name=field(body,"FAVOURITE LABEL",repo.place.label)
        val dialog=AlertDialog.Builder(this).setTitle("Save favourite").setView(body).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val label=name.text.toString().trim().take(60)
            if(label.isEmpty()) {name.error="Enter a label";return@setOnClickListener}
            val place=repo.place.copy(label=label);store.save(place);store.select(place);choose(place,false);dialog.dismiss();Toast.makeText(this,"Favourite saved",Toast.LENGTH_SHORT).show()
        }};dialog.show()
    }
    override fun onResume() {super.onResume();active=true;if(ready){repo.connection();handler.post(tick)}}
    override fun onPause() {active=false;setPlaying(false);handler.removeCallbacks(tick);super.onPause()}
    override fun onSaveInstanceState(out:Bundle) {out.putBoolean("radar",radarVisible);super.onSaveInstanceState(out)}
    override fun onDestroy() {
        dead=true;ready=false;handler.removeCallbacksAndMessages(null)
        runCatching {(getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(networkCallback)}
        map.dispose();repo.close();decoder.shutdownNow();frames.evictAll();super.onDestroy()
    }
    private fun number(n:Double,digits:Int)=String.format(Locale.US,"%.${digits}f",n)
    private fun coords(lat:Double,lon:Double)="${number(abs(lat),4)}°${if(lat>=0)"N" else "S"}  ${number(abs(lon),4)}°${if(lon>=0)"E" else "W"}"
}
