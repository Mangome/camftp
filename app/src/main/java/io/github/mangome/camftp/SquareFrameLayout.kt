package io.github.mangome.camftp

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 方图容器：高度直接取测量到的宽度（3 列等宽 + 每格 1:1）。
 * 不写死 dp —— 换机型 / 换字体缩放下网格都得是方的，而宽度只能测量时才知道。
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 把宽度规格当高度规格用：width 带 weight 时一定拿到 EXACTLY，高就等于宽 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
