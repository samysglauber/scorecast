package com.example.pdfexternal

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * Anima apenas o REPOSICIONAMENTO dos itens que permanecem na lista quando um item
 * é removido (efeito "reorganização": os itens de baixo sobem ocupando o espaço do
 * que saiu).
 *
 * As animações de entrada e saída do próprio item já são feitas manualmente pelo
 * [PdfListAdapter] (animateItemEntrance / animateItemExit), então aqui desativamos
 * as animações padrão de adicionar/remover do [RecyclerView.ItemAnimator].
 *
 * O reposicionamento em si é implementado na mão (em vez do animateMove() padrão do
 * DefaultItemAnimator), para poder usar uma curva de suavização própria. IMPORTANTE:
 * animateMove() só *registra* o movimento (guarda a posição inicial via translation
 * e devolve true); a animação de verdade só começa em runPendingAnimations() - é
 * assim que o RecyclerView sincroniza tudo quando várias mudanças chegam em
 * sequência rápida (ex: remover vários favoritos um atrás do outro). Disparar a
 * animação direto dentro de animateMove() (como numa primeira versão) pulava essa
 * sincronização e causava o comportamento bugado nesse cenário.
 */
class ReorderOnlyItemAnimator : DefaultItemAnimator() {

    private val moveEasing = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    init {
        moveDuration = 340L
    }

    private data class PendingMove(val holder: RecyclerView.ViewHolder)

    private val pendingMoves = mutableListOf<PendingMove>()
    private val activeMoves = mutableSetOf<RecyclerView.ViewHolder>()

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        dispatchAddFinished(holder)
        return false
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        dispatchRemoveFinished(holder)
        return false
    }

    override fun animateMove(
        holder: RecyclerView.ViewHolder,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int
    ): Boolean {
        val view = holder.itemView
        val deltaX = (toX - fromX).toFloat()
        val deltaY = (toY - fromY).toFloat()

        if (deltaX == 0f && deltaY == 0f) {
            dispatchMoveFinished(holder)
            return false
        }

        // Só posiciona a View no ponto de partida (via translation) - a animação
        // de verdade começa em runPendingAnimations(), não aqui.
        view.translationX = -deltaX
        view.translationY = -deltaY
        pendingMoves.add(PendingMove(holder))
        return true
    }

    override fun runPendingAnimations() {
        if (pendingMoves.isNotEmpty()) {
            val moves = ArrayList(pendingMoves)
            pendingMoves.clear()
            for (move in moves) {
                startMoveAnimation(move.holder)
            }
        }
        super.runPendingAnimations()
    }

    private fun startMoveAnimation(holder: RecyclerView.ViewHolder) {
        val view = holder.itemView
        activeMoves.add(holder)
        dispatchMoveStarting(holder)

        view.animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(moveDuration)
            .setInterpolator(moveEasing)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.animate().setListener(null)
                    activeMoves.remove(holder)
                    dispatchMoveFinished(holder)
                }

                override fun onAnimationCancel(animation: Animator) {
                    view.translationX = 0f
                    view.translationY = 0f
                }
            })
            .start()
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        // IMPORTANTE: só mexe na animação da View (cancelar) se ESTE animator (de
        // reposicionamento) for de fato quem está animando ela agora. Antes o cancel()
        // era incondicional, e como .animate() é um único objeto compartilhado por
        // View, isso cortava no meio qualquer OUTRA animação em andamento nessa mesma
        // View - por exemplo, animateItemEntrance() do PdfListAdapter, no meio de uma
        // troca de aba (Favoritos -> Todos). O resultado era um item parado com o
        // alpha "pela metade", de forma imprevisível dependendo do timing exato entre
        // as duas animações - exatamente o "às vezes aparece, geralmente não".
        val hadPendingMove = pendingMoves.removeAll { it.holder == item }
        val hadActiveMove = activeMoves.remove(item)
        val wasTracked = hadPendingMove || hadActiveMove
        if (wasTracked) {
            item.itemView.animate().cancel()
            dispatchMoveFinished(item)
        }
        super.endAnimation(item)
    }

    override fun isRunning(): Boolean {
        return pendingMoves.isNotEmpty() || activeMoves.isNotEmpty() || super.isRunning()
    }
}
