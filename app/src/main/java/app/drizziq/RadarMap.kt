package app.drizziq

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*

/** A native, continuous Web Mercator viewport with an ODIM stereographic mesh. */
class RadarMap(context:Context):View(context) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tiles=LruCache<String,Bitmap>(64)
    private val waiting=mutableSetOf<String>(); private val failed=mutableMapOf<String,Long>()
    private val executor=Executors.newFixedThreadPool(3); private val handler=Handler(Looper.getMainLooper())
    private val folder=File(context.cacheDir,"tiles").apply { mkdirs() }
    private var disposed=false
    private var x=.532151; private var y=.3471
    var zoom=7.0; private set
    var place=Place.MUNICH
        set(value) { field=value; recenter() }
    var online=true
    var frame:RadarFrame?=null; private set
    private var bitmap:Bitmap?=null
    var onMapStatus:((String)->Unit)?=null
    private var status=""
    private var projectedGrid:RadarGrid?=null
    private val geographicMesh=DoubleArray(33*37*2)
    private val drawingMesh=FloatArray(33*37*2)
    private val tileRect=RectF()
    private val dark=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        -.17f,-.17f,-.17f,0f,112f,
        -.18f,-.18f,-.18f,0f,130f,
        -.16f,-.16f,-.16f,0f,149f,
        0f,0f,0f,1f,0f)))
    private val scale=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector:ScaleGestureDetector):Boolean { zoomBy(log2(detector.scaleFactor.toDouble()),detector.focusX,detector.focusY);return true }
    })
    private val gesture=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e:MotionEvent)=true
        override fun onScroll(e1:MotionEvent?,e2:MotionEvent,dx:Float,dy:Float):Boolean {
            if(!scale.isInProgress) { val world=world();x=(x+dx/world+1)%1;y=(y+dy/world).coerceIn(0.0,1.0);viewportChanged() };return true
        }
        override fun onDoubleTap(e:MotionEvent):Boolean { zoomBy(1.0,e.x,e.y);return true }
        override fun onSingleTapUp(e:MotionEvent):Boolean { performClick();return true }
    })
    init { setBackgroundColor(Hud.bg); contentDescription="Precipitation map. Drag to pan, pinch or double tap to zoom. Recenter and zoom buttons are below the map.";isFocusable=true;recenter() }
    private fun world()=256.0*2.0.pow(zoom)
    private fun mercator(lat:Double,lon:Double):Pair<Double,Double> {
        val phi=Math.toRadians(lat.coerceIn(-85.05112878,85.05112878))
        return (lon+180)/360 to (1-ln(tan(phi)+1/cos(phi))/Math.PI)/2
    }
    private fun screen(lat:Double,lon:Double):Pair<Float,Float> {
        val (mx,my)=mercator(lat,lon);val delta=((mx-x+.5+1)%1)-.5
        return (width/2+delta*world()).toFloat() to (height/2+(my-y)*world()).toFloat()
    }
    private fun viewportChanged() {
        val latitude=Math.toDegrees(atan(sinh(Math.PI*(1-2*y))))
        contentDescription="Precipitation map. Center ${String.format(java.util.Locale.US,"%.6f, %.6f",latitude,x*360-180)}; zoom ${String.format(java.util.Locale.US,"%.2f",zoom)}. Drag to pan, pinch to zoom."
        invalidate()
    }
    fun recenter() { val c=mercator(place.lat,place.lon);x=c.first;y=c.second;viewportChanged() }
    fun zoomBy(amount:Double,focusX:Float=width/2f,focusY:Float=height/2f) {
        val before=world();val next=(zoom+amount).coerceIn(2.0,17.0)
        zoom=next;val after=world();x=(x+(focusX-width/2)*(1/before-1/after)+1)%1;y=(y+(focusY-height/2)*(1/before-1/after)).coerceIn(0.0,1.0);viewportChanged()
    }
    fun show(frame:RadarFrame,image:Bitmap) {
        if(projectedGrid!=frame.grid) {
            var i=0
            for(iy in 0..36)for(ix in 0..32) {
                val (lat,lon)=RadarProjection.unproject(frame.grid.left+ix*frame.grid.width*frame.grid.dx/32,frame.grid.top-iy*frame.grid.height*frame.grid.dy/36)
                val (mx,my)=mercator(lat,lon);geographicMesh[i++]=mx;geographicMesh[i++]=my
            }
            projectedGrid=frame.grid
        }
        this.frame=frame;bitmap=image;invalidate()
    }
    fun reset() { frame=null;bitmap=null;invalidate() }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        parent.requestDisallowInterceptTouchEvent(true);scale.onTouchEvent(event);gesture.onTouchEvent(event)
        if(event.action==MotionEvent.ACTION_UP || event.action==MotionEvent.ACTION_CANCEL)parent.requestDisallowInterceptTouchEvent(false)
        return true
    }
    override fun performClick():Boolean { super.performClick();return true }
    private fun request(key:String,z:Int,tx:Int,ty:Int) {
        if(disposed || key in waiting || (failed[key]?.let { System.currentTimeMillis()-it<60000 }==true))return
        waiting.add(key)
        executor.execute {
            var image:Bitmap?=null
            try {
                val file=File(folder,"$key.png")
                if(file.exists())image=BitmapFactory.decodeFile(file.path)
                if(online && (image==null || System.currentTimeMillis()-file.lastModified()>7*86400000L)) {
                    val data=Http.bytes("https://tile.openstreetmap.org/$z/$tx/$ty.png",1500000)
                    val downloaded=BitmapFactory.decodeByteArray(data,0,data.size) ?: error("Invalid map tile")
                    val temp=File(file.path+".part");temp.writeBytes(data);check(temp.renameTo(file));image=downloaded
                }
            } catch(_:Exception) { /* A previously cached tile stays visible. */ }
            val result=image
            handler.post {
                if(!disposed) { waiting.remove(key); if(result!=null)tiles.put(key,result) else failed[key]=System.currentTimeMillis();invalidate() }
            }
        }
    }
    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        val z=floor(zoom).toInt();val count=1 shl z;val tile=world()/count
        val left=x*count-width/2/tile;val top=y*count-height/2/tile
        var missing=0;var seen=0
        p.colorFilter=dark;p.alpha=255;p.style=Paint.Style.FILL
        for(iy in floor(top).toInt()..floor(top+height/tile).toInt())for(ix in floor(left).toInt()..floor(left+width/tile).toInt()) {
            if(iy !in 0 until count)continue
            val nx=((ix%count)+count)%count;val key="$z-$nx-$iy"
            val image=tiles.get(key);val rect=tileRect.apply {set(((ix-left)*tile).toFloat(),((iy-top)*tile).toFloat(),((ix-left+1)*tile+1).toFloat(),((iy-top+1)*tile+1).toFloat())}
            seen++
            if(image!=null)c.drawBitmap(image,null,rect,p) else {
                missing++;p.colorFilter=null;p.color=Hud.panel;c.drawRect(rect,p);p.style=Paint.Style.STROKE;p.color=Hud.line;c.drawRect(rect,p);p.style=Paint.Style.FILL;p.colorFilter=dark
            }
            if(image==null)request(key,z,nx,iy)
        }
        p.colorFilter=null
        val next=when { !online -> "BASEMAP · OFFLINE / CACHE"; missing==seen -> "BASEMAP · LOADING / UNAVAILABLE"; missing>0 -> "BASEMAP · PARTIAL"; else -> "BASEMAP · CONNECTED" }
        if(status!=next) { status=next;post { onMapStatus?.invoke(next) } }
        val f=frame;val image=bitmap
        if(f!=null && image!=null) {
            val world=world()
            for(index in geographicMesh.indices step 2) {
                val delta=((geographicMesh[index]-x+.5+1)%1)-.5
                drawingMesh[index]=(width/2+delta*world).toFloat()
                drawingMesh[index+1]=(height/2+(geographicMesh[index+1]-y)*world).toFloat()
            }
            p.alpha=235;c.drawBitmapMesh(image,32,36,drawingMesh,0,null,0,p);p.alpha=255
        }
        val (px,py)=screen(place.lat,place.lon)
        p.color=Hud.cyan;p.style=Paint.Style.STROKE;p.strokeWidth=context.dp(1).toFloat()
        c.drawCircle(px,py,context.dp(17).toFloat(),p);c.drawLine(px-context.dp(24),py,px-context.dp(10),py,p);c.drawLine(px+context.dp(10),py,px+context.dp(24),py,p)
        c.drawLine(px,py-context.dp(24),px,py-context.dp(10),p);c.drawLine(px,py+context.dp(10),px,py+context.dp(24),p)
        p.style=Paint.Style.FILL;c.drawCircle(px,py,context.dp(4).toFloat(),p)
        p.textSize=context.dp(11).toFloat();p.typeface=Typeface.MONOSPACE
        val title=place.label.take(28).uppercase();val textWidth=p.measureText(title)
        p.color=Hud.bg;c.drawRoundRect(px-textWidth/2-context.dp(7),py+context.dp(26),px+textWidth/2+context.dp(7),py+context.dp(47),4f,4f,p)
        p.color=Hud.ink;c.drawText(title,px-textWidth/2,py+context.dp(40),p)
        if(f!=null && !f.grid.contains(place)) {
            p.color=Hud.bg;c.drawRect(0f,0f,width.toFloat(),context.dp(34).toFloat(),p)
            p.color=Hud.amber;p.textAlign=Paint.Align.CENTER;c.drawText("Outside DWD radar coverage",width/2f,context.dp(22).toFloat(),p);p.textAlign=Paint.Align.LEFT
        }
        p.color=Hud.muted;p.textSize=context.dp(10).toFloat();c.drawText("N  /  Z ${String.format(java.util.Locale.US,"%.1f",zoom)}",context.dp(12).toFloat(),height-context.dp(12).toFloat(),p)
    }
    fun dispose() { disposed=true;executor.shutdownNow();handler.removeCallbacksAndMessages(null);tiles.evictAll();bitmap=null }
}

class RadarTimeline(context:Context):View(context) {
    var frames:List<RadarFrame> = emptyList(); set(value) {field=value;invalidate()}
    var selected=0;set(value) {field=value;invalidate()}
    var onSelect:((Int)->Unit)?=null
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription="Radar timeline; swipe to scrub";isFocusable=true;isClickable=true }
    private fun pos(t:Long):Float { if(frames.size<2)return width/2f;return context.dp(16)+(width-context.dp(32))*(t-frames.first().time).toFloat()/(frames.last().time-frames.first().time) }
    override fun onDraw(c:Canvas) {
        if(frames.isEmpty())return
        val y=height*.65f
        p.color=Hud.line;p.strokeWidth=context.dp(3).toFloat();c.drawLine(pos(frames.first().time),y,pos(frames.last().time),y,p)
        frames.forEachIndexed { i,f -> p.color=if(i<=selected)Hud.cyan else Hud.muted;c.drawCircle(pos(f.time),y,context.dp(2).toFloat(),p) }
        val now=System.currentTimeMillis()
        if(now in frames.first().time..frames.last().time) {
            p.color=Hud.amber;p.strokeWidth=context.dp(1).toFloat();val x=pos(now);c.drawLine(x,context.dp(23).toFloat(),x,height.toFloat(),p)
            p.textSize=context.dp(10).toFloat();p.typeface=Typeface.MONOSPACE;p.textAlign=Paint.Align.CENTER;c.drawText("NOW",x,context.dp(16).toFloat(),p)
        }
        p.color=Hud.cyan;frames.getOrNull(selected)?.let { c.drawCircle(pos(it.time),y,context.dp(7).toFloat(),p) }
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(frames.isEmpty())return false
        if(e.action==MotionEvent.ACTION_DOWN || e.action==MotionEvent.ACTION_MOVE || e.action==MotionEvent.ACTION_UP) {
            val index=frames.indices.minBy { abs(pos(frames[it].time)-e.x) };onSelect?.invoke(index)
            if(e.action==MotionEvent.ACTION_UP)performClick();return true
        };return super.onTouchEvent(e)
    }
    override fun performClick():Boolean {super.performClick();return true}
    override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.SeekBar"
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if(frames.isNotEmpty())info.rangeInfo=android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0f,(frames.size-1).toFloat(),selected.toFloat())
    }
    override fun performAccessibilityAction(action:Int,args:android.os.Bundle?):Boolean {
        val next=when(action) {4096->selected+1;8192->selected-1;else->return super.performAccessibilityAction(action,args)}
        if(frames.isEmpty())return false;onSelect?.invoke(next.coerceIn(0,frames.lastIndex));return true
    }
}
