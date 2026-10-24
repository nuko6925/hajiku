package io.github.nuko6925.flickkb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * キーボードの背景 (標準 / 単色 / 画像)。
 * 画像は選んだ時に縮小してアプリ内 (files/kb_bg.jpg) にコピーする (元の画像が消えても使えるように)
 */
object KbBackground {
    const val DEFAULT = 0
    const val COLOR = 1
    const val IMAGE = 2

    private const val FILE = "kb_bg.jpg"
    private const val MAX_SIDE = 1600

    class Spec(
        val mode: Int,
        val color: Int,
        val bitmap: Bitmap?,
        /** 0〜1。背景の上に重ねる幕の濃さ */
        val scrim: Float,
        /** キー・文字をダーク配色にするか。null なら端末のダークモードに従う */
        val dark: Boolean?,
    )

    private var cached: Bitmap? = null
    private var cachedStamp = 0L

    fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** IME が表示のたびに呼ぶ。画像はファイルが変わった時だけ読み直す */
    fun load(ctx: Context): Spec {
        val mode = Settings.bgMode(ctx)
        val scrim = Settings.bgScrim(ctx) / 100f
        return when (mode) {
            COLOR -> {
                val c = Settings.bgColor(ctx)
                Spec(COLOR, c, null, 0f, luminance(c) < 0.5f)
            }
            IMAGE -> {
                val bmp = bitmap(ctx) ?: return Spec(DEFAULT, 0, null, 0f, null)
                val lum = Settings.bgImageLum(ctx)
                // 幕は画像が暗ければ黒、明るければ白 (読みやすい方へ寄せる)
                Spec(IMAGE, 0, bmp, scrim, lum < 0.5f)
            }
            else -> Spec(DEFAULT, 0, null, 0f, null)
        }
    }

    private fun bitmap(ctx: Context): Bitmap? {
        val f = file(ctx)
        if (!f.exists()) return null
        val stamp = f.lastModified()
        if (cached == null || cachedStamp != stamp) {
            cached = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
            cachedStamp = stamp
        }
        return cached
    }

    /**
     * 選んだ画像を縮小して保存し、平均の明るさを記録する。重いので UI スレッド以外で
     */
    fun saveImage(ctx: Context, uri: Uri) {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val k = MAX_SIDE.toFloat() / max(w, h)
            if (k < 1f) dec.setTargetSize((w * k).roundToInt().coerceAtLeast(1), (h * k).roundToInt().coerceAtLeast(1))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val lum = averageLuminance(bmp)
        bmp.recycle()
        if (!tmp.renameTo(file(ctx))) {
            tmp.copyTo(file(ctx), overwrite = true)
            tmp.delete()
        }
        Settings.setBgImageLum(ctx, lum)
        Settings.setBgMode(ctx, IMAGE)
    }

    private fun averageLuminance(b: Bitmap): Float {
        val s = Bitmap.createScaledBitmap(b, 16, 16, true)
        var sum = 0f
        for (y in 0 until 16) for (x in 0 until 16) sum += luminance(s.getPixel(x, y))
        if (s !== b) s.recycle()
        return sum / 256f
    }

    /** 0〜1 (sRGB の簡易輝度) */
    fun luminance(c: Int) =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
}

/** 角丸パネルの背景: 単色 → 画像 (中央で切り抜き) → 幕 の順に描く */
class PanelBackground : Drawable() {
    var color = Color.TRANSPARENT
        set(v) { field = v; invalidateSelf() }
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidateSelf() }
    var scrim = Color.TRANSPARENT
        set(v) { field = v; invalidateSelf() }
    var cornerRadii = FloatArray(8)
        set(v) { field = v; invalidateSelf() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val srcRect = Rect()

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        path.reset()
        path.addRoundRect(rect, cornerRadii, Path.Direction.CW)
        c.save()
        c.clipPath(path)
        c.drawColor(color)
        bitmap?.let { bmp ->
            // 中央で切り抜いて隙間なく敷く (center crop)
            val k = max(b.width().toFloat() / bmp.width, b.height().toFloat() / bmp.height)
            val sw = (b.width() / k).roundToInt().coerceAtMost(bmp.width)
            val sh = (b.height() / k).roundToInt().coerceAtMost(bmp.height)
            val sx = (bmp.width - sw) / 2
            val sy = (bmp.height - sh) / 2
            srcRect.set(sx, sy, sx + sw, sy + sh)
            c.drawBitmap(bmp, srcRect, b, paint)
        }
        if (Color.alpha(scrim) > 0) c.drawColor(scrim)
        c.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    @Deprecated("Drawable の既定")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
