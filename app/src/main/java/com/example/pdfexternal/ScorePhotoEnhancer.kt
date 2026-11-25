package com.example.pdfexternal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * Pós-processamento de fotos focado especificamente em partituras musicais:
 * 1. Endireitamento sub-grau (deskew) baseado no perfil de projeção horizontal das pautas.
 * 2. Nivelamento de iluminação do papel em blocos (percentil 90) sem halos em acordes densos.
 * 3. Microcontraste fino (Unsharp Mask apenas para escurecer traços) + reforço direcional
 *    horizontal para proteger linhas finas de pauta e linhas suplementares.
 * 4. Curva de níveis suave controlada por intensidade (0..100), preservando antialiasing.
 */
object ScorePhotoEnhancer {

    enum class EnhanceMode {
        /** Pipeline dedicado a partituras (papel branco + proteção de pautas). */
        CLEAN_SCORE,
        /** Mantém a foto original capturada pelo scanner em resolução máxima. */
        ORIGINAL
    }

    const val DEFAULT_INTENSITY = 45

    private const val PREVIEW_MAX_DIM = 1200
    private const val EXPORT_MAX_DIM = 3600
    private const val TILE_SIZE = 32
    private const val DESKEW_SAMPLE_MAX_DIM = 420

    suspend fun renderPreviewBitmap(
        context: Context,
        uri: Uri,
        mode: EnhanceMode,
        intensityPercent: Int = DEFAULT_INTENSITY
    ): Bitmap = withContext(Dispatchers.Default) {
        val decoded = decodeScaledBitmap(context, uri, PREVIEW_MAX_DIM)
        if (mode == EnhanceMode.ORIGINAL) {
            return@withContext decoded
        }
        try {
            processSheetMusicBitmap(decoded, intensityPercent)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    suspend fun buildPdfFromScannedPages(
        context: Context,
        pageUris: List<Uri>,
        mode: EnhanceMode,
        intensityPercent: Int = DEFAULT_INTENSITY,
        outputStream: OutputStream,
        onProgress: (currentPage: Int, totalPages: Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.Default) {
        val pdfDocument = PdfDocument()

        try {
            val total = pageUris.size
            pageUris.forEachIndexed { index, uri ->
                withContext(Dispatchers.Main) {
                    onProgress(index + 1, total)
                }

                val decoded = decodeScaledBitmap(context, uri, EXPORT_MAX_DIM)
                val pageBitmap = if (mode == EnhanceMode.CLEAN_SCORE) {
                    try {
                        processSheetMusicBitmap(decoded, intensityPercent)
                    } finally {
                        if (!decoded.isRecycled) decoded.recycle()
                    }
                } else {
                    decoded
                }

                try {
                    // Dimensões nativas da imagem (1:1 sem reamostragem) para máxima nitidez
                    val pageWidth = max(1, pageBitmap.width)
                    val pageHeight = max(1, pageBitmap.height)
                    val pageInfo = PdfDocument.PageInfo.Builder(
                        pageWidth,
                        pageHeight,
                        index + 1
                    ).create()
                    val page = pdfDocument.startPage(pageInfo)
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(pageBitmap, 0f, 0f, null)
                    pdfDocument.finishPage(page)
                } finally {
                    if (!pageBitmap.isRecycled) pageBitmap.recycle()
                }
            }

            withContext(Dispatchers.IO) {
                pdfDocument.writeTo(outputStream)
                outputStream.flush()
            }
        } finally {
            pdfDocument.close()
        }
    }

    private fun decodeScaledBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val srcW = info.size.width
            val srcH = info.size.height
            val maxSide = max(srcW, srcH)
            if (maxSide > maxDim && maxSide > 0) {
                val scale = maxDim.toFloat() / maxSide
                decoder.setTargetSize(
                    max(1, (srcW * scale).toInt()),
                    max(1, (srcH * scale).toInt())
                )
            }
        }
    }

    private fun processSheetMusicBitmap(src: Bitmap, intensityPercent: Int): Bitmap {
        val deskewed = deskewByStaffProjection(src)
        return try {
            enhanceSheetMusic(deskewed, intensityPercent.coerceIn(0, 100) / 100f)
        } finally {
            if (deskewed !== src && !deskewed.isRecycled) {
                deskewed.recycle()
            }
        }
    }

    /**
     * Detecta micro-inclinações (-2.0° a +2.0°) usando a variância da projeção horizontal
     * de pixels escuros (pautas perfeitamente horizontais geram picos bem definidos).
     * Só rotaciona se o ganho sobre 0° for claro (> 6%), evitando entortar páginas sem pauta.
     */
    private fun deskewByStaffProjection(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val maxSide = max(w, h)
        if (w < 100 || h < 100) return src

        val scale = min(1f, DESKEW_SAMPLE_MAX_DIM.toFloat() / maxSide)
        val sw = max(32, (w * scale).toInt())
        val sh = max(32, (h * scale).toInt())

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val darkness = FloatArray(sw * sh)
        for (sy in 0 until sh) {
            val srcY = (sy * h) / sh
            val srcRow = srcY * w
            val dstRow = sy * sw
            for (sx in 0 until sw) {
                val srcX = (sx * w) / sw
                val px = pixels[srcRow + srcX]
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                val lum = (77 * r + 150 * g + 29 * b) ushr 8
                darkness[dstRow + sx] = max(0f, 195f - lum)
            }
        }

        val cx = sw * 0.5f
        val yMargin = max(2, (sh * 0.06f).toInt())
        val rowSums = DoubleArray(sh)

        fun scoreAngle(angleDeg: Float): Double {
            rowSums.fill(0.0)
            val slope = tan(Math.toRadians(angleDeg.toDouble())).toFloat()
            for (sy in yMargin until sh - yMargin) {
                val rowOff = sy * sw
                for (sx in 0 until sw) {
                    val d = darkness[rowOff + sx]
                    if (d <= 0f) continue
                    val yRot = (sy - (sx - cx) * slope).toInt()
                    if (yRot in yMargin until sh - yMargin) {
                        rowSums[yRot] += d
                    }
                }
            }
            var sum = 0.0
            val count = sh - 2 * yMargin
            for (y in yMargin until sh - yMargin) sum += rowSums[y]
            val mean = sum / max(1, count)
            var variance = 0.0
            for (y in yMargin until sh - yMargin) {
                val diff = rowSums[y] - mean
                variance += diff * diff
            }
            return variance / max(1, count)
        }

        val zeroScore = scoreAngle(0f)
        if (zeroScore <= 0.0) return src

        var bestAngle = 0f
        var bestScore = zeroScore
        var angle = -2.0f
        while (angle <= 2.001f) {
            if (abs(angle) >= 0.24f) {
                val s = scoreAngle(angle)
                if (s > bestScore) {
                    bestScore = s
                    bestAngle = angle
                }
            }
            angle += 0.25f
        }

        if (abs(bestAngle) < 0.25f || bestScore <= zeroScore * 1.06) {
            return src
        }

        val rotated = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rotated)
        canvas.drawColor(Color.WHITE)
        val matrix = Matrix().apply {
            setRotate(-bestAngle, w * 0.5f, h * 0.5f)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, matrix, paint)
        return rotated
    }

    private fun enhanceSheetMusic(src: Bitmap, intensity: Float): Bitmap {
        val w = src.width
        val h = src.height
        val size = w * h
        val pixels = IntArray(size)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val lum = IntArray(size)
        for (i in 0 until size) {
            val px = pixels[i]
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            lum[i] = (77 * r + 150 * g + 29 * b) ushr 8
        }

        // 1. Estimativa do papel por blocos (percentil 90 para não criar halos em acordes)
        val cols = max(1, ceil(w.toDouble() / TILE_SIZE).toInt())
        val rows = max(1, ceil(h.toDouble() / TILE_SIZE).toInt())
        val rawBg = FloatArray(cols * rows)
        val tileHist = IntArray(256)

        for (ty in 0 until rows) {
            val yStart = ty * TILE_SIZE
            val yEnd = min(h, yStart + TILE_SIZE)
            for (tx in 0 until cols) {
                val xStart = tx * TILE_SIZE
                val xEnd = min(w, xStart + TILE_SIZE)
                tileHist.fill(0)
                var count = 0
                var y = yStart
                while (y < yEnd) {
                    val rowOffset = y * w
                    var x = xStart
                    while (x < xEnd) {
                        tileHist[lum[rowOffset + x]]++
                        count++
                        x += 2
                    }
                    y += 2
                }
                val target = max(1, (count * 0.90f).toInt())
                var acc = 0
                var p90 = 238
                for (v in 0..255) {
                    acc += tileHist[v]
                    if (acc >= target) {
                        p90 = v
                        break
                    }
                }
                rawBg[ty * cols + tx] = max(90, p90).toFloat()
            }
        }

        val smoothBg = FloatArray(cols * rows)
        for (ty in 0 until rows) {
            for (tx in 0 until cols) {
                var sum = 0f
                var cnt = 0
                for (dy in -1..1) {
                    val ny = ty + dy
                    if (ny !in 0 until rows) continue
                    for (dx in -1..1) {
                        val nx = tx + dx
                        if (nx !in 0 until cols) continue
                        sum += rawBg[ny * cols + nx]
                        cnt++
                    }
                }
                smoothBg[ty * cols + tx] = sum / cnt
            }
        }

        val norm = FloatArray(size)
        val invTile = 1f / TILE_SIZE
        for (y in 0 until h) {
            val fy = (y * invTile - 0.5f).coerceIn(0f, (rows - 1).toFloat())
            val ty0 = fy.toInt()
            val ty1 = min(rows - 1, ty0 + 1)
            val wy = fy - ty0
            val rowOffset = y * w

            for (x in 0 until w) {
                val fx = (x * invTile - 0.5f).coerceIn(0f, (cols - 1).toFloat())
                val tx0 = fx.toInt()
                val tx1 = min(cols - 1, tx0 + 1)
                val wx = fx - tx0

                val bg00 = smoothBg[ty0 * cols + tx0]
                val bg10 = smoothBg[ty0 * cols + tx1]
                val bg01 = smoothBg[ty1 * cols + tx0]
                val bg11 = smoothBg[ty1 * cols + tx1]

                val top = bg00 + (bg10 - bg00) * wx
                val bot = bg01 + (bg11 - bg01) * wx
                val localPaper = top + (bot - top) * wy

                norm[rowOffset + x] = ((lum[rowOffset + x] * 255f) / localPaper).coerceIn(0f, 255f)
            }
        }

        // 2. Limpeza suave de ruído JPEG apenas nas áreas claras do papel (> 185),
        // evitando borrões nos traços escuros e eliminando manchas entre as pautas
        val cleaned = FloatArray(size)
        for (y in 0 until h) {
            val rowOff = y * w
            val canSmooth = y in 1 until (h - 1)
            for (x in 0 until w) {
                val idx = rowOff + x
                val v = norm[idx]
                if (!canSmooth || x !in 1 until (w - 1) || v < 185f) {
                    cleaned[idx] = v
                    continue
                }
                // Média suave apenas com vizinhos também claros (edge-preserving, sem alocação)
                var sum = v * 2f
                var weight = 2f
                val left = norm[idx - 1]
                if (abs(left - v) < 22f) { sum += left; weight += 1f }
                val right = norm[idx + 1]
                if (abs(right - v) < 22f) { sum += right; weight += 1f }
                val up = norm[idx - w]
                if (abs(up - v) < 22f) { sum += up; weight += 1f }
                val down = norm[idx + w]
                if (abs(down - v) < 22f) { sum += down; weight += 1f }
                cleaned[idx] = sum / weight
            }
        }

        // 3. Curva de níveis suave e não-destrutiva: mantém o antialiasing natural
        // nas bordas das notas, ligaduras e textos, sem deixar aspecto serrilhado/duro
        val blackPoint = 8f + 22f * intensity
        val whitePoint = 248f - 16f * intensity
        val range = max(1f, whitePoint - blackPoint)
        val gamma = 1.0f + 0.25f * intensity

        val lut = IntArray(256)
        for (v in 0..255) {
            val t = ((v - blackPoint) / range).coerceIn(0f, 1f)
            // Soft-knee suave no limite branco para evitar degrau brusco no antialiasing
            val shaped = t.pow(gamma)
            val g = (shaped * 255f).toInt().coerceIn(0, 255)
            lut[v] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }

        for (y in 0 until h) {
            val rowOff = y * w
            val canCheckStaff = y in 3 until (h - 3)
            for (x in 0 until w) {
                val idx = rowOff + x
                var v = cleaned[idx]

                // Proteção sutil apenas para linhas horizontais reais e contínuas (pautas
                // finas), sem amplificar ruído claro entre os espaços da pauta
                if (canCheckStaff && x in 2 until (w - 2) && v < 218f) {
                    val above = (cleaned[idx - 3 * w] + cleaned[idx - 2 * w]) * 0.5f
                    val below = (cleaned[idx + 2 * w] + cleaned[idx + 3 * w]) * 0.5f
                    val vertRef = min(above, below)
                    val vertContrast = vertRef - v
                    if (vertContrast > 18f) {
                        val horizAvg = (cleaned[idx - 1] + cleaned[idx + 1]) * 0.5f
                        if (vertRef - horizAvg > 14f) {
                            v -= vertContrast * (0.22f * intensity)
                        }
                    }
                }

                val clamped = v.toInt().coerceIn(0, 255)
                pixels[idx] = lut[clamped]
            }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}
