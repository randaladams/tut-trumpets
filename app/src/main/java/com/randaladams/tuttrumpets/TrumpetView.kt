package com.randaladams.tuttrumpets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** A playable note. group 0 = silver trumpet, 1 = copper trumpet. */
data class NoteDef(
    val id: String,
    val group: Int,
    val name: String,
    val pitch: String,
    val hz: Int,
    val rare: Boolean = false
)

/** Notes as measured in the 1939 BBC recording, listed high to low. */
val NOTES = listOf(
    NoteDef("A3", 0, "High", "B5", 991),
    NoteDef("A2", 0, "Middle", "G5", 767),
    NoteDef("A1", 0, "Low", "B4", 503),
    NoteDef("B3", 1, "High", "G5", 773),
    NoteDef("B2", 1, "Middle", "C#5", 559),
    NoteDef("B1", 1, "Low", "A#4", 475),
    NoteDef("B0", 1, "Rare low", "A4", 442, rare = true)
)

@SuppressLint("ViewConstructor")
class TrumpetView(
    context: Context,
    private val engine: AudioEngine,
    private val samples: Map<String, Sample>,
    private val onInfo: () -> Unit
) : View(context) {

    private class Zone(val note: NoteDef, val rect: RectF)

    private val zones = ArrayList<Zone>()
    private var titleBaseline = 0f
    private var subBaseline = 0f
    private val active = HashMap<Int, Zone>() // pointer id -> zone
    private val infoRect = RectF()
    private val bellPaths = arrayOf(Path(), Path())
    private val columnTitleY = FloatArray(2)
    private val columnCenterX = FloatArray(2)

    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
    private val colBg = 0xFF121219.toInt()
    private val colText = 0xFFECE9E2.toInt()
    private val colMuted = 0xFF9C98A6.toInt()
    private val metal = intArrayOf(0xFFC9D1DC.toInt(), 0xFFD9895B.toInt())
    private val metalDark = intArrayOf(0xFF6E7785.toInt(), 0xFF7A3E1F.toInt())

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colText; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textSize = 24 * dp
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colMuted; typeface = Typeface.MONOSPACE; textSize = 11 * dp
    }
    private val colTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textSize = 19 * dp
        textAlign = Paint.Align.CENTER
    }
    private val noteNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colText; typeface = Typeface.DEFAULT_BOLD; textSize = 15 * dp
        textAlign = Paint.Align.CENTER
    }
    private val notePitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colMuted; typeface = Typeface.MONOSPACE; textSize = 11 * dp
        textAlign = Paint.Align.CENTER
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp
    }
    private val bellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = colMuted
    }
    private val infoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colMuted; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        textSize = 16 * dp; textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        zones.clear()
        val pad = 16 * dp
        val colGap = 12 * dp
        val colW = (w - pad * 2 - colGap) / 2f
        val infoSize = 40 * dp
        infoRect.set(w - pad - infoSize, 20 * dp, w - pad, 20 * dp + infoSize)

        // Header text follows the phone's font size but shrinks to fit beside the info button.
        val textMaxW = infoRect.left - pad - 12 * dp
        fitText(titlePaint, TITLE, 24 * sp, textMaxW)
        fitText(subPaint, SUBTITLE, 12 * sp, w - pad * 2)
        titleBaseline = 20 * dp - titlePaint.ascent()
        subBaseline = titleBaseline + titlePaint.descent() + 6 * dp - subPaint.ascent()
        val header = subBaseline + subPaint.descent()
        colTitlePaint.textSize = min(19 * sp, colW / 4f)
        noteNamePaint.textSize = min(15 * sp, colW / 6f)
        notePitchPaint.textSize = min(11 * sp, colW / 11f)

        for (g in 0..1) {
            val left = pad + g * (colW + colGap)
            val cx = left + colW / 2f
            columnCenterX[g] = cx
            columnTitleY[g] = header + 14 * dp - colTitlePaint.ascent()
            val top = columnTitleY[g] + colTitlePaint.descent() + 14 * dp
            val bottom = h - pad
            val notes = NOTES.filter { it.group == g }
            // Rare note gets a smaller zone at the bottom.
            val weights = notes.map { if (it.rare) 0.6f else 1f }
            val total = weights.sum()
            val gap = 8 * dp
            val avail = bottom - top - gap * (notes.size - 1)
            var y = top
            notes.forEachIndexed { i, n ->
                val zh = avail * weights[i] / total
                zones.add(Zone(n, RectF(left, y, left + colW, y + zh)))
                y += zh + gap
            }
            // Trumpet silhouette: a narrow tube that flares into a bell.
            val p = bellPaths[g]
            p.reset()
            val tubeHalf = colW * 0.035f
            val bellHalf = colW * 0.40f
            val flareStart = top + (bottom - top) * 0.62f
            p.moveTo(cx - tubeHalf * 1.8f, top - 6 * dp)
            p.lineTo(cx + tubeHalf * 1.8f, top - 6 * dp)
            p.lineTo(cx + tubeHalf * 1.8f, top)
            p.lineTo(cx + tubeHalf, top)
            p.lineTo(cx + tubeHalf, flareStart)
            p.quadTo(cx + tubeHalf, bottom - 10 * dp, cx + bellHalf, bottom)
            p.lineTo(cx - bellHalf, bottom)
            p.quadTo(cx - tubeHalf, bottom - 10 * dp, cx - tubeHalf, flareStart)
            p.lineTo(cx - tubeHalf, top)
            p.lineTo(cx - tubeHalf * 1.8f, top)
            p.close()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colBg)
        val pad = 16 * dp
        canvas.drawText(TITLE, pad, titleBaseline, titlePaint)
        canvas.drawText(SUBTITLE, pad, subBaseline, subPaint)
        canvas.drawOval(infoRect, infoPaint)
        canvas.drawText(
            "i", infoRect.centerX(),
            infoRect.centerY() - (infoTextPaint.ascent() + infoTextPaint.descent()) / 2f, infoTextPaint
        )

        val activeIds = active.values.map { it.note.id }.toHashSet()
        for (g in 0..1) {
            colTitlePaint.color = metal[g]
            canvas.drawText(if (g == 0) "Silver" else "Copper", columnCenterX[g], columnTitleY[g], colTitlePaint)
            val b = bellPaths[g]
            val bounds = RectF()
            b.computeBounds(bounds, true)
            bellPaint.shader = LinearGradient(
                bounds.left, 0f, bounds.right, 0f,
                intArrayOf(metalDark[g], metal[g], metalDark[g]), null, Shader.TileMode.CLAMP
            )
            bellPaint.alpha = 90
            canvas.drawPath(b, bellPaint)
        }

        val r = 10 * dp
        for (z in zones) {
            val g = z.note.group
            val on = z.note.id in activeIds
            zonePaint.color = metal[g]
            zonePaint.alpha = if (on) 120 else 22
            canvas.drawRoundRect(z.rect, r, r, zonePaint)
            strokePaint.color = metal[g]
            strokePaint.alpha = if (on) 255 else if (z.note.rare) 70 else 110
            canvas.drawRoundRect(z.rect, r, r, strokePaint)

            val cx = z.rect.centerX()
            val cy = z.rect.centerY()
            val gap = 4 * dp
            val nameH = noteNamePaint.descent() - noteNamePaint.ascent()
            val pitchH = notePitchPaint.descent() - notePitchPaint.ascent()
            val blockTop = cy - (nameH + gap + pitchH) / 2f
            canvas.drawText(z.note.name, cx, blockTop - noteNamePaint.ascent(), noteNamePaint)
            canvas.drawText(
                "${z.note.pitch} · ${z.note.hz} Hz", cx,
                blockTop + nameH + gap - notePitchPaint.ascent(), notePitchPaint
            )
        }
    }

    private fun fitText(p: Paint, text: String, preferred: Float, maxWidth: Float) {
        p.textSize = preferred
        val w = p.measureText(text)
        if (w > maxWidth) p.textSize = preferred * maxWidth / w
    }

    private fun zoneAt(x: Float, y: Float): Zone? = zones.firstOrNull { it.rect.contains(x, y) }

    private fun press(pointerId: Int, z: Zone) {
        val s = samples[z.note.id] ?: return
        active[pointerId] = z
        engine.noteOn(z.note.id, z.note.group, s)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun releasePointer(pointerId: Int) {
        val z = active.remove(pointerId) ?: return
        if (active.values.none { it.note.id == z.note.id }) engine.noteOff(z.note.id)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                if (e.actionMasked == MotionEvent.ACTION_DOWN && infoRect.contains(x, y)) {
                    onInfo()
                    return true
                }
                zoneAt(x, y)?.let { press(e.getPointerId(i), it) }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val pid = e.getPointerId(i)
                    val z = zoneAt(e.getX(i), e.getY(i))
                    val cur = active[pid]
                    if (z !== cur) {
                        if (cur != null) releasePointer(pid)
                        if (z != null) press(pid, z)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                releasePointer(e.getPointerId(e.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                for (pid in active.keys.toList()) releasePointer(pid)
            }
        }
        invalidate()
        return true
    }

    /** Called when the app goes to the background. */
    fun releaseAll() {
        for (pid in active.keys.toList()) releasePointer(pid)
        invalidate()
    }

    companion object {
        private const val TITLE = "Tutankhamun's Trumpets"
        private const val SUBTITLE = "BBC broadcast, Cairo, 16 April 1939"
    }
}
