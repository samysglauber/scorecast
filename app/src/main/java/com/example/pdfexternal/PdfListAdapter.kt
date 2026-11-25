package com.example.pdfexternal

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.Keyframe
import android.content.res.ColorStateList
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PdfItem(
    val name: String,
    val uri: Uri,
    val lastModified: Long,
    val isFavorite: Boolean = false
)

class PdfListAdapter(
    private val items: MutableList<PdfItem> = mutableListOf(),
    private val onClick: (PdfItem) -> Unit,
    private val onToggleFavorite: (PdfItem) -> Unit,
    private val isShowingFavoritesOnly: () -> Boolean = { false }
) : RecyclerView.Adapter<PdfListAdapter.PdfViewHolder>() {

    // Locale.getDefault() (em vez de um Locale("pt","BR") fixo) para o formato da
    // data acompanhar a preferência de idioma do app - já que a API de idioma por
    // app ajusta o Locale padrão do processo, isso já resolve sozinho após trocar
    // o idioma (a Activity, e portanto este adapter, é recriada do zero nesse caso).
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    // Rastreia até onde a entrada em cascata já rodou. Zerado a cada submitList()
    // para a animação tocar de novo do início sempre que a lista muda de verdade
    // (troca de pasta, busca, filtro Todos/Favoritos, reordenação) - mas sem re-
    // animar item que já apareceu enquanto só se rola a lista.
    private val animatedPositions = mutableSetOf<Int>()

    private companion object {
        // Quantos itens do topo recebem o escalonamento (cascata) da entrada -
        // corresponde, grosso modo, ao que já cabe na tela no primeiro carregamento.
        // No retrato cabem mais itens na tela de cara, então a cascata cobre mais
        // itens (6); na paisagem, a coluna da lista é mais estreita/baixa, então
        // menos itens ficam visíveis de início - cascata mais curta (4) evita o
        // "buraco" perceptível descrito antes.
        const val CASCADE_VISIBLE_ITEM_COUNT_PORTRAIT = 6
        const val CASCADE_VISIBLE_ITEM_COUNT_LANDSCAPE = 4
    }

    class PdfViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvPdfName)
        val meta: TextView = view.findViewById(R.id.tvPdfMeta)
        val favorite: ImageView = view.findViewById(R.id.btnFavorite)
    }

    fun submitList(newItems: List<PdfItem>, replayEntranceAnimation: Boolean = true) {
        if (replayEntranceAnimation) {
            // Mudança "grande" de verdade (troca de pasta, busca, filtro, ordenação):
            // recomeça a cascata de entrada do zero, sem se preocupar em animar
            // reposicionamento item a item.
            animatedPositions.clear()
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
            return
        }

        // Mudança pontual (ex: desfavoritar um item): usa DiffUtil para descobrir
        // exatamente o que mudou, permitindo que o RecyclerView anime sozinho o
        // reposicionamento dos itens que ficam (sobem ocupando o espaço do que saiu),
        // em vez de só redesenhar tudo sem transição - ver ReorderOnlyItemAnimator.
        val oldItems = ArrayList(items)
        val diffCallback = object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldItems[oldItemPosition].uri == newItems[newItemPosition].uri
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldItems[oldItemPosition] == newItems[newItemPosition]
        }
        val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(diffCallback)
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf, parent, false)
        return PdfViewHolder(view)
    }

    /**
     * Chamado sempre que o RecyclerView devolve essa View ao pool de reciclagem, pra
     * reaproveitar depois em QUALQUER posição/item futuro. É o ponto certo pra
     * garantir que nada desta posição "vaze" pra frente: cancela a animação em
     * andamento e qualquer safety net pendente (ver animateItemEntrance) AGORA, em
     * vez de confiar que animateItemEntrance vá necessariamente rodar de novo nessa
     * mesma View antes de algo disparar. Sem isso, uma troca de aba rápida podia
     * reciclar uma View no meio da cascata (sem nunca cancelar/limpar o que estava
     * pendente nela), e esse resíduo só se manifestava mais tarde, quando a View já
     * tinha sido reaproveitada pra outra posição - exatamente o "pop"/piscar
     * intermitente relatado.
     */
    override fun onViewRecycled(holder: PdfViewHolder) {
        super.onViewRecycled(holder)
        val view = holder.itemView
        view.animate().cancel()
        (view.tag as? PendingEntrance)?.let { pending ->
            view.removeCallbacks(pending.start)
            view.removeCallbacks(pending.safetyNet)
        }
        view.tag = null
    }

    /**
     * Dispara a animação de entrada aqui (e não em onBindViewHolder) de propósito:
     * o RecyclerView chama onBindViewHolder também para linhas pré-carregadas FORA
     * da tela (prefetch, antecipando a rolagem) - se a animação rodasse ali, ela
     * podia terminar inteira enquanto a View ainda estava invisível. Quando a View
     * finalmente entrava na tela de verdade, a posição já estava marcada como
     * "vista" (animatedPositions), então ela só aparecia de repente, sem nenhuma
     * transição visível - e paradoxalmente, às vezes só passava a animar depois de
     * rolar e voltar, quando por acaso calhava de ser religada a uma posição ainda
     * não vista. onViewAttachedToWindow só dispara quando a View realmente vai
     * ficar visível, então é o sinal certo pra isso.
     */
    override fun onViewAttachedToWindow(holder: PdfViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            animateItemEntrance(holder.itemView, position)
        }
    }

    override fun onBindViewHolder(holder: PdfViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.meta.text = if (item.lastModified > 0) {
            holder.itemView.context.getString(R.string.pdf_meta_with_date, dateFormat.format(Date(item.lastModified)))
        } else {
            holder.itemView.context.getString(R.string.pdf_meta_plain)
        }
        applyFavoriteIcon(holder.favorite, item.isFavorite)

        // Clique próprio para não abrir o PDF (e sem ripple do próprio item por cima)
        // quando o usuário só quer favoritar.
        holder.favorite.setOnClickListener { view ->
            val newFavoriteState = !item.isFavorite
            // Ícone muda na hora, junto com o toque - feedback visual imediato, sem
            // esperar a persistência/atualização da lista completarem.
            applyFavoriteIcon(view as ImageView, newFavoriteState)

            // Se está desfavoritando um item enquanto a aba "Favoritos" está ativa,
            // ele vai sumir da lista - animamos a SAÍDA do item inteiro (efeito
            // puramente visual - a remoção de verdade da lista já acontece via o
            // refreshList() com debounce lá no MainActivity, ver toggleFavorite()).
            val willLeaveList = !newFavoriteState && isShowingFavoritesOnly()

            // IMPORTANTE: chama onToggleFavorite (persistência de verdade) JÁ, na
            // hora do toque - NÃO espera nenhuma animação terminar. Antes, isso só
            // rodava dentro do callback de "fim da animação de saída"; se o usuário
            // desfavoritasse vários PDFs rápido e trocasse de aba antes dessas
            // animações terminarem, a View era desanexada no meio do caminho (a troca
            // de aba dispara seu próprio notifyDataSetChanged) e esse callback podia
            // nunca disparar - a remoção nunca era salva de verdade, e o item
            // "voltava" da próxima vez que a aba Favoritos era reaberta.
            onToggleFavorite(item)

            playFavoritePulse(view) {
                if (willLeaveList) {
                    animateItemExit(holder.itemView) {}
                }
            }
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    /**
     * Animação de saída do item inteiro (usada quando ele vai sumir da lista, ex:
     * desfavoritar na aba Favoritos). Cresce um pouquinho primeiro - a "elasticidade"
     * - e só depois de dar tempo de ver esse "pop" é que a opacidade começa a cair,
     * junto com o encolhimento até sumir de vez.
     */
    /**
     * Animação de saída do item inteiro (usada quando ele vai sumir da lista, ex:
     * desfavoritar na aba Favoritos). Simples: some do fade e encolhe levemente ao
     * mesmo tempo - sem "zoom" (crescer antes de encolher) - na mesma
     * velocidade/curva da cascata de entrada (ver animateItemEntrance e
     * animateSettingsEntrance em MainActivity), pra sentir consistente com o resto.
     */
    private fun animateItemExit(view: View, onEnd: () -> Unit) {
        view.animate().cancel()
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f

        view.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.PathInterpolator(0f, 0f, 0.2f, 1f))
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            .start()
    }

    /** Guarda os dois Runnables pendentes de uma entrada em cascata numa View, pra
     *  poderem ser cancelados de vez se essa mesma View (reciclada) for reaproveitada
     *  antes deles dispararem - ver [animateItemEntrance]. */
    private class PendingEntrance(val start: Runnable, val safetyNet: Runnable)

    /**
     * Anima a entrada em cascata dos itens da lista: sobe, some do fade-in e cresce
     * levemente, um pouco depois do item anterior - mesma linguagem visual usada na
     * entrada do menu de configurações. Só toca uma vez por posição (ver
     * animatedPositions) para não repetir a cada rolagem/reciclagem de View.
     */
    private fun animateItemEntrance(view: View, position: Int) {
        // Cancela qualquer animação de saída ainda em andamento nessa View reciclada
        // (ex: o usuário desfavoritou rapidinho e o RecyclerView já reaproveitou a
        // View pra outra posição antes da saída terminar de tocar).
        view.animate().cancel()

        // Cancela também o início adiado e o "safety net" (mais abaixo) de uma
        // chamada ANTERIOR desta função nessa mesma View reciclada, se houver algum
        // ainda pendente.
        //
        // IMPORTANTE: o atraso escalonado da cascata (antes) usava
        // ViewPropertyAnimator.setStartDelay(). Só que existe um bug conhecido e
        // antigo do próprio Android: chamar .cancel() numa animação que ainda está
        // DENTRO do período de setStartDelay (ainda não começou a mexer de verdade
        // nas propriedades) nem sempre cancela esse início pendente - ele podia
        // disparar sozinho mais tarde, rodando POR CIMA da animação nova (que já
        // tinha cancelado e reiniciado a View) - duas animações mexendo no alpha/
        // translationY ao mesmo tempo. Era exatamente isso que causava o "piscar"/
        // duplicar ao trocar de aba antes da cascata anterior terminar: o início
        // adiado de um item de uma cascata mais antiga sobrevivia ao cancel() e
        // aparecia no meio da cascata nova.
        //
        // A correção: não usar setStartDelay() nativo nenhum - o atraso agora é
        // feito à mão via postDelayed (guardado abaixo), que É de fato cancelável
        // com removeCallbacks, ao contrário do bug do setStartDelay.
        (view.tag as? PendingEntrance)?.let { pending ->
            view.removeCallbacks(pending.start)
            view.removeCallbacks(pending.safetyNet)
        }
        view.tag = null

        if (!animatedPositions.add(position)) {
            // Posição já vista antes (não importa a ORDEM em que os binds
            // aconteceram - ao contrário de antes, que comparava com um único
            // "último visto" e podia se confundir se o RecyclerView vinculasse fora
            // de ordem, ex: mantendo a posição de rolagem entre trocas de aba) - não
            // repete a entrada, mas AINDA ASSIM garante que a View volte ao estado
            // normal (visível, tamanho normal). Sem isso, uma View que acabou de
            // tocar animateItemExit() (que termina em alpha=0/escala=0) poderia ser
            // reciclada pra esta posição e ficar "presa" invisível para sempre.
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }

        val cascadeVisibleItemCount = if (
            view.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) {
            CASCADE_VISIBLE_ITEM_COUNT_LANDSCAPE
        } else {
            CASCADE_VISIBLE_ITEM_COUNT_PORTRAIT
        }

        val delay = if (position < cascadeVisibleItemCount) position * 40L else 0L
        val duration = if (position < cascadeVisibleItemCount) 650L else 200L

        val density = view.resources.displayMetrics.density
        view.alpha = 0f
        view.translationY = 24f * density
        view.scaleX = 0.98f
        view.scaleY = 0.98f

        // Início da animação de verdade - roda na hora (delay == 0) ou adiado à mão
        // (ver comentário acima sobre o porquê de não usar setStartDelay nativo).
        val start = Runnable {
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(duration)
                // Curva "decelerate" padrão do Material: final de movimento mais
                // suave, sem "travar" de repente. Mesmo tempo/curva do menu de
                // configurações.
                .setInterpolator(android.view.animation.PathInterpolator(0f, 0f, 0.2f, 1f))
                .start()
        }

        // Rede de segurança: se essa animação for cancelada/interrompida no meio do
        // caminho por QUALQUER motivo (reciclagem rápida durante rolagem, outro
        // sistema de animação mexendo na mesma View, um notifyDataSetChanged
        // concorrente etc.), sem isso o item ficava "preso" com alpha parcial/zero
        // até ser reciclado de novo (por isso rolar e voltar "consertava"). Forçamos
        // o estado final visível um pouco depois de quando a animação normal já
        // deveria ter terminado - se ela já tiver terminado sozinha, isso não faz
        // diferença nenhuma (os valores já são esses mesmos).
        val safetyNet = Runnable {
            // Só corrige se algo realmente ficou "preso" no meio do caminho - se a
            // animação já terminou sozinha (o caso normal, quase sempre), os valores
            // já são exatamente esses, então não fazemos nada e não interferimos em
            // nada na fluidez.
            if (view.alpha != 1f || view.translationY != 0f || view.scaleX != 1f || view.scaleY != 1f) {
                view.alpha = 1f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
            }
            view.tag = null
        }

        // Guarda os dois juntos na tag, pra uma futura chamada desta função nessa
        // mesma View reciclada conseguir cancelar ambos de uma vez (ver início da
        // função). Só escalona (atraso maior a cada item) os primeiros da lista - os
        // que já aparecem na tela quando ela carrega. A partir daí, item revelado por
        // rolagem aparece IMEDIATAMENTE, sem atraso nenhum - antes, todo item além do
        // teto usava sempre o mesmo atraso máximo (bem alto), então rolar até eles
        // fazia parecer que "não apareciam": ficavam invisíveis esperando aquele
        // atraso fixo toda vez, e quem rola rápido nunca dava tempo dele terminar de
        // entrar.
        view.tag = PendingEntrance(start, safetyNet)
        if (delay > 0) {
            view.postDelayed(start, delay)
        } else {
            start.run()
        }
        view.postDelayed(safetyNet, delay + duration + 150L)
    }

    private fun applyFavoriteIcon(view: ImageView, isFavorite: Boolean) {
        // Um único desenho (ic_note_filled) para os dois estados - só a cor muda
        // (cinza neutro / dourado). Evitar uma segunda versão "contorno" própria
        // pra esse glifo pequeno e detalhado, que é mais fácil de sair malformada.
        view.setImageResource(R.drawable.ic_note_filled)
        view.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(view.context, if (isFavorite) R.color.favorite_star else R.color.text_secondary)
        )
        view.contentDescription = view.context.getString(
            if (isFavorite) R.string.favorite_remove_description else R.string.favorite_add_description
        )
    }

    /**
     * Pop simples e suave: cresce um pouco e volta ao normal, numa única curva -
     * sem apertar, sem repique, sem múltiplos keyframes.
     */
    private fun playFavoritePulse(view: View, onEnd: () -> Unit) {
        view.animate().cancel()
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
        view.scaleX = 1f
        view.scaleY = 1f

        val scaleX = PropertyValuesHolder.ofKeyframe(
            View.SCALE_X,
            Keyframe.ofFloat(0f, 1f),
            Keyframe.ofFloat(0.5f, 1.22f),
            Keyframe.ofFloat(1f, 1f)
        )
        val scaleY = PropertyValuesHolder.ofKeyframe(
            View.SCALE_Y,
            Keyframe.ofFloat(0f, 1f),
            Keyframe.ofFloat(0.5f, 1.22f),
            Keyframe.ofFloat(1f, 1f)
        )
        ObjectAnimator.ofPropertyValuesHolder(view, scaleX, scaleY).apply {
            duration = 240L
            interpolator = android.view.animation.PathInterpolator(0.33f, 0f, 0.2f, 1f)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            start()
        }
    }

    override fun getItemCount(): Int = items.size
}

