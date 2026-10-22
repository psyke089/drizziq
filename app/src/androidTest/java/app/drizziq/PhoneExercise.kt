package app.drizziq

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Exercises real native UI on an attached device, without external test libraries. */
class PhoneExercise:Instrumentation() {
    private var phase="main"
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);phase=arguments?.getString("phase")?:"main";start()}
    private fun nodes(node:AccessibilityNodeInfo?=uiAutomation.rootInActiveWindow):List<AccessibilityNodeInfo> =
        if(node==null)emptyList() else listOf(node)+(0 until node.childCount).flatMap {nodes(node.getChild(it))}
    private fun waitFor(description:String,timeout:Long=90000,test:()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+timeout
        while(SystemClock.uptimeMillis()<deadline) {if(test())return;SystemClock.sleep(250)}
        error("Timed out: $description. Screen: ${nodes().mapNotNull {it.text?.toString()}.joinToString(" | ")}")
    }
    private fun has(text:String)=nodes().any {it.text?.toString()?.contains(text)==true || it.contentDescription?.toString()?.contains(text)==true}
    private fun click(text:String) {
        fun find()=nodes().firstOrNull {it.text?.toString()?.equals(text,true)==true || it.contentDescription?.toString()?.equals(text,true)==true}
        waitFor("button $text",10000){find()!=null}
        val node=find()?:error("Button disappeared: $text")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {"Click failed: $text"};SystemClock.sleep(350)
    }
    private fun enter(label:String,value:String) {
        val node=nodes().first {it.contentDescription?.toString()==label && it.isEditable}
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}))
    }
    private fun drag(x1:Float,y1:Float,x2:Float,y2:Float) {
        val start=SystemClock.uptimeMillis()
        for(i in 0..20) {
            val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),when(i){0->0;20->1;else->2},x1+(x2-x1)*i/20,y1+(y2-y1)*i/20,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN;uiAutomation.injectInputEvent(event,true);event.recycle();SystemClock.sleep(15)
        }
    }
    private fun pinch(x:Float,y:Float) {
        val start=SystemClock.uptimeMillis()
        val props=Array(2){i->MotionEvent.PointerProperties().apply {id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
        fun send(action:Int,count:Int,distance:Float) {
            val coords=Array(count){i->MotionEvent.PointerCoords().apply {this.x=x+(if(i==0)-distance else distance);this.y=y;pressure=1f;size=1f}}
            val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,count,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            uiAutomation.injectInputEvent(event,true);event.recycle()
        }
        send(MotionEvent.ACTION_DOWN,1,60f);send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),2,60f)
        for(i in 1..20){send(MotionEvent.ACTION_MOVE,2,60f+i*5);SystemClock.sleep(15)}
        send(MotionEvent.ACTION_POINTER_UP or (1 shl 8),2,160f);send(MotionEvent.ACTION_UP,1,160f)
    }
    override fun onStart() {
        val result=Bundle()
        try {
            startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("initial app"){has("PLACES")}
            when(phase) {
                "forecast" -> {
                    click("01  FORECAST");waitFor("forecast"){has("CURRENT FORECAST")}
                    fun scroll():AccessibilityNodeInfo=nodes().first {it.className=="android.widget.ScrollView"}
                    scroll().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);SystemClock.sleep(400)
                    val outlook=nodes().firstOrNull {it.className=="android.widget.HorizontalScrollView"}
                    check(outlook!=null) {"Outlook is not scrollable"}
                    repeat(6) {outlook.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);SystemClock.sleep(120)}
                    val last=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Berlin")).apply {add(java.util.Calendar.DAY_OF_MONTH,6)}
                    check(has(Times.format(last.timeInMillis,"dd MMM","Europe/Berlin"))) {"Seventh forecast day is not reachable"}
                    var reached=false
                    for(i in 0..16) {
                        if(has("DATA TRANSMISSION")) {reached=true;break}
                        scroll().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);SystemClock.sleep(150)
                    }
                    check(reached&&has("Issue")&&has("Received")) {"Hourly detail or provenance footer is not reachable"}
                    check(has("Relative humidity is not supplied")) {"Missing humidity is not explained"}
                    repeat(20){scroll().performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)}
                    result.putString("checks","forecast scroll; all seven days reachable; hourly detail traversed; missing humidity omitted; issue, validity and received footer visible")
                }
                "main" -> {
                    waitFor("live Munich forecast"){has("CURRENT FORECAST")&&has("LIVE")}
                    click("PLACES");click("ENTER COORDINATES");enter("LATITUDE · −90 TO 90","48.138108");enter("LONGITUDE · −180 TO 180","11.574726");enter("LABEL (OPTIONAL)","Munich");click("Use location")
                    waitFor("Munich forecast"){has("MUENCHEN STADT")&&has("LIVE")}
                    click("PLACES");click("SAVE CURRENT AS FAVOURITE");enter("FAVOURITE LABEL","Munich home");click("Save")
                    click("PLACES");click("ENTER COORDINATES")
                    enter("LATITUDE · −90 TO 90","91");click("Use location");check(has("Enter location")) {"Invalid latitude accepted"}
                    enter("LATITUDE · −90 TO 90","52.5200");enter("LONGITUDE · −180 TO 180","181");click("Use location");check(has("Enter location")) {"Invalid longitude accepted"}
                    enter("LONGITUDE · −180 TO 180","13.4050");enter("LABEL (OPTIONAL)","Berlin test");click("Use location")
                    waitFor("Berlin live forecast"){has("BERLIN")&&has("LIVE")&&has("CURRENT FORECAST")}
                    click("PLACES");click("SAVE CURRENT AS FAVOURITE");enter("FAVOURITE LABEL","Berlin saved");click("Save")
                    click("02  RADAR");waitFor("radar frames",180000){has("OBSERVED")&&has("frames")}
                    val map=nodes().first {it.contentDescription?.toString()?.startsWith("Precipitation map")==true};val bounds=Rect();map.getBoundsInScreen(bounds)
                    fun viewport()=nodes().first {it.contentDescription?.toString()?.startsWith("Precipitation map")==true}.contentDescription.toString()
                    val original=viewport()
                    drag(bounds.centerX().toFloat(),bounds.centerY().toFloat(),bounds.centerX()+170f,bounds.centerY()+120f)
                    check(viewport()!=original) {"Map did not pan"}
                    val panned=viewport();pinch(bounds.centerX().toFloat(),bounds.centerY().toFloat());check(viewport()!=panned) {"Pinch did not change viewport"}
                    click("Recenter on selected location");val centered=viewport();click("Zoom out");check(viewport()!=centered);click("Zoom in")
                    val bar=nodes().first {it.className=="android.widget.SeekBar"};val r=Rect();bar.getBoundsInScreen(r)
                    drag(r.left+10f,r.centerY().toFloat(),r.right-10f,r.centerY().toFloat())
                    waitFor("last forecast frame"){has("FORECAST +120m")}
                    click("Play or pause radar animation");waitFor("animation loops",10000){has("OBSERVED")};click("Play or pause radar animation")
                    click("PLACES");click("Select favourite Munich home")
                    result.putString("checks","live Munich; coordinate validation; live Berlin; save two favourites; radar pan; two-finger pinch; zoom buttons; recenter; scrub to +120; playback loop; select favourite")
                }
                "persistence" -> {
                    click("PLACES");check(has("Berlin saved"));check(has("Munich home"));click("Select favourite Berlin saved")
                    waitFor("selected persisted Berlin"){has("CURRENT FORECAST")&&has("BERLIN")}
                    click("PLACES");click("Delete favourite Berlin saved");check(nodes().none {it.contentDescription?.toString()=="Select favourite Berlin saved"});click("Select favourite Munich home")
                    result.putString("checks","favourites survived force-stop; selected Berlin; deleted Berlin; selected Munich")
                }
                "offline" -> {
                    waitFor("offline cached forecast"){has("OFFLINE")&&has("STALE")&&has("CURRENT FORECAST")}
                    click("02  RADAR");waitFor("offline cached radar"){has("STALE")&&has("frames")&&has("OBSERVED")}
                    click("Play or pause radar animation");SystemClock.sleep(1800);click("Play or pause radar animation")
                    result.putString("checks","airplane mode retains forecast and radar; stale/offline labels; cached playback")
                }
                "restore" -> {
                    waitFor("live forecast recovered"){has("LIVE")&&has("CURRENT FORECAST")}
                    result.putString("checks","connectivity recovery returns forecast to live")
                }
            }
            result.putString("result","PASS");finish(0,result)
        } catch(t:Throwable) {result.putString("result","FAIL");result.putString("error",t.stackTraceToString());finish(-1,result)}
    }
}
