package com.example.pdfexternal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.widget.FrameLayout

/**
 * FrameLayout que "apaga" o próprio conteúdo (não uma View extra por cima) numa faixa
 * no topo, usando uma máscara de gradiente real via PorterDuff.Mode.DST_IN.
 *
 * Diferença crucial em relação a só desenhar uma cor semi-transparente por cima do
 * conteúdo: aqui o conteúdo em si é tornado transparente onde a máscara diz para
 * apagar - então o que aparece ali é o que estiver DESENHADO ATRÁS deste FrameLayout
 * na hierarquia (no caso deste app, o vidro líquido do cartão de configurações), não
 * uma cor "inventada" por cima.
 *
 * A faixa mascarada tem altura [maskZoneHeightPx]. Dentro dela:
 * - de 0 até [solidZoneFraction] (ex.: metade de cima): 100% apagado.
 * - de [solidZoneFraction] até o fim da faixa: gradiente suave de apagado até visível.
 * - abaixo da faixa: conteúdo normal, sem nenhuma alteração.
 */
class FadeMaskFrameLayout(context: Context) : FrameLayout(context) {

    var maskZoneHeightPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            gradientDirty = true
            invalidate()
        }

    var solidZoneFraction: Float = 0.5f
        set(value) {
            if (field == value) return
            field = value
            gradientDirty = true
            invalidate()
        }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var gradientDirty = true
    private var lastWidth = -1

    init {
        // Necessário para o saveLayer/PorterDuff funcionarem como esperado - sem uma
        // camada de software aqui, o compositing por hardware pode não aplicar a
        // máscara corretamente em todos os aparelhos.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != lastWidth) {
            lastWidth = w
            gradientDirty = true
        }
    }

    private fun rebuildGradientIfNeeded() {
        if (!gradientDirty || maskZoneHeightPx <= 0 || width <= 0) return
        gradientDirty = false
        maskPaint.shader = LinearGradient(
            0f, 0f, 0f, maskZoneHeightPx.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK),
            floatArrayOf(0f, solidZoneFraction.coerceIn(0f, 1f), 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (maskZoneHeightPx <= 0) {
            // Sem faixa configurada ainda - desenha normalmente, sem máscara nenhuma.
            super.dispatchDraw(canvas)
            return
        }

        rebuildGradientIfNeeded()

        val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), maskZoneHeightPx.toFloat(), maskPaint)
        canvas.restoreToCount(saveCount)
    }
}
