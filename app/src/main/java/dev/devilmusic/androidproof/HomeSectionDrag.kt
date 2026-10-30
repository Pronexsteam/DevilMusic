package dev.devilmusic.androidproof

import android.content.ClipData
import android.os.Bundle
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView

/** Native drag shadow, edge scrolling and accessible reorder actions; edits remain a draft. */
internal class HomeSectionDrag(private val content:LinearLayout,private val scroll:ScrollView,
    private val order:MutableList<String>) {
    private var active:View?=null
    private var original=emptyList<String>()
    private var pointerY=0f
    private val density=content.resources.displayMetrics.density
    private val upId=0x01020001
    private val downId=0x01020002
    private val tick=object:Runnable {
        override fun run(){
            if(active==null)return
            val edge=48*density
            val delta=when {pointerY<edge->-((edge-pointerY)/edge*10*density).toInt();pointerY>scroll.height-edge->((pointerY-scroll.height+edge)/edge*10*density).toInt();else->0}
            if(delta!=0){scroll.scrollBy(0,delta);reorder(pointerY+scroll.scrollY)}
            scroll.postOnAnimation(this)
        }
    }
    init {
        scroll.setOnDragListener {_,event->
            val row=active
            when(event.action){
                DragEvent.ACTION_DRAG_STARTED->event.localState===this
                DragEvent.ACTION_DRAG_LOCATION->{pointerY=event.y;reorder(event.y+scroll.scrollY);true}
                DragEvent.ACTION_DROP->row!=null
                DragEvent.ACTION_DRAG_ENDED->{
                    scroll.removeCallbacks(tick)
                    row?.alpha=1f
                    active=null
                    if(!event.result && original.isNotEmpty()){
                        order.clear();order.addAll(original)
                        val views=(0 until content.childCount).map {content.getChildAt(it)}.associateBy {it.tag as String}
                        content.removeAllViews();order.forEach {views[it]?.let(content::addView)}
                    }
                    row?.announceForAccessibility("${row.tag}, position ${order.indexOf(row.tag)+1} of ${order.size}")
                    true
                }
                else->event.localState===this
            }
        }
        scroll.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View){}
            override fun onViewDetachedFromWindow(v:View){scroll.removeCallbacks(tick);active?.alpha=1f;active=null}
        })
    }
    private fun move(row:View,to:Int){
        val from=content.indexOfChild(row)
        if(from<0 || from==to || to !in order.indices)return
        val old=(0 until content.childCount).associate {content.getChildAt(it) to content.getChildAt(it).top}
        order.add(to,order.removeAt(from));content.removeView(row);content.addView(row,to)
        content.post {
            old.forEach {(view,top)->if(view!==active && view.parent===content){view.animate().cancel();view.translationY=(top-view.top).toFloat();view.animate().translationY(0f).setDuration(150).start()}}
        }
    }
    private fun reorder(y:Float){
        val row=active ?: return
        var target=0
        for(i in 0 until content.childCount){val child=content.getChildAt(i);if(child!==row && y>child.top+child.height/2f)target++}
        move(row,target)
    }
    fun bind(handle:View,row:View,name:String){
        row.tag=name
        handle.contentDescription="Reorder $name"
        handle.isFocusable=true;handle.isClickable=true
        fun begin():Boolean {
            if(active!=null)return false
            original=order.toList();active=row
            val location=IntArray(2);handle.getLocationOnScreen(location)
            val origin=IntArray(2);scroll.getLocationOnScreen(origin)
            pointerY=(location[1]-origin[1]+handle.height/2).toFloat()
            val started=handle.startDragAndDrop(ClipData.newPlainText("Home section",name),View.DragShadowBuilder(row),this,0)
            if(started){row.alpha=.25f;handle.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);scroll.postOnAnimation(tick)}else active=null
            return started
        }
        var downY=0f
        handle.setOnTouchListener {v,event->
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{downY=event.rawY;v.parent.requestDisallowInterceptTouchEvent(true);true}
                MotionEvent.ACTION_MOVE->{if(kotlin.math.abs(event.rawY-downY)>ViewConfiguration.get(v.context).scaledTouchSlop)begin();true}
                MotionEvent.ACTION_UP->{v.parent.requestDisallowInterceptTouchEvent(false);v.performClick();true}
                MotionEvent.ACTION_CANCEL->{v.parent.requestDisallowInterceptTouchEvent(false);true}
                else->false
            }
        }
        handle.setOnClickListener {android.widget.Toast.makeText(it.context,"Drag the handle to move this section",android.widget.Toast.LENGTH_SHORT).show()}
        handle.accessibilityDelegate=object:View.AccessibilityDelegate(){
            override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo){
                super.onInitializeAccessibilityNodeInfo(host,info)
                val index=order.indexOf(name)
                if(index>0)info.addAction(AccessibilityNodeInfo.AccessibilityAction(upId,"Move up"))
                if(index<order.lastIndex)info.addAction(AccessibilityNodeInfo.AccessibilityAction(downId,"Move down"))
            }
            override fun performAccessibilityAction(host:View,action:Int,args:Bundle?):Boolean {
                if(action==upId || action==downId){move(row,order.indexOf(name)+if(action==upId)-1 else 1);host.announceForAccessibility("$name, position ${order.indexOf(name)+1} of ${order.size}");return true}
                return super.performAccessibilityAction(host,action,args)
            }
        }
    }
}
