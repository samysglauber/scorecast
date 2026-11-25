package com.example.pdfexternal

import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Switch estilo "pill" (trilho azul/cinza + polegar branco) implementado só com Views
 * comuns e drawables próprios do app, sem depender do MaterialSwitch/SwitchCompat.
 *
 * Motivo: o MaterialSwitch (Material 3) exige atributos de tema (colorOutline,
 * colorSurfaceContainerHighest etc.) que este app - baseado em
 * Theme.MaterialComponents (Material 2) - não define. Mesmo envolvendo a criação
 * num ThemeOverlay.Material3, o widget não desenhava NADA (nem crashava, só ficava
 * invisível) em testes reais no dispositivo. Esta classe evita esse problema por
 * completo: é só um FrameLayout com um fundo (trilho) e um círculo (polegar) que
 * a gente mesmo desenha e anima, então sempre aparece, em qualquer tema.
 */
class SimpleSwitch(context: Context) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    private val trackWidth = dp(44)
    private val trackHeight = dp(26)
    // Margem visível ao redor da bolinha (não preenche a altura toda do trilho) -
    // 3dp de folga em cima/embaixo/nas laterais.
    private val thumbMargin = dp(3)
    private val thumbSize = trackHeight - (thumbMargin * 2)

    private val thumb: View = ImageView(context).apply {
        setBackgroundResource(R.drawable.bg_pill_thumb)
        // Sem elevação/sombra aqui - o One UI é um estilo bem "chapado" (flat),
        // sem esse acabamento 3D/glossy.
    }

    private var thumbAnimator: ValueAnimator? = null
    private var listener: ((Boolean) -> Unit)? = null

    /** Muda o estado sem disparar o listener (para configuração inicial). */
    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            render(animate = isLaidOut)
        }

    init {
        layoutParams = LayoutParams(trackWidth, trackHeight)
        addView(
            thumb,
            LayoutParams(thumbSize, thumbSize).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = thumbMargin
            }
        )
        render(animate = false)
    }

    /** Alterna o estado e notifica o listener (usado pelo clique da linha inteira). */
    fun toggle() {
        isChecked = !isChecked
        listener?.invoke(isChecked)
    }

    fun setOnCheckedChangeListener(onChange: (Boolean) -> Unit) {
        listener = onChange
    }

    private fun render(animate: Boolean) {
        setBackgroundResource(if (isChecked) R.drawable.bg_pill_track_on else R.drawable.bg_pill_track_off)

        val startX = thumb.translationX
        // A posição base da bolinha já inclui marginStart = thumbMargin (a folga da
        // esquerda). A translação soma-se por cima dessa posição - por isso, para
        // sobrar a MESMA margem do lado direito quando ligado, é preciso descontar o
        // thumbMargin DUAS vezes (uma de cada lado), não uma.
        val endX = if (isChecked) (trackWidth - thumbSize - thumbMargin * 2).toFloat() else 0f

        thumbAnimator?.cancel()
        if (animate) {
            thumbAnimator = ValueAnimator.ofFloat(startX, endX).apply {
                duration = 150L
                addUpdateListener { thumb.translationX = it.animatedValue as Float }
                start()
            }
        } else {
            thumb.translationX = endX
        }
    }
}
