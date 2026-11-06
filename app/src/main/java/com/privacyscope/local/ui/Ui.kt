package com.privacyscope.local.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

/**
 * 界面积木（v0.7 黑客风 / 暗色）
 *
 * 设计约束（改之前先看这四条）：
 * 1. 深底 + 等宽字 + 细绿边，不用圆角大卡片，不用白底，不用彩色大色块；
 * 2. 长文段一律用 fold() 折起来 —— 页面第一屏只放"结论 + 操作"，解释都收在折叠里；
 * 3. 颜色只承担一种含义：绿=正常/可用，红=风险/失败，黄=需要注意；
 * 4. 全部用代码搭，不写 XML —— 主题换色只需要改本文件顶部的常量。
 */
object Ui {

    // —— 配色：一改全改 ——
    // v0.9.9 提对比度：暗底上一律按 WCAG 的「正文 ≥ 4.5:1」推。
    // C_DIM 原来 0xFF5C7F6C 在 C_CARD 上只有约 2.3:1 —— 小字基本看不清，是用户报的主要问题。
    const val C_BG = 0xFF05070A.toInt()      // 近黑（底）
    const val C_CARD = 0xFF0A1014.toInt()    // 面板
    const val C_INK = 0xFFE8FFF2.toInt()     // 主文字（比原来再亮一点）
    const val C_DIM = 0xFF9DBFAC.toInt()     // 次要文字（在 C_CARD 上约 7:1）
    const val C_SEED = 0xFF3BF59A.toInt()    // 主色：荧光绿（更亮，暗底上约 12:1）
    const val C_WARN = 0xFFFF6B5E.toInt()    // 红：风险（提亮，暗底上更易读）
    const val C_AMBER = 0xFFFFC44D.toInt()   // 黄：注意
    const val C_LINE = 0xFF2E7A55.toInt()    // 分隔线 / 边框（1px 也看得见）
    const val C_BORDER = 0xFF54C08A.toInt()  // 勾选框 / 输入框的框：必须比 C_LINE 更亮

    private val MONO = Typeface.MONOSPACE

    fun dp(ctx: Context, v: Number): Int =
        (v.toFloat() * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 带边的圆角背景。
     *
     * 关键：GradientDrawable 的描边是**以边界为中心**画的 —— 向外那半个线宽落在 View 外面，
     * 被直接裁掉。1dp 的边在 2x/3x 屏上就是 2~3px，被裁掉一半后底边常常整条看不见。
     * 所以有描边时套一层 InsetDrawable 把绘制区域向内收半个线宽，四条边就都完整了。
     */
    fun round(bg: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0): Drawable {
        val d = GradientDrawable()
        d.setColor(bg)
        d.cornerRadius = radius.toFloat()
        if (stroke > 0) {
            d.setStroke(stroke, strokeColor)
            return InsetDrawable(d, stroke / 2)
        }
        return d
    }

    /**
     * 空勾选框的边框。
     *
     * 为什么不用 GradientDrawable：它的描边是**以边界为中心**画的，有一半落在 View 外面被裁掉；
     * 在 18dp 的小方块 + 圆角上，底边最容易被吃掉，表现就是「框没有下边框」。
     * 这里自己画：把描边整体内缩 half，保证四条边完整落在 bounds 里。
     */
    private class BoxOutline(
        private val stroke: Int,
        private val radius: Float,
        private val color: Int
    ) : Drawable() {
        private val paint = Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeWidth = stroke.toFloat()
            this.color = color
        }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val h = stroke / 2f
            rect.set(
                bounds.left + h, bounds.top + h,
                bounds.right - h, bounds.bottom - h
            )
            if (rect.width() <= 0f || rect.height() <= 0f) return
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // —————————————————— 文字 ——————————————————

    fun mono(ctx: Context, text: String, size: Float = 12f, color: Int = C_INK): TextView {
        val t = TextView(ctx)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        t.typeface = MONO
        t.setLineSpacing(dp(ctx, 3).toFloat(), 1f)
        return t
    }

    fun title(ctx: Context, text: String, size: Float = 15f): TextView {
        val t = mono(ctx, text, size, C_INK)
        t.setTypeface(MONO, Typeface.BOLD)
        return t
    }

    fun body(ctx: Context, text: String): TextView = mono(ctx, text, 12f, C_DIM)

    /** 段落小标题：`▍ 网络观测` —— 一眼能看出是新的一段 */
    fun section(ctx: Context, text: String): TextView {
        val t = mono(ctx, "▍" + text, 13f, C_SEED)
        t.setTypeface(MONO, Typeface.BOLD)
        t.setPadding(0, dp(ctx, 14), 0, dp(ctx, 6))
        return t
    }

    /** 键值行：左灰右亮，等宽，数字对齐 */
    fun keyValue(ctx: Context, k: String, v: String, valueColor: Int = C_INK): LinearLayout {
        val r = row(ctx)
        val a = mono(ctx, k, 12f, C_DIM)
        a.maxLines = 1
        a.ellipsize = TextUtils.TruncateAt.END
        a.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val b = mono(ctx, v, 12f, valueColor)
        b.setTypeface(MONO, Typeface.BOLD)
        b.gravity = Gravity.END
        r.addView(a)
        r.addView(b)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(ctx, 7)
        r.layoutParams = lp
        return r
    }

    // —————————————————— 容器 ——————————————————

    fun column(ctx: Context): LinearLayout {
        val ll = LinearLayout(ctx)
        ll.orientation = LinearLayout.VERTICAL
        return ll
    }

    fun row(ctx: Context): LinearLayout {
        val ll = LinearLayout(ctx)
        ll.orientation = LinearLayout.HORIZONTAL
        ll.gravity = Gravity.CENTER_VERTICAL
        return ll
    }

    fun spacer(ctx: Context, h: Number): Space {
        val s = Space(ctx)
        s.layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h))
        return s
    }

    fun card(ctx: Context, pad: Number = 12): LinearLayout {
        val ll = LinearLayout(ctx)
        ll.orientation = LinearLayout.VERTICAL
        val p = dp(ctx, pad)
        ll.setPadding(p, p, p, p)
        ll.background = round(C_CARD, dp(ctx, 4), dp(ctx, 1), C_LINE)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(ctx, 10)
        ll.layoutParams = lp
        return ll
    }

    fun scroll(ctx: Context): android.widget.ScrollView {
        val s = android.widget.ScrollView(ctx)
        s.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        return s
    }

    fun divider(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(C_LINE)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1))
        return v
    }

    // —————————————————— 折叠 ——————————————————

    /**
     * 折叠块：点头部展开/收起。
     *
     * ```
     * val f = Ui.fold(ctx, "为什么会有这一页", false) { box ->
     *     box.addView(Ui.body(ctx, "……长文……"))
     * }
     * ```
     * 长文段、解释性内容、排查步骤都放这里 —— 第一屏只留结论和按钮。
     */
    fun fold(ctx: Context, label: String, open: Boolean = false, build: (LinearLayout) -> Unit): LinearLayout {
        val box = column(ctx)
        val head = mono(ctx, (if (open) "▾ " else "▸ ") + label, 12.5f, C_AMBER)
        head.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        val body = column(ctx)
        body.setPadding(dp(ctx, 10), 0, 0, dp(ctx, 4))
        build(body)
        body.visibility = if (open) View.VISIBLE else View.GONE
        head.setOnClickListener {
            val to = body.visibility != View.VISIBLE
            body.visibility = if (to) View.VISIBLE else View.GONE
            head.text = (if (to) "▾ " else "▸ ") + label
        }
        box.addView(head)
        box.addView(body)
        return box
    }

    /** 折叠一段纯文字（最常用：把一段解释收起来） */
    fun foldText(ctx: Context, label: String, text: String, open: Boolean = false): LinearLayout =
        fold(ctx, label, open) { it.addView(mono(ctx, text, 12f, C_DIM)) }

    /** 折叠式说明（默认收起）：页面上只留一行标题，点开才看见这段话 */
    fun note(ctx: Context, label: String, text: String): LinearLayout =
        foldText(ctx, label, text, false)

    // —————————————————— 控件 ——————————————————

    fun button(ctx: Context, text: String, filled: Boolean = true, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = text
        b.textSize = 12f
        b.typeface = MONO
        b.isAllCaps = false
        b.setTextColor(if (filled) 0xFF04120A.toInt() else C_SEED)
        b.background = round(if (filled) C_SEED else 0x00000000, dp(ctx, 3), dp(ctx, 1), C_SEED)
        b.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
        b.minHeight = 0
        b.minimumHeight = 0
        b.stateListAnimator = null
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.rightMargin = dp(ctx, 6)
        lp.topMargin = dp(ctx, 6)
        b.layoutParams = lp
        return b
    }

    fun chip(ctx: Context, text: String, color: Int): TextView {
        val t = mono(ctx, text, 10.5f, color)
        t.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
        t.background = round(0x00000000, dp(ctx, 2), dp(ctx, 1), color)
        return t
    }

    /** 一行状态：`● 运行中` */
    fun dot(ctx: Context, on: Boolean, onText: String, offText: String, size: Float = 13f): TextView =
        mono(ctx, (if (on) "● " else "○ ") + (if (on) onText else offText), size, if (on) C_SEED else C_DIM)

    /**
     * 自绘勾选框（v0.7.1）
     *
     * 不用系统 CheckBox：它自带 drawable 的内边距，在暗色主题 + 固定行高下底边容易被裁掉，
     * 看起来就是「框的下边框消失了」。这里画一个固定 18dp 的方块，四条边一个像素不差。
     */
    fun check(ctx: Context, size: Int = 18): TextView {
        val t = TextView(ctx)
        t.textSize = 12f
        t.typeface = MONO
        t.gravity = Gravity.CENTER
        t.includeFontPadding = false
        // 注意：TextView.setHeight() 设的是「最大高度」，文字为空时测量高度会塌成 0，
        // 表现就是「勾选框的下边框 / 整条边消失」。所以用 LayoutParams 固定尺寸 +
        // minimumHeight 兜底，绝不依赖 width/height 属性。
        t.minimumWidth = dp(ctx, size)
        t.minimumHeight = dp(ctx, size)
        val lp = LinearLayout.LayoutParams(dp(ctx, size), dp(ctx, size))
        t.layoutParams = lp
        t.isClickable = true
        paintCheck(ctx, t, false)
        return t
    }

    fun paintCheck(ctx: Context, t: TextView, checked: Boolean) {
        if (checked) {
            t.text = "✓"
            t.setTextColor(0xFF04120A.toInt())
            t.background = round(C_SEED, dp(ctx, 3))
        } else {
            t.text = ""
            t.setTextColor(C_DIM)
            // 2dp 边框 + 提亮后的 C_BORDER：1dp 在深色面板上几乎看不见，这是用户反复报的那个问题。
            val w = dp(ctx, 2)
            t.background = BoxOutline(if (w < 3) 3 else w, dp(ctx, 3).toFloat(), C_BORDER)
        }
    }

    fun warnColor(score: Int): Int = when {
        score >= 60 -> C_WARN
        score >= 30 -> C_AMBER
        else -> C_SEED
    }

    fun parseColor(c: Int): Int = c

    /** 面板顶部的细线（贴在页面最上面，制造"终端窗"感） */
    fun topRule(ctx: Context, label: String): View {
        val v = View(ctx)
        v.background = round(0x00000000, 0, dp(ctx, 1), C_LINE)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1))
        v.contentDescription = label
        return v
    }

    fun tint(color: Int): Int = color or 0xFF000000.toInt()
}
