package com.example.pdfexternal

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.view.Display
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.rememberPageCurlState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Tipo de transição usado ao virar de página no display externo. */
enum class PageTransitionMode {
    /** Troca instantânea, sem nenhuma animação. */
    NONE,
    /** Desliza a página para o lado (a nova entra, a antiga sai). */
    SLIDE,
    /** Curvatura 3D real (efeito "Liquid"/pagecurl). */
    CURL
}

/**
 * Janela que roda FISICAMENTE no display externo (via Presentation API).
 * A tela do celular nunca mostra o conteúdo desta janela.
 *
 * A janela é criada UMA VEZ (assim que o display externo é detectado) e permanece
 * aberta enquanto o display estiver conectado. Trocar de PDF apenas troca o conteúdo
 * interno (loadPdf), sem recriar a janela - evitando o "piscar" da tela.
 *
 * O efeito de virar página com curvatura 3D real é feito pela biblioteca `pagecurl`
 * (Jetpack Compose). Como essa janela não é uma Activity, é necessário fornecer
 * manualmente os componentes de ciclo de vida que o Compose exige - ver
 * [PresentationLifecycleOwner].
 */
class PdfPresentation(
    context: Context,
    display: Display
) : Presentation(withAppLocale(context), display) {

    /** Reatribuível: a Activity que consome esse callback pode ser recriada (ex: troca de tema). */
    var onPageChanged: ((current: Int, total: Int) -> Unit)? = null

    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var currentPageIndex = 0

    private lateinit var windowRootView: View
    private lateinit var rootView: View
    private lateinit var layoutIdle: View
    private lateinit var tvIdleMessage: android.widget.TextView
    private lateinit var composeView: ComposeView

    // true quando o display físico conectado é landscape (a maioria dos monitores
    // portáteis/dock não gira sozinha) - nesse caso, rootView é girado 90° para
    // aproveitar a tela toda na vertical, como uma partitura pede.
    private var rotateForLandscapeDisplay = false

    // Sentido do giro acima. Depende de como o monitor foi fisicamente montado -
    // configurável pelo usuário nas configurações caso o texto apareça de cabeça
    // para baixo.
    private var invertRotationDirection = false

    private val lifecycleOwner = PresentationLifecycleOwner()

    // Curva "ease-in-out" para a transição de slide: começa devagar, acelera no
    // meio, desacelera no final - em vez da curva padrão do Compose (que já começa
    // rápido). Mesma calibragem usada antes nas outras transições suaves do app.
    private val slideEasing = CubicBezierEasing(0.1f, 0f, 0f, 1f)
    private val presentationExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        // Sem isso, qualquer erro dentro de curlNext()/curlPrev() (biblioteca pagecurl)
        // derruba o app inteiro, já que coroutines sem handler propagam a exceção para
        // o uncaught exception handler da thread principal.
        android.util.Log.e("PdfExternalDisplay", "Falha ao virar página", throwable)
    }
    private val presentationScope =
        CoroutineScope(Dispatchers.Main.immediate + SupervisorJob() + presentationExceptionHandler)

    private val bitmapCache = mutableMapOf<Int, Bitmap>()

    // Estado observado pelo Compose: contagem de páginas e uma "chave" do documento
    // atual (incrementada a cada loadPdf, força a PageCurl a recomeçar do zero ao
    // trocar de arquivo, em vez de manter a posição de rolagem do PDF anterior).
    private val pageCountState = mutableStateOf(0)
    private val docKeyState = mutableStateOf(0)

    // Índice da página atual, espelhado num estado do Compose para o modo "sem
    // efeito de curvatura" (troca instantânea de bitmap em vez de animação 3D).
    private val currentPageIndexState = mutableStateOf(0)

    // Controla que tipo de transição é usada ao virar página. Configurável pelo
    // usuário nas configurações.
    private val transitionModeState = mutableStateOf(PageTransitionMode.CURL)

    // Índice REAL (absoluto, no PDF) que corresponde à página "0" dentro da PageCurl
    // no momento em que ela foi (re)criada. Existe porque o estado interno da
    // PageCurl é perdido sempre que o composable dela sai da árvore (ex: ao
    // desativar o efeito) - ao reativar, uma instância nova nasceria do zero na
    // página 0 do documento, "voltando" a leitura para o início. Reancorando aqui
    // sempre que a curva (re)nasce, ela passa a recomeçar contando a partir da
    // página onde o usuário realmente está.
    private val curlBaseIndexState = mutableStateOf(0)

    /** Troca o tipo de transição usado ao virar página (nenhuma / slide / curvatura real). */
    fun setTransitionMode(mode: PageTransitionMode) {
        if (mode == PageTransitionMode.CURL && transitionModeState.value != PageTransitionMode.CURL) {
            // Reancora a curva na página atual antes de (re)ativar - sem isso ela
            // nasceria do zero na primeira página do documento.
            curlBaseIndexState.value = currentPageIndex
        }
        transitionModeState.value = mode
    }

    /**
     * Gira e redimensiona [rootView] para preencher a tela toda na vertical quando o
     * display físico conectado é landscape. Chamado uma vez na criação da janela e de
     * novo sempre que [setInvertRotation] muda o sentido do giro.
     */
    private fun applyDisplayRotation() {
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        rotateForLandscapeDisplay = metrics.widthPixels > metrics.heightPixels

        if (!rotateForLandscapeDisplay) {
            rootView.rotation = 0f
            rootView.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            return
        }

        // Troca largura/altura: um container de (altura x largura) do display, girado
        // 90°, passa a ocupar visualmente exatos (largura x altura) - preenchendo a
        // tela física landscape como se fosse um canvas vertical.
        rootView.layoutParams = android.widget.FrameLayout.LayoutParams(
            metrics.heightPixels,
            metrics.widthPixels
        ).apply {
            gravity = android.view.Gravity.CENTER
        }
        rootView.rotation = if (invertRotationDirection) -90f else 90f
    }

    /**
     * Inverte o sentido do giro de 90° usado quando o display físico é landscape (ver
     * [applyDisplayRotation]). Depende de como o monitor foi montado fisicamente -
     * configurável pelo usuário nas configurações caso o texto apareça de cabeça para
     * baixo.
     */
    fun setInvertRotation(invert: Boolean) {
        if (invert == invertRotationDirection) return
        invertRotationDirection = invert
        if (rotateForLandscapeDisplay) {
            rootView.rotation = if (invertRotationDirection) -90f else 90f
        }
    }

    // Percentual (0-20) de margem removida da página para "ampliar" o conteúdo -
    // pensado para partituras, onde as margens costumam ser desperdício de espaço
    // num display externo. Configurável pelo usuário nas configurações.
    private val zoomCropPercentState = mutableStateOf(0)

    /** Ajusta o zoom/crop da página (0-20%) e força a re-renderização das páginas. */
    fun setZoomCropPercent(percent: Int) {
        val clamped = percent.coerceIn(0, MAX_ZOOM_CROP_PERCENT)
        if (clamped == zoomCropPercentState.value) return
        zoomCropPercentState.value = clamped
        // As páginas já renderizadas (e cacheadas) foram cortadas com o crop antigo -
        // precisam ser descartadas para refletir o novo valor.
        bitmapCache.values.forEach { bmp -> if (!bmp.isRecycled) bmp.recycle() }
        bitmapCache.clear()
    }

    // "Zoom rápido": diferente do crop acima (que recorta e RE-RENDERIZA a página em
    // resolução maior), este é um botão de atalho que só amplia visualmente o que já
    // está na tela (uma escala aplicada por cima do bitmap existente, via Compose) -
    // por isso liga/desliga instantaneamente com uma animação suave, sem precisar
    // chamar o PdfRenderer de novo. O valor máximo (quanto amplia) é configurável nas
    // configurações, até MAX_QUICK_ZOOM_PERCENT.
    private val quickZoomEnabledState = mutableStateOf(false)
    private val quickZoomMaxPercentState = mutableStateOf(DEFAULT_QUICK_ZOOM_PERCENT)

    val isQuickZoomEnabled: Boolean
        get() = quickZoomEnabledState.value

    /** Define o quanto o botão de zoom rápido amplia quando ativado (1-10%). */
    fun setQuickZoomMaxPercent(percent: Int) {
        quickZoomMaxPercentState.value = percent.coerceIn(1, MAX_QUICK_ZOOM_PERCENT)
    }

    /** Liga/desliga o zoom rápido. Retorna o novo estado (true = ligado). */
    fun toggleQuickZoom(): Boolean {
        quickZoomEnabledState.value = !quickZoomEnabledState.value
        return quickZoomEnabledState.value
    }

    /**
     * Reaplica os textos localizados (ex: mensagem "Selecione uma partitura...").
     *
     * Necessário porque esta janela (Presentation) não é uma Activity - ela não é
     * recriada automaticamente quando o idioma do app muda (diferente da tela do
     * celular, que o AppCompatDelegate recria sozinho). Sem isso, o texto definido
     * aqui ficava "congelado" no idioma de quando a janela foi criada, mesmo depois
     * do usuário trocar o idioma nas configurações.
     */
    fun refreshLocalizedStrings() {
        tvIdleMessage.text = withAppLocale(context).getString(R.string.external_idle_message)
    }

    // Referência à função next()/prev() da própria biblioteca, capturada de dentro
    // da composição para poder ser chamada pelos botões do celular (fora do Compose).
    private var curlNext: (suspend () -> Unit)? = null
    private var curlPrev: (suspend () -> Unit)? = null

    // CoroutineScope obtido via rememberCoroutineScope() dentro da composição - é o
    // único que tem o MonotonicFrameClock necessário para animar o curl. Enquanto
    // não estiver disponível (ex: composição ainda não rodou), caímos de volta para
    // presentationScope só para não dar NPE - a chamada real vai falhar de forma
    // controlada (capturada pelo try/catch em nextPage/prevPage) em vez de crashar.
    private var animationScope: CoroutineScope? = null

    @OptIn(ExperimentalPageCurlApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_pdf)

        windowRootView = findViewById(R.id.presentationWindow)
        rootView = findViewById(R.id.presentationRoot)
        layoutIdle = findViewById(R.id.layoutIdle)
        tvIdleMessage = findViewById(R.id.tvIdleMessage)
        composeView = findViewById(R.id.composePageCurl)

        // IMPORTANTE: o Compose resolve o ViewTreeLifecycleOwner/ViewModelStoreOwner/
        // SavedStateRegistryOwner a partir de `composeView.rootView` (a raiz de TODA a
        // hierarquia de Views desta janela: windowRootView, o topo real anexado à
        // janela via setContentView), e não a partir do composeView em si. Por isso os
        // owners precisam ser setados aqui - setá-los só no composeView (um filho) faz
        // o Compose falhar com "ViewTreeLifecycleOwner not found" assim que a janela é
        // anexada ao display, pois nesse momento composeView.rootView aponta para
        // windowRootView.
        lifecycleOwner.handleCreate()
        windowRootView.setViewTreeLifecycleOwner(lifecycleOwner)
        windowRootView.setViewTreeViewModelStoreOwner(lifecycleOwner)
        windowRootView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        applyDisplayRotation()

        composeView.setContent {
            val docKey = docKeyState.value
            val count = pageCountState.value
            val transitionMode = transitionModeState.value
            val zoomCropPercent = zoomCropPercentState.value

            if (count > 0) {
                // Animação de entrada compartilhada pelos dois modos (com e sem
                // curvatura de página): antes era só um fade manual (alpha 0→1) feito
                // com um loop de delay's, e só existia no modo com curvatura. Agora é
                // uma única progressão (0f a 1f) que dirige opacidade + um deslize de
                // baixo para cima de uma vez, com uma curva de easing de verdade (em
                // vez do loop manual) - dá a sensação de "movimento", não só de
                // aparecer/sumir. Sem escala: só opacidade + posição.
                val enterProgress = remember(docKey) { Animatable(0f) }
                LaunchedEffect(docKey) {
                    enterProgress.snapTo(0f)
                    enterProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
                    )
                }
                val enterModifier = Modifier.graphicsLayer {
                    val progress = enterProgress.value
                    alpha = progress
                    // De baixo para cima: começa deslocado para baixo (positivo) e
                    // desliza até a posição final (0) conforme progride. Sem escala -
                    // só opacidade + deslize vertical.
                    translationY = (1f - progress) * 40.dp.toPx()
                }

                // "Zoom rápido": anima suavemente entre 1x e (1 + quickZoomMaxPercent%)
                // sempre que o botão é ligado/desligado. É só uma escala visual por
                // cima do bitmap já renderizado (ver comentário em quickZoomEnabledState),
                // então a mesma Animatable serve tanto para o zoom-in quanto o zoom-out -
                // só muda o alvo (targetValue).
                val quickZoomEnabled = quickZoomEnabledState.value
                val quickZoomMaxPercent = quickZoomMaxPercentState.value
                val quickZoomTarget = if (quickZoomEnabled) 1f + quickZoomMaxPercent / 100f else 1f
                val quickZoomScale = remember { Animatable(1f) }
                LaunchedEffect(quickZoomTarget) {
                    quickZoomScale.animateTo(
                        targetValue = quickZoomTarget,
                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                    )
                }
                val quickZoomModifier = Modifier.graphicsLayer {
                    scaleX = quickZoomScale.value
                    scaleY = quickZoomScale.value
                }

                when (transitionMode) {
                    PageTransitionMode.CURL -> {
                    val curlBaseIndex = curlBaseIndexState.value
                    // Tamanho "visível" para a PageCurl a partir da âncora: ela nunca
                    // enxerga páginas anteriores à âncora (ver comentário em
                    // curlBaseIndexState) - só as daqui pra frente no documento.
                    val relativeCount = (count - curlBaseIndex).coerceAtLeast(0)

                    // key(docKey, curlBaseIndex) força o Compose a descartar e recriar o
                    // curlState do zero sempre que o documento muda OU a âncora muda
                    // (ex: ao reativar o efeito, ou ao voltar página além da janela
                    // atual). Sem isso, rememberPageCurlState() seria reaproveitado
                    // indevidamente entre esses eventos.
                    val curlState = key(docKey, curlBaseIndex) { rememberPageCurlState() }
                    // Precisa ser o CoroutineScope da própria composição (tem o
                    // MonotonicFrameClock do Recomposer no contexto) - um CoroutineScope
                    // "cru" criado manualmente (ex: presentationScope) NÃO serve para
                    // chamar curlState.next()/prev(), pois eles animam com Animatable e
                    // isso exige esse clock, causando IllegalStateException se ausente.
                    val composeScope = rememberCoroutineScope()
                    LaunchedEffect(docKey, curlBaseIndex) {
                        animationScope = composeScope
                        curlNext = { curlState.next() }
                        curlPrev = { curlState.prev() }
                    }

                    PageCurl(
                        count = relativeCount,
                        state = curlState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(enterModifier)
                            .then(quickZoomModifier)
                    ) { relativeIndex ->
                        val realIndex = relativeIndex + curlBaseIndex
                        val bitmap = remember(realIndex, docKey, zoomCropPercent) { getOrRenderBitmap(realIndex) }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(ComposeColor.White),
                            contentAlignment = Alignment.Center
                        ) {
                            bitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                    }

                    PageTransitionMode.SLIDE -> {
                        // Modo slide: a página nova desliza por cima enquanto a antiga
                        // desliza para fora - AnimatedContent cuida de manter as duas
                        // (saindo/entrando) na tela ao mesmo tempo durante a transição.
                        curlNext = null
                        curlPrev = null

                        val pageIndex = currentPageIndexState.value
                        AnimatedContent(
                            targetState = pageIndex,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(enterModifier)
                                .then(quickZoomModifier),
                            transitionSpec = {
                                val duration = 600
                                val spec = tween<androidx.compose.ui.unit.IntOffset>(duration, easing = slideEasing)
                                if (targetState >= initialState) {
                                    // Avançando: a nova entra pela direita, a antiga
                                    // sai pela esquerda ("slide para a esquerda").
                                    slideInHorizontally(spec) { full -> full }
                                        .togetherWith(slideOutHorizontally(spec) { full -> -full })
                                } else {
                                    // Voltando: sentido contrário.
                                    slideInHorizontally(spec) { full -> -full }
                                        .togetherWith(slideOutHorizontally(spec) { full -> full })
                                }
                            },
                            label = "pageSlideTransition"
                        ) { index ->
                            val bitmap = remember(index, docKey, zoomCropPercent) { getOrRenderBitmap(index) }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(ComposeColor.White),
                                contentAlignment = Alignment.Center
                            ) {
                                bitmap?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }
                    }

                    PageTransitionMode.NONE -> {
                    // Modo sem efeito de curvatura: apenas troca o bitmap exibido
                    // instantaneamente, sem animação de dobra de página (mas com a
                    // mesma animação de entrada acima ao trocar de documento).
                    curlNext = null
                    curlPrev = null

                    val pageIndex = currentPageIndexState.value
                    val bitmap = remember(pageIndex, docKey, zoomCropPercent) { getOrRenderBitmap(pageIndex) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(enterModifier)
                            .then(quickZoomModifier)
                            .background(ComposeColor.White),
                        contentAlignment = Alignment.Center
                    ) {
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                    }
                }
            }
        }

        showIdleState()
    }

    val pageCount: Int
        get() = pdfRenderer?.pageCount ?: 0

    /** Página atualmente exibida (1-based), ou 0 se nenhum PDF está carregado. */
    val currentPage: Int
        get() = if (pageCount == 0) 0 else currentPageIndex + 1

    /** Carrega um novo PDF nesta mesma janela. */
    fun loadPdf(uri: Uri) {
        closeCurrentDocument()
        try {
            fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            fileDescriptor?.let {
                val renderer = PdfRenderer(it)
                pdfRenderer = renderer

                rootView.setBackgroundColor(Color.WHITE)
                layoutIdle.visibility = View.GONE
                // Garante que uma eventual animação de saída anterior (closeDocument)
                // não deixe o composeView "preso" translúcido/deslocado - a animação
                // de entrada em si (opacidade + escala + deslize) é toda feita dentro
                // do Compose (ver enterProgress/enterModifier no onCreate).
                composeView.alpha = 1f
                composeView.translationY = 0f
                composeView.visibility = View.VISIBLE

                currentPageIndex = 0
                currentPageIndexState.value = 0
                curlBaseIndexState.value = 0
                pageCountState.value = renderer.pageCount
                docKeyState.value += 1

                onPageChanged?.invoke(currentPageIndex + 1, renderer.pageCount)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Fecha o PDF atualmente aberto no display externo, voltando à tela ociosa (idle).
     *
     * Antes trocava instantaneamente (visibility GONE/VISIBLE na hora). Agora anima a
     * saída do conteúdo (fade + leve deslize para baixo) antes de soltar os recursos do
     * documento e mostrar a tela ociosa - a troca abrupta só acontece depois que o
     * conteúdo já sumiu da tela, então não é percebida.
     */
    fun closeDocument() {
        if (pdfRenderer == null) {
            // Nada carregado - não há o que animar, só garante o estado ocioso.
            showIdleState()
            onPageChanged?.invoke(0, 0)
            return
        }

        composeView.animate()
            .alpha(0f)
            .translationY(dpToPx(28f))
            .setDuration(220L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                closeCurrentDocument()
                // Reseta a transformação para a próxima vez que loadPdf() reaproveitar
                // esta mesma View (ela também é redefinida lá, mas por segurança).
                composeView.alpha = 1f
                composeView.translationY = 0f
                showIdleState()
                onPageChanged?.invoke(0, 0)
            }
            .start()
    }

    private fun showIdleState() {
        rootView.setBackgroundColor(Color.BLACK)
        composeView.visibility = View.GONE

        // Mesma ideia da entrada do PDF: um fade + leve deslize em vez de aparecer
        // abruptamente - fica consistente com a transição de saída acima.
        layoutIdle.alpha = 0f
        layoutIdle.translationY = dpToPx(16f)
        layoutIdle.visibility = View.VISIBLE
        layoutIdle.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        pageCountState.value = 0
        currentPageIndexState.value = 0
        curlBaseIndexState.value = 0
    }

    /** Conversão simples dp -> px usando a densidade deste display (externo). */
    private fun dpToPx(dp: Float): Float = dp * context.resources.displayMetrics.density

    fun nextPage() {
        val total = pageCount
        if (total == 0) return
        if (currentPageIndex < total - 1) {
            currentPageIndex++
            currentPageIndexState.value = currentPageIndex
            if (transitionModeState.value == PageTransitionMode.CURL) {
                (animationScope ?: presentationScope).launch {
                    try {
                        curlNext?.invoke()
                    } catch (e: Exception) {
                        android.util.Log.e("PdfExternalDisplay", "Falha em curlNext", e)
                    }
                }
            }
            onPageChanged?.invoke(currentPageIndex + 1, total)
        }
    }

    fun prevPage() {
        if (currentPageIndex > 0) {
            currentPageIndex--
            currentPageIndexState.value = currentPageIndex
            if (transitionModeState.value == PageTransitionMode.CURL) {
                if (currentPageIndex < curlBaseIndexState.value) {
                    // Saiu da janela que a PageCurl atual está cobrindo (ver comentário
                    // em curlBaseIndexState) - reancora nela mesma, o que força uma nova
                    // instância a nascer já na página certa, em vez de tentar animar
                    // para um índice negativo (inválido).
                    curlBaseIndexState.value = currentPageIndex
                } else {
                    (animationScope ?: presentationScope).launch {
                        try {
                            curlPrev?.invoke()
                        } catch (e: Exception) {
                            android.util.Log.e("PdfExternalDisplay", "Falha em curlPrev", e)
                        }
                    }
                }
            }
            onPageChanged?.invoke(currentPageIndex + 1, pageCount)
        }
    }

    /** Renderiza (ou reaproveita do cache) a página do PDF como bitmap ajustado ao display. */
    private fun getOrRenderBitmap(index: Int): Bitmap? {
        bitmapCache[index]?.let { return it }
        val renderer = pdfRenderer ?: return null
        if (index < 0 || index >= renderer.pageCount) return null

        // O PdfRenderer só permite UMA página aberta por vez (openPage/close não pode
        // sobrepor). O PageCurl pode acabar disparando renderizações de páginas vizinhas
        // em passes de composição próximos, então sincronizamos por segurança.
        return synchronized(renderer) {
            // Reconfere dentro da seção crítica: outra chamada concorrente pode já
            // ter renderizado e cacheado este índice enquanto esperávamos o lock.
            bitmapCache[index]?.let { return@synchronized it }

            var page: PdfRenderer.Page? = null
            try {
                page = renderer.openPage(index)
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)

                // Quando o display físico é landscape, giramos o conteúdo 90° (ver
                // applyDisplayRotation) para aproveitar a tela toda na vertical - então a
                // "largura disponível" real para a página é a ALTURA física do display
                // (e vice-versa), não o valor bruto de metrics.widthPixels/heightPixels.
                val availableWidth: Int
                val availableHeight: Int
                if (rotateForLandscapeDisplay) {
                    availableWidth = metrics.heightPixels
                    availableHeight = metrics.widthPixels
                } else {
                    availableWidth = metrics.widthPixels
                    availableHeight = metrics.heightPixels
                }

                // Fração da página (0 a 0.20) a remover das margens: metade de cada lado
                // (esquerda/direita, topo/baixo), mantendo o conteúdo central centralizado.
                val cropFraction = zoomCropPercentState.value / 100f
                val insetXRatio = cropFraction / 2f
                val insetYRatio = cropFraction / 2f
                val insetX = page.width * insetXRatio
                val insetY = page.height * insetYRatio
                val croppedWidth = (page.width - 2 * insetX).coerceAtLeast(1f)
                val croppedHeight = (page.height - 2 * insetY).coerceAtLeast(1f)

                val scale = minOf(
                    availableWidth.toFloat() / croppedWidth,
                    availableHeight.toFloat() / croppedHeight
                )
                val targetWidth = (croppedWidth * scale).toInt().coerceAtLeast(1)
                val targetHeight = (croppedHeight * scale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)

                if (cropFraction <= 0f) {
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } else {
                    // Mapeia apenas a região central (descartando as margens) para
                    // preencher o bitmap inteiro, "ampliando" o conteúdo da página.
                    val transform = android.graphics.Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(-insetX * scale, -insetY * scale)
                    }
                    page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }

                trimBitmapCache(around = index)
                bitmapCache[index] = bitmap
                bitmap
            } catch (e: Exception) {
                // Nunca deixamos uma falha de renderização (memória, página corrompida,
                // etc.) derrubar o app - a pior consequência deve ser uma página em branco.
                android.util.Log.e("PdfExternalDisplay", "Falha ao renderizar página $index", e)
                null
            } finally {
                try {
                    page?.close()
                } catch (e: Exception) {
                    android.util.Log.e("PdfExternalDisplay", "Falha ao fechar página $index", e)
                }
            }
        }
    }

    /**
     * Mantém no cache só as páginas próximas da atual (janela de +/- [KEEP_RADIUS]).
     * Sem isso, num display de alta resolução (ex: TV 4K) cada bitmap pode ocupar
     * dezenas de MB, e ir avançando página em página eventualmente estoura a memória
     * (OutOfMemoryError) e derruba o app.
     */
    private fun trimBitmapCache(around: Int) {
        val toRemove = bitmapCache.keys.filter { kotlin.math.abs(it - around) > KEEP_RADIUS }
        for (key in toRemove) {
            bitmapCache.remove(key)?.let { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    private companion object {
        const val KEEP_RADIUS = 3
        const val MAX_ZOOM_CROP_PERCENT = 20
        const val MAX_QUICK_ZOOM_PERCENT = 10
        const val DEFAULT_QUICK_ZOOM_PERCENT = 5

        /**
         * Retorna uma cópia de [base] com o idioma escolhido pelo usuário nas
         * configurações do app (via AppCompatDelegate) já aplicado explicitamente.
         *
         * Necessário porque [base] normalmente é o applicationContext: ao contrário do
         * contexto de uma Activity, ele NÃO é atualizado automaticamente pelo
         * AppCompatDelegate quando o idioma do app muda. Sem isso, os textos desta
         * janela (que roda no display externo) ficavam sempre no idioma do sistema,
         * ignorando a escolha feita nas configurações do app.
         */
        fun withAppLocale(base: Context): Context {
            val locales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return base // "Sistema" selecionado - segue o idioma do aparelho normalmente
            val locale = locales[0] ?: return base
            val config = android.content.res.Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }

    private fun closeCurrentDocument() {
        try {
            pdfRenderer?.close()
            fileDescriptor?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        pdfRenderer = null
        fileDescriptor = null
        currentPageIndex = 0
        bitmapCache.values.forEach { bmp -> if (!bmp.isRecycled) bmp.recycle() }
        bitmapCache.clear()
    }

    override fun onStop() {
        super.onStop()
        closeCurrentDocument()
        presentationScope.cancel()
        lifecycleOwner.handleDestroy()
    }
}
