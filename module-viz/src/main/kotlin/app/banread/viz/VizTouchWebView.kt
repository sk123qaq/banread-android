package app.banread.viz

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewParent
import android.webkit.WebView

/**
 * Pooled [WebView] subclass that guards drag and pinch-zoom gestures inside diagrams
 * from being intercepted by a parent BottomSheet or scrollable container.
 */
open class VizTouchWebView(context: Context) : WebView(context) {

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        applyTouchInterceptionGuard(parent, event)
        return super.onTouchEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        applyTouchInterceptionGuard(parent, ev)
        return super.dispatchTouchEvent(ev)
    }

    internal companion object {
        fun applyTouchInterceptionGuard(parent: ViewParent?, event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE,
                -> parent?.requestDisallowInterceptTouchEvent(true)

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }
}
