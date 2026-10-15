package com.modradar.app.ui.ime

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.PopupWindow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 软键盘状态与控制。
 *
 * 窗口声明 adjustNothing，IME 只由 Compose 的 WindowInsets 一套机制让位，避免 OEM ROM
 * （ColorOS 等）旧式 adjustResize 与 imePadding 叠加造成「双重让位」。
 *
 * - API 30+：decorView 上挂 [WindowInsetsAnimationCompat.Callback]，IME 动画期间 [animating] = true；
 * - API < 30：adjustNothing 下系统不再上报 IME inset，改用探针窗口的 OnGlobalLayoutListener 测量键盘高度 [legacyImeHeight]。
 */
class ImeController(private val activity: Activity) {

    private val _animating = MutableStateFlow(false)
    val animating: StateFlow<Boolean> = _animating.asStateFlow()

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _legacyImeHeight = MutableStateFlow(0)
    /** 仅 API < 30 使用：键盘在窗口底部占据的像素高度（含导航栏区域）。 */
    val legacyImeHeight: StateFlow<Int> = _legacyImeHeight.asStateFlow()

    private val decor: View get() = activity.window.decorView
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var probePopup: PopupWindow? = null

    fun attach() {
        if (Build.VERSION.SDK_INT >= 30) {
            ViewCompat.setWindowInsetsAnimationCallback(
                decor,
                object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onStart(
                        animation: WindowInsetsAnimationCompat,
                        bounds: WindowInsetsAnimationCompat.BoundsCompat,
                    ): WindowInsetsAnimationCompat.BoundsCompat {
                        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) _animating.value = true
                        return bounds
                    }

                    override fun onProgress(
                        insets: WindowInsetsCompat,
                        running: MutableList<WindowInsetsAnimationCompat>,
                    ): WindowInsetsCompat = insets

                    override fun onEnd(animation: WindowInsetsAnimationCompat) {
                        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                            _visible.value = ViewCompat.getRootWindowInsets(decor)
                                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                            _animating.value = false
                        }
                    }
                },
            )
            // 无动画的显隐（如硬件键盘、部分 ROM）也要同步 visible
            ViewCompat.setOnApplyWindowInsetsListener(decor) { v, insets ->
                if (!_animating.value) _visible.value = insets.isVisible(WindowInsetsCompat.Type.ime())
                ViewCompat.onApplyWindowInsets(v, insets)
            }
        } else {
            // adjustNothing 下主窗口不会因键盘重新布局，监听不到变化；
            // 改用一个 0 宽、满高、adjustResize 的探针 PopupWindow，它会随键盘缩放并触发 global layout。
            val probe = View(activity)
            val popup = PopupWindow(probe, 0, WindowManager.LayoutParams.MATCH_PARENT).apply {
                @Suppress("DEPRECATION")
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            }
            val rect = Rect()
            val l = ViewTreeObserver.OnGlobalLayoutListener {
                probe.getWindowVisibleDisplayFrame(rect)
                val full = decor.height
                val covered = (full - rect.bottom).coerceAtLeast(0)
                // 超过屏高 15% 视为键盘；否则只是导航栏
                val ime = if (covered > full * 0.15f) covered else 0
                _legacyImeHeight.value = ime
                _visible.value = ime > 0
            }
            probe.viewTreeObserver.addOnGlobalLayoutListener(l)
            layoutListener = l
            probePopup = popup
            decor.post { if (!activity.isFinishing && decor.windowToken != null) popup.showAtLocation(decor, Gravity.NO_GRAVITY, 0, 0) }
        }
    }

    fun detach() {
        if (Build.VERSION.SDK_INT >= 30) {
            ViewCompat.setWindowInsetsAnimationCallback(decor, null)
            ViewCompat.setOnApplyWindowInsetsListener(decor, null)
        } else {
            probePopup?.let { p -> layoutListener?.let { p.contentView.viewTreeObserver.removeOnGlobalLayoutListener(it) }; p.dismiss() }
            probePopup = null
            layoutListener = null
        }
    }

    /** 立即请求收起键盘（不等待动画结束）。 */
    fun requestHide() {
        activity.currentFocus?.clearFocus()
        WindowCompat.getInsetsController(activity.window, decor).hide(WindowInsetsCompat.Type.ime())
        if (Build.VERSION.SDK_INT < 30) {
            activity.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(decor.windowToken, 0)
        }
    }

    /** 收起键盘并挂起到完全收起（含动画结束）；最多等 [timeoutMs]。截图 / 弹窗 / 跳转前调用。 */
    suspend fun hideAndAwait(timeoutMs: Long = 600) {
        if (!_visible.value && !_animating.value) return
        requestHide()
        withTimeoutOrNull(timeoutMs) {
            _visible.first { !it }
            _animating.first { !it }
        }
    }
}

val LocalImeController = staticCompositionLocalOf<ImeController?> { null }

/**
 * 全项目统一的 IME 让位修饰符：任一时刻只有一种补偿生效。
 *
 * - API 30+：IME 动画进行中跳过 imePadding，动画结束后再让位；
 * - API < 30：按测得的键盘高度让位，并消费掉这部分 inset，
 *   使后续 navigationBarsPadding 与 API 30+ 下 imePadding 的消费语义一致。
 */
fun Modifier.optimizedImePadding(): Modifier = composed {
    val ctl = LocalImeController.current ?: return@composed imePadding()
    if (Build.VERSION.SDK_INT >= 30) {
        val animating by ctl.animating.collectAsStateWithLifecycle()
        if (animating) Modifier else imePadding()
    } else {
        val h by ctl.legacyImeHeight.collectAsStateWithLifecycle()
        val inset = WindowInsets(bottom = h)
        windowInsetsPadding(inset).consumeWindowInsets(inset)
    }
}
