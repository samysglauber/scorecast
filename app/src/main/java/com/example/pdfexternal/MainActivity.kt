package com.example.pdfexternal

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.liquid
import io.github.fletchmckee.liquid.rememberLiquidState

class MainActivity : AppCompatActivity() {

    // Os rótulos ficam como resource IDs (não strings prontas) justamente para
    // resolver com getString() na hora de mostrar - são enum constants, carregadas
    // uma única vez por processo; se fossem strings fixas, ficariam presas no idioma
    // de quando o app abriu pela primeira vez, ignorando qualquer troca de idioma
    // feita depois (mesmo com a Activity recriada, o enum em si não é recarregado).
    private enum class SortOption(
        @androidx.annotation.StringRes val menuLabelRes: Int,
        @androidx.annotation.StringRes val shortLabelRes: Int,
        val ascending: Boolean
    ) {
        SETLIST_ORDER(R.string.sort_option_setlist, R.string.sort_label_setlist, true),
        NAME_ASC(R.string.sort_option_name_asc, R.string.sort_label_name, true),
        NAME_DESC(R.string.sort_option_name_desc, R.string.sort_label_name, false),
        NEWEST_FIRST(R.string.sort_option_newest, R.string.sort_label_date, false),
        OLDEST_FIRST(R.string.sort_option_oldest, R.string.sort_label_date, true)
    }

    companion object {
        const val PREFS_NAME = "pdf_external_prefs"
        const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_VOLUME_PAGING = "volume_paging_enabled"
        private const val KEY_PAGE_TRANSITION_MODE = "page_transition_mode"
        private const val KEY_ZOOM_CROP_PERCENT = "zoom_crop_percent"
        private const val KEY_INVERT_EXTERNAL_ROTATION = "invert_external_rotation"
        private const val MAX_ZOOM_CROP_PERCENT = 20
        private const val KEY_QUICK_ZOOM_MAX_PERCENT = "quick_zoom_max_percent"
        private const val MAX_QUICK_ZOOM_PERCENT = 10
        private const val DEFAULT_QUICK_ZOOM_PERCENT = 5
        private const val KEY_FAVORITE_URIS = "favorite_pdf_uris"
        private const val KEY_SETLIST_ORDER = "setlist_order_json"
        // Espera de "silêncio" antes de reconstruir a lista após favoritar/
        // desfavoritar - um pouco mais longa que a duração da animação de
        // reposicionamento (ReorderOnlyItemAnimator.moveDuration = 340ms), pra
        // garantir que ela sempre termine antes da próxima reorganização começar.
        private const val LIST_REFRESH_DEBOUNCE_MS = 420L

        // Ajuste fino do recorte da screenshot usada como fundo do vidro líquido do
        // diálogo de configurações (ver buildAndShowSettingsDialog) - desloca a
        // origem do recorte alguns pixels pra cima pra corrigir um leve
        // desalinhamento residual observado na prática. Se ainda notar desvio,
        // é só ajustar este número (positivo = sobe mais, negativo = desce).
        private const val BACKDROP_VERTICAL_NUDGE_DP = -27

        // Fração da tela ocupada pelo cartão de configurações (largura/altura). Menor
        // que 1 = mais margem em volta. A altura foi reduzida de 0.85 pra dar uma
        // margem maior especificamente embaixo (ver CARD_UPWARD_SHIFT_DP logo abaixo,
        // que soma a essa margem extra deslocando o cartão inteiro pra cima).
        private const val CARD_WIDTH_FRACTION = 0.85f
        private const val CARD_HEIGHT_FRACTION = 0.80f

        // Desloca o cartão inteiro (e o recorte da screenshot que serve de fundo a
        // ele, em buildAndShowSettingsDialog) alguns pixels pra cima, pra ficar mais
        // centralizado na tela visível (compensando, por exemplo, a barra de gestos
        // do sistema, que "puxa" o centro geométrico da tela pra baixo do centro
        // percebido visualmente).
        private const val CARD_UPWARD_SHIFT_DP = 16

        // O header agora fica sobreposto (flutuando) por cima do topo do conteúdo
        // rolável, em vez de ocupar uma linha própria (ver buildAndShowSettingsDialog).
        // A máscara de gradiente (FadeMaskFrameLayout) que apaga/revela o conteúdo por
        // baixo dele usa a altura real do header, medida em tempo de execução - não
        // precisa de uma constante de altura de faixa separada.
        // HEADER_OVERLAY_CLEARANCE_DP é o quanto o conteúdo rolável precisa começar
        // mais pra baixo para que o primeiro card não nasça escondido atrás do header -
        // um valor fixo (em vez de medir o header em tempo de execução) evita qualquer
        // salto visual de layout no primeiro frame.
        private const val HEADER_OVERLAY_CLEARANCE_DP = 100

        // Vive fora do ciclo de vida da Activity: assim, quando a Activity é recriada
        // (ex: troca de tema), a janela do display externo NÃO é destruída/recriada,
        // evitando o "piscar" da tela externa.
        private var sharedPresentation: PdfPresentation? = null
    }

    private var currentPresentation: PdfPresentation?
        get() = sharedPresentation
        set(value) { sharedPresentation = value }

    private lateinit var prefs: SharedPreferences
    private lateinit var displayManager: DisplayManager

    private lateinit var tvDisplayStatus: TextView
    private lateinit var btnSelectFolder: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var recyclerPdfs: RecyclerView
    private lateinit var layoutPageControls: LinearLayout
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var tvEmptyStateMessage: TextView
    private lateinit var btnPrevPage: View
    private lateinit var btnNextPage: View
    private lateinit var btnClosePdf: View
    private lateinit var tvPageIndicator: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnSort: LinearLayout
    private lateinit var tvSortLabel: TextView
    private lateinit var sortDivider: View
    private lateinit var ivSortArrow: ImageView
    private lateinit var tvResultCount: TextView
    private lateinit var tabAll: TextView
    private lateinit var tabFavorites: TextView
    private lateinit var tabIndicator: View

    private lateinit var adapter: PdfListAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var allPdfs: List<PdfItem> = emptyList()
    private var searchQuery: String = ""
    private var allTabSortOption: SortOption = SortOption.NAME_ASC
    private var setlistSortOption: SortOption = SortOption.SETLIST_ORDER
    private val activeSortOption: SortOption
        get() = if (showOnlyFavorites) setlistSortOption else allTabSortOption
    private var folderSelected = false
    private var volumePagingEnabled = false
    private var pageTransitionMode = PageTransitionMode.CURL
    private var zoomCropPercent = 0
    private var invertExternalRotation = false
    private var quickZoomMaxPercent = DEFAULT_QUICK_ZOOM_PERCENT
    // Referência ao botão de zoom rápido de cada layout (retrato/paisagem têm o seu
    // próprio, já que os XMLs são infladados de novo a cada rotação) - guardada para
    // poder atualizar o tint (ligado/desligado) sem precisar reconsultar findViewById.
    private var btnQuickZoom: ImageButton? = null
    // Referência ao diálogo de configurações atualmente aberto (se houver) - usado só
    // para detectar, de FORA da função que o constrói, se ele está na tela quando o
    // aparelho gira (ver onConfigurationChanged). A janela do diálogo tem tamanho fixo
    // em pixels (ver buildAndShowSettingsDialog) e não se reajusta sozinha ao girar.
    private var activeSettingsDialog: android.app.Dialog? = null
    private var showOnlyFavorites = false

    // Carregado uma vez em onCreate; toda alteração (toggleFavorite / reordenação)
    // mexe direto aqui E persiste em seguida - preserva exatamente a sequência (1, 2, 3...)
    // da set list.
    private var favoriteOrder: MutableList<String> = mutableListOf()

    private val openFolderLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                prefs.edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
                folderSelected = true
                loadPdfsFromTree(uri)
            }
        }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            updateDisplayStatus()
            // Logo após o evento de conexão o sistema às vezes ainda não terminou
            // de registrar o display (show() pode falhar nesse instante - ver
            // ensureExternalPresentation). Tentamos de novo em seguida.
            if (ensureExternalPresentation() == null) {
                mainHandler.postDelayed({ ensureExternalPresentation() }, 300)
            }
        }
        override fun onDisplayRemoved(displayId: Int) {
            updateDisplayStatus()
            currentPresentation?.dismiss()
            currentPresentation = null
            layoutPageControls.visibility = View.GONE
        }
        override fun onDisplayChanged(displayId: Int) = updateDisplayStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No Android 15+/targetSdk 35+ isto já é forçado pelo sistema; chamamos
        // explicitamente para o comportamento ser consistente também no Android 13/14
        // (minSdk deste app), onde edge-to-edge ainda é opcional.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // Depois de tentar (sem sucesso) fazer a screenshot do fundo do diálogo bater
        // pixel a pixel com a área do recorte de tela (furo da câmera), a solução mais
        // simples e confiável é nem deixar o app desenhar ali: restringimos a janela à
        // área "segura" do display. LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER faz o sistema
        // reservar esse espaço (como uma barra preta) tanto em retrato quanto em
        // paisagem, então o conteúdo real da Activity, a screenshot capturada e a
        // janela do diálogo passam a usar exatamente a mesma área - sem discrepância
        // nenhuma, sem precisar mais igualar atributos entre as duas janelas.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        volumePagingEnabled = prefs.getBoolean(KEY_VOLUME_PAGING, false)
        pageTransitionMode = try {
            PageTransitionMode.valueOf(prefs.getString(KEY_PAGE_TRANSITION_MODE, PageTransitionMode.CURL.name)!!)
        } catch (e: IllegalArgumentException) {
            PageTransitionMode.CURL
        }
        zoomCropPercent = prefs.getInt(KEY_ZOOM_CROP_PERCENT, 0).coerceIn(0, MAX_ZOOM_CROP_PERCENT)
        invertExternalRotation = prefs.getBoolean(KEY_INVERT_EXTERNAL_ROTATION, false)
        quickZoomMaxPercent = prefs.getInt(KEY_QUICK_ZOOM_MAX_PERCENT, DEFAULT_QUICK_ZOOM_PERCENT)
            .coerceIn(1, MAX_QUICK_ZOOM_PERCENT)
        loadFavoriteOrder()
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager

        bindViewsAndListeners()
        restorePersistedFolderIfAny()
    }

    /**
     * Aplica o inset da status bar (e da barra de navegação/gestos) como padding extra
     * na raiz do layout. Necessário porque o app roda em modo edge-to-edge (obrigatório
     * a partir do Android 15/targetSdk 35+): sem isso, o conteúdo é desenhado por baixo
     * dessas barras do sistema.
     */
    private fun applyStatusBarInsets() {
        val root = findViewById<View>(R.id.rootContainer)
        // Guarda o padding "de design" original (o que já está no XML) uma única vez,
        // para não ir somando inset em cima de inset a cada chamada (ex: reaberturas).
        val basePaddingLeft = root.paddingLeft
        val basePaddingTop = root.paddingTop
        val basePaddingRight = root.paddingRight
        val basePaddingBottom = root.paddingBottom

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            // Combinamos systemBars + displayCutout: o modo NEVER (ver onCreate) já evita
            // que a janela se estenda pela área do furo, mas somar esse inset aqui é uma
            // segurança extra para qualquer aparelho/versão que ainda exponha uma fatia
            // dessa área.
            val safeInsets = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                basePaddingLeft + safeInsets.left,
                basePaddingTop + safeInsets.top,
                basePaddingRight + safeInsets.right,
                basePaddingBottom + safeInsets.bottom
            )
            // O RecyclerView às vezes faz sua primeira medição ANTES desse padding
            // ser aplicado (esse callback de insets roda de forma assíncrona, depois
            // do primeiro layout) - guardando um alcance de rolagem baseado no
            // tamanho antigo (maior) da tela. Forçamos uma remedição explícita aqui
            // pra garantir que ele recalcule certo, senão os últimos itens da lista
            // podiam ficar inacessíveis ao rolar (mais perceptível em paisagem, onde
            // a altura disponível é bem menor).
            if (::recyclerPdfs.isInitialized) {
                recyclerPdfs.post { recyclerPdfs.requestLayout() }
            }
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    /** Infla o layout (retrato ou paisagem, conforme a orientação atual) e conecta as views. */
    private fun bindViewsAndListeners() {
        setContentView(R.layout.activity_main)

        // targetSdk 36 (Android 16) exige edge-to-edge: sem isto, o conteúdo desenha
        // por baixo da status bar (e da barra de navegação) por padrão - é por isso
        // que o topo da tela ficava "grudado" nela, parecendo faltar uma margem.
        applyStatusBarInsets()

        tvDisplayStatus = findViewById(R.id.tvDisplayStatus)
        btnSelectFolder = findViewById(R.id.btnSelectFolder)
        btnSettings = findViewById(R.id.btnSettings)
        recyclerPdfs = findViewById(R.id.recyclerPdfs)
        layoutPageControls = findViewById(R.id.layoutPageControls)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        tvEmptyStateMessage = findViewById(R.id.tvEmptyStateMessage)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        btnClosePdf = findViewById(R.id.btnClosePdf)
        btnQuickZoom = findViewById(R.id.btnQuickZoom)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        etSearch = findViewById(R.id.etSearch)
        btnSort = findViewById(R.id.btnSort)
        tvSortLabel = findViewById(R.id.tvSortLabel)
        sortDivider = findViewById(R.id.sortDivider)
        ivSortArrow = findViewById(R.id.ivSortArrow)
        tvResultCount = findViewById(R.id.tvResultCount)
        tabAll = findViewById(R.id.tabAll)
        tabFavorites = findViewById(R.id.tabFavorites)
        tabIndicator = findViewById(R.id.tabIndicator)

        adapter = PdfListAdapter(
            onClick = { pdfItem -> openPdfOnExternalDisplay(pdfItem) },
            onToggleFavorite = { pdfItem -> toggleFavorite(pdfItem) },
            isShowingFavoritesOnly = { showOnlyFavorites },
            canReorder = { canReorderSetlist() },
            onStartDrag = { holder -> itemTouchHelper.startDrag(holder) }
        )
        recyclerPdfs.layoutManager = LinearLayoutManager(this)
        recyclerPdfs.adapter = adapter
        recyclerPdfs.itemAnimator = ReorderOnlyItemAnimator()
        setupSetlistDragAndDrop()

        btnSelectFolder.setOnClickListener { openFolderLauncher.launch(null) }
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnPrevPage.setOnClickListener { currentPresentation?.prevPage(); syncPageIndicator() }
        btnNextPage.setOnClickListener { currentPresentation?.nextPage(); syncPageIndicator() }
        btnClosePdf.setOnClickListener { closeOpenPdf() }
        btnQuickZoom?.setOnClickListener {
            val isEnabled = currentPresentation?.toggleQuickZoom() ?: return@setOnClickListener
            updateQuickZoomButtonTint(isEnabled)
        }
        // Reflete o estado atual (ex: após girar o aparelho, o layout é reinflado e o
        // botão nasce de novo - sem isso, ele sempre voltaria pra aparência "desligado"
        // mesmo com o zoom rápido ainda ativo na janela do display externo).
        updateQuickZoomButtonTint(currentPresentation?.isQuickZoomEnabled ?: false)

        tabAll.setOnClickListener {
            if (showOnlyFavorites) {
                showOnlyFavorites = false
                updateTabSelection(animate = true)
                updateSortLabel()
                refreshList()
            }
        }
        tabFavorites.setOnClickListener {
            if (!showOnlyFavorites) {
                showOnlyFavorites = true
                updateTabSelection(animate = true)
                updateSortLabel()
                refreshList()
            }
        }
        // O indicador (View separada, ver bg_tab_selected/tabIndicator no XML) só tem
        // largura/posição corretas depois que o layout mede as duas abas - por isso o
        // posicionamento inicial espera o próximo passo de layout (post), sem animação.
        tabAll.post {
            tabIndicator.layoutParams = tabIndicator.layoutParams.apply { width = tabAll.width }
            tabIndicator.requestLayout()
            updateTabSelection(animate = false)
        }

        etSearch.setText(searchQuery)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        updateSortLabel()
        btnSort.setOnClickListener { showSortMenu() }

        // Restaura estado visual (útil após rotação, já que a Activity não é recriada)
        updateDisplayStatus()
        refreshList()
        if ((currentPresentation?.pageCount ?: 0) > 0) {
            layoutPageControls.visibility = View.VISIBLE
            syncPageIndicator()
        } else {
            layoutPageControls.visibility = View.GONE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Reinfla a tela ao girar o aparelho: no modo paisagem o cabeçalho passa a
        // rolar junto com a lista (não fica fixo), liberando espaço vertical.
        bindViewsAndListeners()

        // O diálogo de configurações usa uma janela com tamanho FIXO em pixels e uma
        // screenshot capturada no momento em que foi aberto (ver
        // buildAndShowSettingsDialog) - nada disso se reajusta sozinho ao girar o
        // aparelho, nem numa troca de tema (que também cai aqui, já que "uiMode" está
        // no configChanges do manifest). Se ele estiver aberto, fechamos e reabrimos,
        // recapturando tudo já no novo estado. Um pequeno atraso (em vez de
        // mainHandler.post imediato) garante que a janela antiga termine de ser
        // desfeita e bindViewsAndListeners() acima já tenha medido/desenhado a tela
        // antes da nova janela pedir foco - sem isso, o primeiro toque logo em
        // seguida podia ser "engolido" pela disputa de foco entre as duas janelas.
        if (activeSettingsDialog?.isShowing == true) {
            activeSettingsDialog?.dismiss()
            mainHandler.postDelayed({ showSettingsDialog() }, 120)
        }
    }

    override fun onStart() {
        super.onStart()
        displayManager.registerDisplayListener(displayListener, null)
        updateDisplayStatus()
        ensureExternalPresentation()
    }

    override fun onStop() {
        super.onStop()
        displayManager.unregisterDisplayListener(displayListener)
    }

    // ---------------------------------------------------------------------
    // Tema claro/escuro
    // ---------------------------------------------------------------------

    private fun isCurrentlyDark(): Boolean {
        val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun setDarkTheme(enabled: Boolean) {
        if (enabled == isCurrentlyDark()) return
        val newMode = if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        prefs.edit().putInt(KEY_THEME_MODE, newMode).apply()
        // Isso recria a Activity automaticamente para aplicar o novo tema.
        AppCompatDelegate.setDefaultNightMode(newMode)
    }

    // ---------------------------------------------------------------------
    // Idioma do aplicativo
    // ---------------------------------------------------------------------
    //
    // Usa a API de "preferência de idioma por app" do AndroidX (disponível desde o
    // appcompat 1.6+): AppCompatDelegate.setApplicationLocales(). Como o minSdk deste
    // projeto já é 33 (Android 13+), essa escolha é gerenciada e persistida pelo
    // próprio sistema operacional (LocaleManager) - não precisamos de nenhuma
    // SharedPreference própria nem de nenhuma entrada extra no manifest (essas só
    // seriam necessárias para dar suporte a versões mais antigas do Android).

    /** Rótulo (já traduzido) do idioma atualmente selecionado, pra mostrar na linha do menu. */
    private fun currentLanguageLabel(): String {
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.isEmpty) return getString(R.string.language_option_system)
        return when (current[0]?.language) {
            "en" -> getString(R.string.language_option_en)
            "pt" -> getString(R.string.language_option_pt)
            else -> getString(R.string.language_option_system)
        }
    }

    /**
     * Mostra o seletor de idioma (Sistema/Português/English). Aplicar uma escolha
     * diferente da atual recria a Activity automaticamente (é assim que o Android
     * aplica uma nova configuração de idioma) - por isso [onBeforeApply] existe: dá
     * a chance de fechar o diálogo de configurações de forma limpa ANTES dessa
     * recriação acontecer, evitando qualquer aviso de "janela vazada".
     */
    private fun showLanguagePickerDialog(onBeforeApply: () -> Unit) {
        val options = listOf(
            getString(R.string.language_option_system) to LocaleListCompat.getEmptyLocaleList(),
            getString(R.string.language_option_pt) to LocaleListCompat.forLanguageTags("pt"),
            getString(R.string.language_option_en) to LocaleListCompat.forLanguageTags("en")
        )

        val current = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (current.isEmpty) "" else current[0]?.language.orEmpty()
        val selectedIndex = when (currentTag) {
            "en" -> 2
            "pt" -> 1
            else -> 0
        }

        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PdfExternal_RoundedDialog)
            .setTitle(R.string.language_dialog_title)
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), selectedIndex) { dialog, which ->
                dialog.dismiss()
                val newLocales = options[which].second
                if (newLocales.toLanguageTags() != current.toLanguageTags()) {
                    onBeforeApply()
                    AppCompatDelegate.setApplicationLocales(newLocales)
                    // A janela do display externo não é uma Activity - não é recriada
                    // sozinha quando o idioma muda, então precisa ser avisada na mão.
                    currentPresentation?.refreshLocalizedStrings()
                }
            }
            .show()
    }

    /** Seletor simples (estilo "menu suspenso") de 1% a 10% para o zoom rápido. */
    private fun showQuickZoomPercentPickerDialog(onSelected: (Int) -> Unit) {
        val options = (1..MAX_QUICK_ZOOM_PERCENT).toList()
        val labels = options.map { "$it%" }.toTypedArray()
        val selectedIndex = options.indexOf(quickZoomMaxPercent).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PdfExternal_RoundedDialog)
            .setTitle(R.string.setting_quick_zoom_title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                dialog.dismiss()
                onSelected(options[which])
            }
            .show()
    }

    private fun pageTransitionModeLabel(mode: PageTransitionMode): String = when (mode) {
        PageTransitionMode.NONE -> getString(R.string.page_transition_none)
        PageTransitionMode.SLIDE -> getString(R.string.page_transition_slide)
        PageTransitionMode.CURL -> getString(R.string.page_transition_curl)
    }

    /** Seletor (estilo "menu suspenso") entre os 3 tipos de transição de página. */
    private fun showPageTransitionModePickerDialog(onSelected: (PageTransitionMode) -> Unit) {
        val options = listOf(PageTransitionMode.NONE, PageTransitionMode.SLIDE, PageTransitionMode.CURL)
        val labels = options.map { pageTransitionModeLabel(it) }.toTypedArray()
        val selectedIndex = options.indexOf(pageTransitionMode).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_PdfExternal_RoundedDialog)
            .setTitle(R.string.setting_curl_title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                dialog.dismiss()
                onSelected(options[which])
            }
            .show()
    }

    // ---------------------------------------------------------------------
    // Pasta persistente
    // ---------------------------------------------------------------------

    private fun restorePersistedFolderIfAny() {
        val savedUriString = prefs.getString(KEY_FOLDER_URI, null) ?: return
        val savedUri = Uri.parse(savedUriString)

        val stillGranted = contentResolver.persistedUriPermissions.any {
            it.uri == savedUri && it.isReadPermission
        }

        if (stillGranted) {
            folderSelected = true
            loadPdfsFromTree(savedUri)
        } else {
            prefs.edit().remove(KEY_FOLDER_URI).apply()
        }
    }

    // ---------------------------------------------------------------------
    // Display externo
    // ---------------------------------------------------------------------

    private fun getExternalDisplay(): Display? {
        return displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    private fun updateDisplayStatus() {
        val external = getExternalDisplay()
        if (external != null) {
            tvDisplayStatus.text = getString(R.string.display_status_connected, external.name)
            tvDisplayStatus.setBackgroundResource(R.drawable.bg_chip_connected)
            tvDisplayStatus.setTextColor(getColor(R.color.success))
        } else {
            tvDisplayStatus.text = getString(R.string.display_status_disconnected)
            tvDisplayStatus.setBackgroundResource(R.drawable.bg_chip_disconnected)
            tvDisplayStatus.setTextColor(getColor(R.color.text_secondary))
        }
    }

    /**
     * Garante que exista uma janela Presentation aberta no display externo (mostrando o
     * estado ocioso em preto, se nenhum PDF foi selecionado ainda). A janela é reaproveitada
     * entre seleções de PDF para não piscar a tela — só é recriada se o display mudar.
     */
    private fun ensureExternalPresentation(): PdfPresentation? {
        val external = try {
            getExternalDisplay() ?: return null
        } catch (e: Exception) {
            android.util.Log.e("PdfExternalDisplay", "Falha ao consultar displays", e)
            return null
        }

        val existing = currentPresentation
        try {
            if (existing != null && existing.display.displayId == external.displayId) {
                // Reconecta o callback a esta instância da Activity (a anterior pode
                // ter sido destruída, ex: após trocar o tema).
                existing.onPageChanged = { _, _ -> syncPageIndicator() }
                existing.setTransitionMode(pageTransitionMode)
                existing.setZoomCropPercent(zoomCropPercent)
                existing.setInvertRotation(invertExternalRotation)
                existing.setQuickZoomMaxPercent(quickZoomMaxPercent)
                return existing
            }
        } catch (e: Exception) {
            // A instância antiga pode estar com a janela/display já inválidos.
            android.util.Log.e("PdfExternalDisplay", "Presentation existente inválida", e)
            currentPresentation = null
        }

        try {
            currentPresentation?.dismiss()
        } catch (e: Exception) {
            android.util.Log.e("PdfExternalDisplay", "Falha ao fechar Presentation antiga", e)
        }
        currentPresentation = null

        // Usamos o contexto da aplicação (não da Activity) para que esta janela
        // sobreviva a recriações da Activity sem precisar ser recriada.
        return try {
            val presentation = PdfPresentation(applicationContext, external)
            presentation.onPageChanged = { _, _ -> syncPageIndicator() }
            presentation.setTransitionMode(pageTransitionMode)
            presentation.setZoomCropPercent(zoomCropPercent)
            presentation.setInvertRotation(invertExternalRotation)
            presentation.setQuickZoomMaxPercent(quickZoomMaxPercent)
            presentation.show()
            currentPresentation = presentation
            presentation
        } catch (e: Exception) {
            // Cobre InvalidDisplayException e qualquer outra falha ao criar/mostrar
            // a janela no display externo (nunca deve derrubar o app).
            android.util.Log.e("PdfExternalDisplay", "Falha ao mostrar Presentation no display externo", e)
            currentPresentation = null
            null
        }
    }

    // ---------------------------------------------------------------------
    // Busca e ordenação
    // ---------------------------------------------------------------------

    private fun canReorderSetlist(): Boolean =
        showOnlyFavorites && setlistSortOption == SortOption.SETLIST_ORDER && searchQuery.isBlank()

    private fun setupSetlistDragAndDrop() {
        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            private var draggedFromPos = RecyclerView.NO_POSITION

            override fun isLongPressDragEnabled(): Boolean = canReorderSetlist()

            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int = if (canReorderSetlist()) super.getDragDirs(recyclerView, viewHolder) else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (!canReorderSetlist()) return false
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                val movedPair = adapter.onItemMove(fromPos, toPos) ?: return false
                moveSetlistItem(movedPair.first, movedPair.second)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    draggedFromPos = viewHolder.bindingAdapterPosition
                    viewHolder.itemView.animate()
                        .scaleX(1.02f)
                        .scaleY(1.02f)
                        .alpha(0.92f)
                        .translationZ(dpToPx(6f))
                        .setDuration(120L)
                        .start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .translationZ(0f)
                    .setDuration(150L)
                    .start()
                if (draggedFromPos != RecyclerView.NO_POSITION) {
                    draggedFromPos = RecyclerView.NO_POSITION
                    saveFavoriteOrder()
                }
            }
        }
        itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(recyclerPdfs)
    }

    private fun updateSortLabel() {
        val currentSort = activeSortOption
        tvSortLabel.text = getString(currentSort.shortLabelRes)
        if (currentSort == SortOption.SETLIST_ORDER) {
            sortDivider.visibility = View.GONE
            ivSortArrow.visibility = View.GONE
        } else {
            sortDivider.visibility = View.VISIBLE
            ivSortArrow.visibility = View.VISIBLE
            ivSortArrow.rotation = if (currentSort.ascending) 0f else 180f
        }
    }

    private fun showSortMenu() {
        val popup = PopupMenu(this, btnSort)
        val availableOptions = if (showOnlyFavorites) {
            SortOption.values().toList()
        } else {
            SortOption.values().filter { it != SortOption.SETLIST_ORDER }
        }
        availableOptions.forEachIndexed { index, option ->
            popup.menu.add(0, index, index, getString(option.menuLabelRes))
        }
        popup.setOnMenuItemClickListener { menuItem ->
            val chosen = availableOptions[menuItem.itemId]
            if (showOnlyFavorites) {
                setlistSortOption = chosen
            } else {
                allTabSortOption = chosen
            }
            updateSortLabel()
            refreshList()
            true
        }
        popup.show()
    }

    private fun loadPdfsFromTree(treeUri: Uri) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )

        val items = mutableListOf<PdfItem>()
        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeIdx)
                val name = cursor.getString(nameIdx)
                if (mime == "application/pdf" || name?.endsWith(".pdf", ignoreCase = true) == true) {
                    val docId = cursor.getString(idIdx)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val lastModified = if (modifiedIdx >= 0) cursor.getLong(modifiedIdx) else 0L
                    items.add(PdfItem(name ?: docId, docUri, lastModified))
                }
            }
        }

        allPdfs = items
        refreshList()

        if (items.isEmpty()) {
            Toast.makeText(this, getString(R.string.empty_state_no_pdfs), Toast.LENGTH_SHORT).show()
        }
    }

    /** Carrega a ordem salva da set list (com migração automática do formato legado em Set). */
    private fun loadFavoriteOrder() {
        val savedJson = prefs.getString(KEY_SETLIST_ORDER, null)
        if (savedJson != null) {
            val list = mutableListOf<String>()
            try {
                val array = JSONArray(savedJson)
                for (i in 0 until array.length()) {
                    val uriStr = array.optString(i, "")
                    if (uriStr.isNotEmpty() && uriStr !in list) {
                        list.add(uriStr)
                    }
                }
            } catch (_: Exception) {
                // Ignora JSON inválido e tenta fallback para chave legada
            }
            favoriteOrder = list
            return
        }

        val legacySet = prefs.getStringSet(KEY_FAVORITE_URIS, emptySet()) ?: emptySet()
        favoriteOrder = legacySet.sorted().toMutableList()
        if (favoriteOrder.isNotEmpty()) {
            saveFavoriteOrder()
        }
    }

    private fun saveFavoriteOrder() {
        val json = JSONArray(favoriteOrder).toString()
        prefs.edit().putString(KEY_SETLIST_ORDER, json).apply()
    }

    private fun moveSetlistItem(movedUri: Uri, targetUri: Uri) {
        val fromIndex = favoriteOrder.indexOf(movedUri.toString())
        val toIndex = favoriteOrder.indexOf(targetUri.toString())
        if (fromIndex == -1 || toIndex == -1 || fromIndex == toIndex) return
        val moved = favoriteOrder.removeAt(fromIndex)
        favoriteOrder.add(toIndex, moved)
    }

    /** Aplica busca + aba (todos/favoritos) + ordenação sobre a lista completa e atualiza a tela. */
    private fun refreshList(replayEntranceAnimation: Boolean = true) {
        // Mapa de URI -> posição 1-based considerando apenas os itens da set list que
        // existem na pasta atual, garantindo sequência contínua (1, 2, 3...) na tela.
        val currentFolderUris = allPdfs.map { it.uri.toString() }.toHashSet()
        val setlistPosByUri = HashMap<String, Int>()
        var seq = 1
        for (uriStr in favoriteOrder) {
            if (uriStr in currentFolderUris) {
                setlistPosByUri[uriStr] = seq++
            }
        }
        val favoriteSet = favoriteOrder.toHashSet()

        var result = allPdfs

        if (searchQuery.isNotBlank()) {
            result = result.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

        if (showOnlyFavorites) {
            result = result.filter { it.uri.toString() in favoriteSet }
        }

        result = when (activeSortOption) {
            SortOption.SETLIST_ORDER -> result.sortedBy { setlistPosByUri[it.uri.toString()] ?: Int.MAX_VALUE }
            SortOption.NAME_ASC -> result.sortedBy { it.name.lowercase() }
            SortOption.NAME_DESC -> result.sortedByDescending { it.name.lowercase() }
            SortOption.NEWEST_FIRST -> result.sortedByDescending { it.lastModified }
            SortOption.OLDEST_FIRST -> result.sortedBy { it.lastModified }
        }

        // O estado de favorito e a posição na set list são persistidos à parte (não fazem
        // parte do escaneamento da pasta) - aplicados aqui na hora de montar a lista final.
        result = result.map { item ->
            val uriStr = item.uri.toString()
            item.copy(
                isFavorite = uriStr in favoriteSet,
                setlistPosition = if (showOnlyFavorites) setlistPosByUri[uriStr] else null
            )
        }

        adapter.submitList(result, replayEntranceAnimation)

        tvResultCount.text = if (allPdfs.isEmpty()) {
            ""
        } else {
            val count = result.size
            if (count == 1) getString(R.string.result_count_singular) else getString(R.string.result_count_plural, count)
        }

        updateEmptyState(hasResultsAfterFilter = result.isNotEmpty())
    }

    /** Alterna o favorito de um PDF e persiste a mudança imediatamente. */
    private var pendingListRefresh: Runnable? = null

    private fun toggleFavorite(item: PdfItem) {
        val key = item.uri.toString()
        if (!favoriteOrder.remove(key)) {
            favoriteOrder.add(key)
        }
        saveFavoriteOrder()

        // A reconstrução da lista (o que dispara a animação de reorganização) espera
        // um curto instante de "silêncio" antes de rodar de verdade. Se o usuário
        // desfavoritar vários PDFs em sequência rápida, cada toque cancela e reagenda
        // essa espera - só quando ninguém mais toca por LIST_REFRESH_DEBOUNCE_MS é que
        // a lista é atualizada de fato, incorporando todas as remoções de uma vez.
        // Sem isso, cada remoção disparava sua própria reorganização, e como elas
        // ficavam competindo (uma começando antes da anterior terminar de mover os
        // itens), o resultado era um "salto" visual bugado.
        pendingListRefresh?.let { mainHandler.removeCallbacks(it) }
        val refresh = Runnable {
            pendingListRefresh = null
            // false: favoritar é só um "flag" no item já visível na tela - repetir a
            // cascata inteira aqui ficaria irritante toda vez que a pessoa toca na estrela.
            refreshList(replayEntranceAnimation = false)
        }
        pendingListRefresh = refresh
        mainHandler.postDelayed(refresh, LIST_REFRESH_DEBOUNCE_MS)
    }

    /**
     * Realça visualmente a aba ativa (Todos/Favoritos): desliza o indicador (uma View
     * separada, ver tabIndicator no XML) para trás do label ativo, e faz as cores do
     * texto fazerem um crossfade suave em vez de trocar na hora.
     *
     * [animate] = false só é usado no posicionamento inicial (logo depois do primeiro
     * layout, antes de qualquer toque do usuário) - nesse caso não faz sentido animar
     * a partir de um lugar arbitrário.
     */
    private fun updateTabSelection(animate: Boolean) {
        val activeColor = androidx.core.content.ContextCompat.getColor(this, R.color.primary)
        val inactiveColor = androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)

        val (selected, unselected) = if (showOnlyFavorites) tabFavorites to tabAll else tabAll to tabFavorites
        selected.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        unselected.setTypeface(android.graphics.Typeface.DEFAULT)

        if (animate) {
            animateTabTextColor(selected, activeColor)
            animateTabTextColor(unselected, inactiveColor)
        } else {
            selected.setTextColor(activeColor)
            unselected.setTextColor(inactiveColor)
        }

        val indicatorWidth = tabIndicator.width
        if (indicatorWidth == 0) return // ainda não medido - tabAll.post cuida disso

        val targetX = if (showOnlyFavorites) indicatorWidth.toFloat() else 0f
        if (animate) {
            tabIndicator.animate()
                .translationX(targetX)
                .setDuration(280L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                .start()
        } else {
            tabIndicator.translationX = targetX
        }
    }

    /** Crossfade suave da cor do texto (em vez de trocar abruptamente). */
    private fun animateTabTextColor(view: TextView, toColor: Int) {
        android.animation.ValueAnimator.ofArgb(view.currentTextColor, toColor).apply {
            duration = 280L
            addUpdateListener { view.setTextColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun updateEmptyState(hasResultsAfterFilter: Boolean = true) {
        val showEmpty = !folderSelected || allPdfs.isEmpty() || !hasResultsAfterFilter
        layoutEmptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
        recyclerPdfs.visibility = if (showEmpty) View.GONE else View.VISIBLE

        tvEmptyStateMessage.text = when {
            !folderSelected -> getString(R.string.empty_state_select_folder)
            allPdfs.isEmpty() -> getString(R.string.empty_state_no_pdfs)
            showOnlyFavorites && !hasResultsAfterFilter -> getString(R.string.empty_state_no_favorites)
            !hasResultsAfterFilter -> getString(R.string.empty_state_no_search_results)
            else -> ""
        }
    }

    // ---------------------------------------------------------------------
    // Exibição do PDF no display externo
    // ---------------------------------------------------------------------

    private fun openPdfOnExternalDisplay(item: PdfItem) {
        val presentation = ensureExternalPresentation()
        if (presentation == null) {
            Toast.makeText(
                this,
                getString(R.string.toast_connect_external_display),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        presentation.setTransitionMode(pageTransitionMode)
        presentation.setZoomCropPercent(zoomCropPercent)
        presentation.setInvertRotation(invertExternalRotation)
        presentation.setQuickZoomMaxPercent(quickZoomMaxPercent)

        // Reaproveita a mesma janela já aberta no display externo - troca só o
        // conteúdo, sem recriar a Presentation, evitando o "piscar" da tela.
        presentation.loadPdf(item.uri)

        showPageControls()
    }

    /** Fecha o PDF atualmente exibido no display externo, voltando à tela ociosa. */
    private fun closeOpenPdf() {
        currentPresentation?.closeDocument()
        hidePageControls()
    }

    /** Mostra a faixa de controles (anterior/próxima, fechar, zoom) com uma entrada
     *  suave (fade + leve deslize de baixo para cima), em vez de aparecer de repente. */
    private fun showPageControls() {
        layoutPageControls.animate().cancel()
        if (layoutPageControls.visibility != View.VISIBLE) {
            layoutPageControls.alpha = 0f
            layoutPageControls.translationY = dpToPx(10f)
            layoutPageControls.visibility = View.VISIBLE
        }
        layoutPageControls.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(320L)
            .setInterpolator(android.view.animation.PathInterpolator(0.25f, 0.1f, 0.25f, 1f))
            .start()
    }

    /** Some com a faixa de controles com uma saída suave (fade + leve deslize para
     *  baixo), em vez de sumir de repente. */
    private fun hidePageControls() {
        layoutPageControls.animate().cancel()
        layoutPageControls.animate()
            .alpha(0f)
            .translationY(dpToPx(10f))
            .setDuration(160L)
            .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                layoutPageControls.visibility = View.GONE
                layoutPageControls.translationY = 0f
            }
            .start()
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    private fun syncPageIndicator() {
        val presentation = currentPresentation ?: return
        runOnUiThread {
            tvPageIndicator.text = "${presentation.currentPage} / ${presentation.pageCount}"
        }
    }

    /** Tinge o botão de zoom rápido com a cor de destaque quando ativado, ou a cor
     *  secundária padrão quando desativado - mesmo padrão visual usado nos outros
     *  botões de estado liga/desliga do app (ex: tema, tabs). */
    private fun updateQuickZoomButtonTint(isEnabled: Boolean) {
        val button = btnQuickZoom ?: return
        val color = if (isEnabled) getColor(R.color.primary) else getColor(R.color.text_secondary)
        button.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

    // ---------------------------------------------------------------------
    // Virar página com botões físicos (page turner Bluetooth / volume)
    // ---------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val presentation = currentPresentation
        if (presentation != null && presentation.pageCount > 0) {
            when (keyCode) {
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    presentation.nextPage()
                    syncPageIndicator()
                    return true
                }
                KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    presentation.prevPage()
                    syncPageIndicator()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (volumePagingEnabled) {
                        presentation.nextPage()
                        syncPageIndicator()
                        return true
                    }
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (volumePagingEnabled) {
                        presentation.prevPage()
                        syncPageIndicator()
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------------
    // Diálogo de configurações (estilo One UI: cards arredondados agrupando opções)
    // ---------------------------------------------------------------------

    private fun showSettingsDialog() {
        // A captura precisa ser assíncrona (ver capturePixelCopyBitmap) - por isso o
        // diálogo só é de fato montado dentro do callback, dono do bitmap.
        capturePixelCopyBitmap { screenshotBitmap ->
            buildAndShowSettingsDialog(screenshotBitmap)
        }
    }

    /**
     * Anima a entrada dos blocos do menu de configurações (cabeçalho + cada card de
     * seção): cada um sobe, some do fade-in e cresce levemente ao mesmo tempo, com um
     * pequeno atraso escalonado entre eles - dá a sensação de "cascata" em vez de tudo
     * aparecer de uma vez.
     */
    private fun animateSettingsEntrance(container: android.view.ViewGroup) {
        // Curva "decelerate" padrão do Material (cubic-bezier 0, 0, 0.2, 1): a
        // velocidade cai a zero suavemente perto do fim, em vez de "travar" de
        // repente - um final de movimento mais suave. Mesmo tempo da cascata da
        // lista de PDFs (ver animateItemEntrance em PdfListAdapter), pra sentir
        // igual em todo o app.
        val smoothDecelerate = android.view.animation.PathInterpolator(0f, 0f, 0.2f, 1f)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(i * 40L)
                .setDuration(650L)
                .setInterpolator(smoothDecelerate)
                .start()
        }
    }

    private fun buildAndShowSettingsDialog(fullScreenshotBitmap: android.graphics.Bitmap?) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        // A janela do Dialog continua do tamanho da tela INTEIRA (ver mais abaixo) -
        // só o conteúdo/imagem de fundo é que passa a ser recortado. Guardamos essas
        // dimensões aqui porque o bitmap original é reciclado logo em seguida.
        val fullScreenWidthPx = fullScreenshotBitmap?.width
        val fullScreenHeightPx = fullScreenshotBitmap?.height

        // Posição REAL da Activity na tela física - não assumimos mais que é sempre
        // (0,0). Em aparelhos com furo de câmera, a Activity pode ser deslocada
        // alguns pixels para desviar da área do furo, e esse deslocamento NÃO é
        // simétrico entre as duas rotações de paisagem (o furo fica numa borda física
        // fixa do aparelho; dependendo de qual direção ele foi girado, essa borda
        // acaba à esquerda ou a a direita da tela). Fixar x=0 sempre só "acertava"
        // por coincidência numa das duas direções - medir de verdade corrige as duas.
        val activityScreenLocation = IntArray(2)
        window.decorView.getLocationOnScreen(activityScreenLocation)
        val activityScreenX = activityScreenLocation[0]
        val activityScreenY = activityScreenLocation[1]

        // Recorta da screenshot cheia SÓ a fatia onde o cartão vai ficar (mesma
        // fração/alinhamento usado no Compose logo abaixo: 85% de largura/altura,
        // centralizado). Com isso a screenshot deixa de "substituir" a tela toda -
        // ela serve só de fonte para o vidro líquido refratar bem ali, dentro do
        // próprio cartão. Fora do cartão não existe imagem nenhuma por cima: o que
        // aparece é a Activity de verdade, ao vivo, o tempo todo - eliminando
        // qualquer chance de "flash"/deslocamento entre o real e uma cópia estática
        // (mesmo uma cópia pixel-perfeita ainda corre esse risco, por diferenças
        // sutis de composição entre o bitmap e a renderização ao vivo).
        val screenshotBitmap = fullScreenshotBitmap?.let { full ->
            try {
                val cardWidthPx = (full.width * CARD_WIDTH_FRACTION).toInt().coerceAtLeast(1)
                val cardHeightPx = (full.height * CARD_HEIGHT_FRACTION).toInt().coerceAtLeast(1)

                // Compensação manual: na paisagem "virada pra esquerda" (confirmado
                // por teste: Surface.ROTATION_90 nesse aparelho), o recorte horizontal
                // fica alguns pixels deslocado em relação à posição real do cartão na
                // tela. Mover a JANELA do diálogo não teve efeito nenhum nesse
                // desalinhamento (então não é a janela) - o recorte em si
                // (cardLeftPx, aqui embaixo) é quem precisa ser ajustado.
                val currentRotation = display?.rotation
                val landscapeLeftCompensationPx = dp(-27)
                val cardLeftPx = if (currentRotation == android.view.Surface.ROTATION_90) {
                    (full.width - cardWidthPx) / 2 - landscapeLeftCompensationPx
                } else {
                    (full.width - cardWidthPx) / 2
                }.coerceIn(0, full.width - cardWidthPx)

                // Além do ajuste fino empírico de alinhamento (BACKDROP_VERTICAL_NUDGE_DP),
                // subtrai também CARD_UPWARD_SHIFT_DP: o mesmo deslocamento pra cima
                // aplicado ao cartão de verdade no Compose (Modifier.offset), pra que o
                // recorte da screenshot continue vindo exatamente de trás de onde o
                // cartão passou a ficar. coerceIn garante que nunca saia dos limites do
                // bitmap original mesmo se os ajustes somados passarem da margem
                // disponível.
                val cardTopPx = (
                    (full.height - cardHeightPx) / 2 -
                        dp(BACKDROP_VERTICAL_NUDGE_DP) -
                        dp(CARD_UPWARD_SHIFT_DP)
                    ).coerceIn(0, full.height - cardHeightPx)

                android.graphics.Bitmap.createBitmap(full, cardLeftPx, cardTopPx, cardWidthPx, cardHeightPx)
            } catch (e: Exception) {
                android.util.Log.e("PdfExternalDisplay", "Falha ao recortar a screenshot do cartão", e)
                null
            } finally {
                full.recycle()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(HEADER_OVERLAY_CLEARANCE_DP), dp(26), dp(32))
            // Sem fundo próprio aqui: a superfície de vidro (blur + refração real via
            // shader de GPU) é fornecida pelo Modifier.liquid(...) no Compose, logo
            // abaixo - ver a montagem final da função.
        }

        // Diálogo criado antes do header para que o botão de voltar já possa referenciá-lo.
        val dialog = android.app.Dialog(this).apply {
            window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT
                )
                // Sem dim extra do sistema - o próprio blur manual (abaixo) já separa
                // visualmente o diálogo do conteúdo atrás, dispensando escurecer tudo.
                setDimAmount(0f)
            }
        }

        // Cabeçalho: seta de voltar + título. Todas as alterações abaixo são aplicadas
        // na hora (auto-save), então fechar o diálogo é só "voltar" - não há necessidade
        // de um botão "Concluído".
        //
        // O header fica SOBREPOSTO ao topo do conteúdo rolável. Ele mesmo NÃO tem
        // fundo algum (totalmente transparente) - quem esconde/revela o conteúdo
        // rolando por baixo é o FadeMaskFrameLayout (montado mais abaixo, na
        // extremidade final da função), que apaga de verdade o próprio conteúdo numa
        // máscara de gradiente (metade de cima 100% apagada, metade de baixo em
        // degradê) em vez de pintar uma cor por cima. Resultado: onde o conteúdo é
        // apagado, o que aparece é o vidro líquido de verdade (a mesma superfície do
        // resto do cartão), não uma cor "inventada".
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(24), dp(26), dp(14))
        }
        header.addView(ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setBackgroundResource(R.drawable.bg_icon_button)
            setImageResource(R.drawable.ic_chevron_left)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_primary))
            contentDescription = getString(R.string.settings_back_description)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { dialog.dismiss() }
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            setTextColor(getColor(R.color.text_primary))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(14) }
        })
        // NOTA: header não é adicionado a "root" - ele fica fixo no topo, fora da área
        // rolável (ver a montagem final da função, onde header e o ScrollView com
        // "root" são combinados numa coluna).

        // --- Seção: Aparência ---
        root.addView(createSectionLabel(getString(R.string.section_appearance)))
        val appearanceCard = createSettingsCard()
        val (themeRow, themeSwitch) = createSwitchRow(
            title = getString(R.string.setting_dark_theme_title),
            description = getString(R.string.setting_dark_theme_desc),
            checked = isCurrentlyDark(),
            iconRes = R.drawable.ic_theme_dark
        )
        appearanceCard.addView(themeRow)
        appearanceCard.addView(createRowDivider())
        val languageRow = createNavigationRow(
            title = getString(R.string.setting_language_title),
            description = getString(R.string.setting_language_desc),
            valueText = currentLanguageLabel(),
            iconRes = R.drawable.ic_language
        ) {
            // IMPORTANTE: diferente da troca de tema (que só dispara
            // onConfigurationChanged, sem recriar a Activity - "uiMode" está
            // declarado em configChanges no manifesto), trocar o idioma recria a
            // Activity de verdade (não está, e não deveria estar, em configChanges,
            // já que "locale" afeta strings/layout-direction, algo que o Android
            // recarrega via recriação, não em tempo real).
            //
            // Por isso aqui só fechamos o diálogo e NADA mais - reabrir o menu de
            // configurações (como fazíamos pro tema) tentaria construir uma UI nova
            // (com captura de tela assíncrona via PixelCopy) bem no meio da Activity
            // sendo destruída/recriada, o que travava o app (o callback do
            // PixelCopy ou a nova janela do diálogo ficavam presos a uma Activity
            // que já não existia mais). A recriação por si só já reconstrói a tela
            // principal do zero, no idioma novo - não há necessidade de reabrir nada.
            showLanguagePickerDialog { dialog.dismiss() }
        }
        appearanceCard.addView(languageRow)
        root.addView(appearanceCard)
        // A Activity já declara "uiMode" no configChanges do manifest, então trocar o
        // tema NÃO a recria - só dispara onConfigurationChanged (ver
        // bindViewsAndListeners). O diálogo, por ser uma janela separada, sobrevive
        // sozinho; onConfigurationChanged já cuida de fechá-lo e reabri-lo em seguida
        // pra repintar com as cores do novo tema (ver ali). NÃO duplicar esse
        // fechar/reabrir aqui: fazer isso nos dois lugares ao mesmo tempo fazia duas
        // janelas de diálogo disputarem foco, e o primeiro toque no botão de voltar
        // era "roubado" por essa disputa (só o segundo toque funcionava).
        themeSwitch.setOnCheckedChangeListener { isChecked ->
            if (isChecked != isCurrentlyDark()) {
                setDarkTheme(isChecked)
            }
        }

        // --- Seção: Partituras / leitura ---
        root.addView(createSectionLabel(getString(R.string.section_reading), topMarginDp = 22))
        val readingCard = createSettingsCard()
        val (zoomRow, zoomSlider, zoomValueLabel) = createSliderRow(
            title = getString(R.string.setting_zoom_title),
            description = getString(R.string.setting_zoom_desc),
            iconRes = R.drawable.ic_zoom,
            value = zoomCropPercent,
            max = MAX_ZOOM_CROP_PERCENT
        )
        readingCard.addView(zoomRow)
        root.addView(readingCard)
        zoomSlider.addOnChangeListener { _, value, fromUser ->
            zoomValueLabel.text = "${value.toInt()}%"
            if (fromUser) {
                zoomCropPercent = value.toInt()
                prefs.edit().putInt(KEY_ZOOM_CROP_PERCENT, zoomCropPercent).apply()
                currentPresentation?.setZoomCropPercent(zoomCropPercent)
            }
        }
        readingCard.addView(createRowDivider())
        val (invertRotationRow, invertRotationSwitch) = createSwitchRow(
            title = getString(R.string.setting_invert_rotation_title),
            description = getString(R.string.setting_invert_rotation_desc),
            checked = invertExternalRotation,
            iconRes = R.drawable.ic_screen_rotation
        )
        readingCard.addView(invertRotationRow)
        invertRotationSwitch.setOnCheckedChangeListener { isChecked ->
            invertExternalRotation = isChecked
            prefs.edit().putBoolean(KEY_INVERT_EXTERNAL_ROTATION, isChecked).apply()
            currentPresentation?.setInvertRotation(isChecked)
        }
        readingCard.addView(createRowDivider())
        val quickZoomRow = createNavigationRow(
            title = getString(R.string.setting_quick_zoom_title),
            description = getString(R.string.setting_quick_zoom_desc),
            valueText = "$quickZoomMaxPercent%",
            iconRes = R.drawable.ic_zoom
        ) {
            showQuickZoomPercentPickerDialog { selectedPercent ->
                quickZoomMaxPercent = selectedPercent
                prefs.edit().putInt(KEY_QUICK_ZOOM_MAX_PERCENT, selectedPercent).apply()
                currentPresentation?.setQuickZoomMaxPercent(selectedPercent)
                // Fecha e reabre o diálogo só para atualizar o texto "X%" exibido
                // ao lado dessa linha - mesmo truque (e mesmo atraso) usado ao trocar
                // de tema, para evitar disputa de foco entre a janela antiga e a nova.
                dialog.dismiss()
                mainHandler.postDelayed({ showSettingsDialog() }, 120)
            }
        }
        readingCard.addView(quickZoomRow)

        // --- Seção: Virar página ---
        root.addView(createSectionLabel(getString(R.string.section_page_turn), topMarginDp = 22))

        val pagingCard = createSettingsCard()
        val (volumeRow, volumeSwitch) = createSwitchRow(
            title = getString(R.string.setting_volume_title),
            description = getString(R.string.setting_volume_desc),
            checked = volumePagingEnabled
        )
        pagingCard.addView(volumeRow)
        pagingCard.addView(createRowDivider())
        val curlRow = createNavigationRow(
            title = getString(R.string.setting_curl_title),
            description = getString(R.string.setting_curl_desc),
            valueText = pageTransitionModeLabel(pageTransitionMode),
            iconRes = R.drawable.ic_pdf
        ) {
            showPageTransitionModePickerDialog { selectedMode ->
                pageTransitionMode = selectedMode
                prefs.edit().putString(KEY_PAGE_TRANSITION_MODE, selectedMode.name).apply()
                currentPresentation?.setTransitionMode(selectedMode)
                // Fecha e reabre o diálogo só para atualizar o texto exibido ao lado
                // dessa linha - mesmo truque usado no zoom rápido/idioma.
                dialog.dismiss()
                mainHandler.postDelayed({ showSettingsDialog() }, 120)
            }
        }
        pagingCard.addView(curlRow)
        root.addView(pagingCard)

        volumeSwitch.setOnCheckedChangeListener { isChecked ->
            volumePagingEnabled = isChecked
            prefs.edit().putBoolean(KEY_VOLUME_PAGING, isChecked).apply()
        }

        // --- Montagem final: vidro líquido (Liquid, shader de GPU real) + cartão ---
        //
        // Antes: blur manual em CPU (box blur numa cópia reduzida da tela) - funcional
        // em qualquer aparelho, mas com qualidade/realismo limitados. Agora usamos a
        // biblioteca Liquid (io.github.fletchmckee.liquid), que aplica o desfoque e a
        // refração via RuntimeShader (GPU) - o mesmo tipo de tecnologia por trás do
        // "Liquid Glass" da Apple. Exige Android 13+ (por isso o minSdk do projeto
        // subiu para 33) e, como é Compose, hospedamos tudo num ComposeView dentro
        // do Dialog. Diferente da janela do display externo (Presentation, que não é
        // uma Activity), aqui a própria MainActivity já fornece os "donos" de ciclo de
        // vida que o Compose exige - não precisamos de nada como o
        // PresentationLifecycleOwner que criamos para aquele outro caso.
        //
        // screenshotBitmap chega pronto via parâmetro (ver showSettingsDialog) - a
        // captura em si já foi feita antes, de forma assíncrona, via PixelCopy.

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@MainActivity)
            setViewTreeViewModelStoreOwner(this@MainActivity)
            setViewTreeSavedStateRegistryOwner(this@MainActivity)

            setContent {
                val liquidState = rememberLiquidState()

                Box(modifier = Modifier.fillMaxSize()) {
                    // Tocar fora do cartão fecha o diálogo. Fica ANTES da imagem/cartão
                    // na árvore só para não interceptar cliques que são pro cartão -
                    // z-order de desenho não é afetado pela ordem de declaração aqui já
                    // que ninguém mais desenha nessa camada.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { dialog.dismiss() }
                    )

                    // Cartão de configurações com a superfície de vidro líquido -
                    // o conteúdo em si (root) continua sendo nossas Views clássicas
                    // (switches, cards, slider), hospedadas via AndroidView.
                    val cardAlpha = remember { Animatable(0f) }
                    LaunchedEffect(Unit) {
                        // Fade do CARTÃO INTEIRO (vidro + conteúdo juntos) - sem isso,
                        // mesmo com a cascata dos blocos internos, o vidro/fundo do
                        // cartão em si aparecia "de golpe", dando a sensação de "spawnar"
                        // do nada por trás da animação dos textos.
                        cardAlpha.animateTo(1f, animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing))
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            // Desloca o cartão inteiro pra cima, pra ficar mais
                            // centralizado na tela visível (ver CARD_UPWARD_SHIFT_DP).
                            .offset(y = (-CARD_UPWARD_SHIFT_DP).dp)
                            .alpha(cardAlpha.value)
                            .fillMaxWidth(CARD_WIDTH_FRACTION)
                            // IMPORTANTE: era heightIn(max = ...) - uma altura "até um
                            // teto", que depende do conteúdo clássico (AndroidView/
                            // ScrollView) para saber o tamanho final. Isso faz o Compose
                            // medir em duas passadas (uma provisória, depois a real com
                            // base no conteúdo), causando um leve "salto" de tamanho bem
                            // na primeira exibição - e como é essa mesma área que
                            // amostra/refrata o fundo, o salto parecia um deslocamento do
                            // fundo. Uma altura FIXA (fração da tela) já nasce correta na
                            // primeira passada, sem ajuste posterior.
                            .fillMaxHeight(CARD_HEIGHT_FRACTION)
                            .clip(RoundedCornerShape(30.dp))
                    ) {
                        // Fundo do cartão: SÓ a fatia da screenshot correspondente a essa
                        // área (recortada em buildAndShowSettingsDialog) - não a tela
                        // toda. Como o Box já está alinhado/dimensionado exatamente igual
                        // ao recorte, a imagem cai no lugar certo sem precisar saber a
                        // posição do cartão em coordenadas globais.
                        screenshotBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .liquefiable(liquidState)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .liquid(liquidState) {
                                // Sem este bloco, a lib usa os padrões: frost = 0.dp (SEM
                                // desfoque) e tint = Color.Unspecified (SEM véu de cor) -
                                // ou seja, o texto ficava sentado direto em cima do fundo
                                // só distorcido (refraction padrão), sem nada pra dar
                                // contraste. Isso é o que causava a má legibilidade.
                                frost = 10.dp
                                tint = if (isCurrentlyDark()) {
                                    ComposeColor.Black.copy(alpha = 0.45f)
                                } else {
                                    ComposeColor.White.copy(alpha =  0.45f)
                                }
                                refraction = 0.08f
                                curve = 0.1f
                                edge = 0.03f
                                saturation = 1.1f
                                // O padrão da lib é CircleShape - descasado do clip
                                // retangular do cartão, o que deixava a distorção nos
                                // cantos com cara de "pílula". Alinhar os dois aqui.
                                shape = RoundedCornerShape(30.dp)
                            }
                        ) {
                            AndroidView(
                                factory = {
                                    val scrollableContent = android.widget.ScrollView(this@MainActivity).apply {
                                        addView(root)
                                        // Estado inicial (invisível/deslocado) já no
                                        // primeiro frame, antes de qualquer coisa ser
                                        // desenhada - senão dá pra ver um "pisca" com o
                                        // conteúdo no lugar final antes da animação começar.
                                        for (i in 0 until root.childCount) {
                                            val child = root.getChildAt(i)
                                            child.alpha = 0f
                                            child.translationY = dp(28).toFloat()
                                            child.scaleX = 0.98f
                                            child.scaleY = 0.98f
                                        }
                                        post { animateSettingsEntrance(root) }
                                    }

                                    // Envolve o CONTEÚDO ROLÁVEL (não o header) numa
                                    // máscara de gradiente real: a metade de cima da
                                    // faixa fica 100% apagada (revelando o vidro
                                    // líquido de verdade atrás, não uma cor), e da
                                    // metade até o fim do header vira um degradê
                                    // suave até o conteúdo aparecer por completo.
                                    // root já reserva um respiro (HEADER_OVERLAY_
                                    // CLEARANCE_DP) pro primeiro card não nascer
                                    // escondido atrás dessa faixa.
                                    val maskedContent = FadeMaskFrameLayout(this@MainActivity).apply {
                                        addView(
                                            scrollableContent,
                                            android.widget.FrameLayout.LayoutParams(
                                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                                            )
                                        )
                                        solidZoneFraction = 0.8f
                                    }

                                    // A altura da faixa mascarada = altura real do
                                    // header (medida após o layout, já que depende do
                                    // texto/densidade) - o degradê termina exatamente
                                    // no fim do header, como pedido.
                                    header.doOnLayout {
                                        maskedContent.maskZoneHeightPx = header.height
                                    }

                                    android.widget.FrameLayout(this@MainActivity).apply {
                                        addView(
                                            maskedContent,
                                            android.widget.FrameLayout.LayoutParams(
                                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                                            )
                                        )
                                        addView(
                                            header,
                                            android.widget.FrameLayout.LayoutParams(
                                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.setOnDismissListener {
            screenshotBitmap?.takeIf { !it.isRecycled }?.recycle()
            if (activeSettingsDialog === dialog) {
                activeSettingsDialog = null
            }
        }

        // Sem isto, a JANELA do diálogo (não o nosso Compose) usa o tamanho padrão do
        // tema (windowMinWidthMajor/Minor), pensado para caber um cartãozinho central -
        // não a tela toda. IMPORTANTE: isto precisa ser feito ANTES de dialog.show() -
        // fazer depois faz a janela aparecer pequena e "esticar" pra tela cheia um
        // instante depois, e é esse estico que dá a sensação de o fundo (a screenshot)
        // se deslocar/pular quando o menu abre.
        // A Activity já é edge-to-edge (ver onCreate), mas o Dialog tem sua PRÓPRIA
        // janela - por padrão ela ainda reserva espaço pro furo da câmera/barras do
        // sistema (fitsSystemWindows=true nessa janela). Isso fazia o conteúdo Compose
        // ficar deslocado (empurrado pra baixo da área do furo) em relação à screenshot
        // de fundo, que foi capturada da Activity (sem essa reserva) - daí a sensação
        // de o fundo "se deslocar/pular". Igualando as duas janelas, o alinhamento fica
        // pixel a pixel.
        dialog.window?.let { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false) }

        // O Dialog é uma janela NOVA, com seus PRÓPRIOS atributos - por padrão ela
        // não herda o `layoutInDisplayCutoutMode` da janela da Activity.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            dialog.window?.attributes = dialog.window?.attributes?.apply {
                layoutInDisplayCutoutMode = window.attributes.layoutInDisplayCutoutMode
            }
        }

        // Antes: MATCH_PARENT nas duas janelas (Activity e Dialog) e torcer pra elas
        // resolverem pro mesmo tamanho/posição - na prática elas divergiam mesmo com
        // as mesmas configurações de recorte de tela, e a screenshot ficava menor que
        // o app de verdade.
        //
        // Depois: demos ao Dialog o tamanho EXATO (em pixels) do bitmap capturado, mas
        // ainda fixando a âncora em x=0,y=0 - o que "acertava" numa das duas rotações
        // de paisagem por coincidência, mas ficava alguns pixels deslocado na outra
        // (aparelhos com furo de câmera podem deslocar a Activity pra desviar dele, e
        // esse deslocamento não é simétrico entre girar pra um lado ou pro outro).
        //
        // Agora: em vez de ASSUMIR a posição, MEDIMOS a posição real da Activity na
        // tela (activityScreenX/Y, ver getLocationOnScreen mais acima) e usamos esse
        // valor de verdade - assim os dois passam a ser literalmente o mesmo
        // retângulo em qualquer rotação, sem depender de nenhuma suposição.
        dialog.window?.let { win ->
            if (fullScreenWidthPx != null && fullScreenHeightPx != null) {
                win.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
                win.setLayout(fullScreenWidthPx, fullScreenHeightPx)
                win.attributes = win.attributes.apply {
                    x = activityScreenX
                    y = activityScreenY
                }
            } else {
                // Sem screenshot (falha na captura) - melhor ainda assim cobrir a
                // tela toda do que deixar o diálogo do tamanho mínimo do tema.
                win.setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }

        activeSettingsDialog = dialog
        dialog.show()
    }

    /**
     * Captura o conteúdo atual da janela como bitmap (nítido, sem blur próprio - o
     * desfoque em si é aplicado pela biblioteca Liquid via shader).
     *
     * Antes: usava `decorView.draw(canvas)` - um desenho manual, via software, da
     * hierarquia de Views. O problema: esse desenho manual não reproduz fielmente a
     * área do recorte de tela (furo da câmera) - o app em si preenche o display
     * inteiro (renderização normal, acelerada por hardware), mas o `draw(canvas)`
     * manual "perdia" esse pedaço, capturando a imagem deslocada para baixo do furo.
     *
     * Depois: trocamos para `PixelCopy` (captura real da superfície, não um
     * redesenho) e, junto, restringimos a Activity à área segura do display
     * (LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER, ver onCreate). Mesmo assim a screenshot
     * ainda saía menor que o app real - a causa era usar `windowManager.
     * currentWindowMetrics.bounds` para decidir o tamanho do bitmap: esse valor é
     * calculado pelo sistema de forma independente e nem sempre bate exatamente com
     * o tamanho que a JANELA DE VERDADE da Activity acabou tendo depois do NEVER.
     *
     * Agora usamos `decorView.width`/`decorView.height` como fonte da verdade: é o
     * tamanho REAL e já estável da janela no momento em que o usuário abre o menu
     * (a Activity já está totalmente visível e não vai mudar de tamanho sozinha) -
     * não uma estimativa de outra API. Esse mesmo valor também é usado para dar à
     * janela do diálogo o tamanho e a posição EXATOS (ver buildAndShowSettingsDialog),
     * em vez de torcer para dois MATCH_PARENT em janelas diferentes resolverem para
     * o mesmo número.
     */
    private fun capturePixelCopyBitmap(onResult: (android.graphics.Bitmap?) -> Unit) {
        try {
            val decorView = window.decorView
            val w = decorView.width
            val h = decorView.height
            if (w <= 0 || h <= 0) {
                onResult(null)
                return
            }

            val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(window, bitmap, { copyResult ->
                if (copyResult == android.view.PixelCopy.SUCCESS) {
                    onResult(bitmap)
                } else {
                    android.util.Log.e(
                        "PdfExternalDisplay",
                        "PixelCopy falhou ao capturar a tela para o efeito de vidro (código $copyResult)"
                    )
                    onResult(null)
                }
            }, mainHandler)
        } catch (e: Exception) {
            android.util.Log.e("PdfExternalDisplay", "Falha ao capturar a tela para o efeito de vidro", e)
            onResult(null)
        }
    }

    /** Badge circular colorido com um ícone dentro (estilo One UI). */
    private fun createIconBadge(iconRes: Int, sizeDp: Int): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            setBackgroundResource(R.drawable.bg_icon_badge)
            setPadding(dp(sizeDp / 4), dp(sizeDp / 4), dp(sizeDp / 4), dp(sizeDp / 4))
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.primary))
        }
    }

    /** Rótulo pequeno de seção, no estilo das telas de Configurações do One UI. */
    private fun createSectionLabel(text: String, topMarginDp: Int = 0): TextView {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.primary))
            textSize = 12f
            letterSpacing = 0.06f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(topMarginDp), dp(4), dp(8))
        }
    }

    /** Card arredondado que agrupa uma ou mais linhas de configuração. */
    private fun createSettingsCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_settings_card)
        }
    }

    /**
     * Contexto "envolvido" com [R.style.ThemeOverlay_PdfExternal_Material3Widgets], necessário
     * para instanciar widgets Material 3 (MaterialSwitch, Slider) com segurança dentro deste
     * app, cujo tema base é Material 2 e não define os atributos de cor que eles exigem.
     */
    private fun material3WidgetContext(): Context =
        android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_PdfExternal_Material3Widgets)

    /** Divisor fino e discreto entre linhas dentro de um mesmo card. */
    private fun createRowDivider(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            }
            setBackgroundColor(getColor(R.color.text_secondary))
            alpha = 0.12f
        }
    }

    /** Linha de configuração: título + descrição à esquerda, switch estilo pill à direita. */
    private fun createSwitchRow(
        title: String,
        description: String?,
        checked: Boolean,
        iconRes: Int? = null
    ): Pair<LinearLayout, SimpleSwitch> {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_settings_row)
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minimumHeight = dp(60)
        }

        if (iconRes != null) {
            row.addView(createIconBadge(iconRes, sizeDp = 36).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
            })
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
        })
        if (description != null) {
            textColumn.addView(TextView(this).apply {
                text = description
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(2), dp(8), 0)
            })
        }
        row.addView(textColumn)

        // SimpleSwitch: implementação própria (não usa MaterialSwitch/M3) - ver
        // comentário na classe sobre o motivo.
        val switchControl = SimpleSwitch(this).apply {
            isChecked = checked
        }
        row.addView(switchControl)

        row.setOnClickListener { switchControl.toggle() }

        return row to switchControl
    }

    /**
     * Linha de configuração que abre outra tela/diálogo ao ser tocada (em vez de um
     * switch) - mostra o valor atual à direita + uma seta indicando navegação. Usada
     * pela escolha de Idioma.
     */
    private fun createNavigationRow(
        title: String,
        description: String?,
        valueText: String,
        iconRes: Int? = null,
        onClick: () -> Unit
    ): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_settings_row)
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minimumHeight = dp(60)
        }

        if (iconRes != null) {
            row.addView(createIconBadge(iconRes, sizeDp = 36).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
            })
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
        })
        if (description != null) {
            textColumn.addView(TextView(this).apply {
                text = description
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(2), dp(8), 0)
            })
        }
        row.addView(textColumn)

        val valueLabel = TextView(this).apply {
            text = valueText
            setTextColor(getColor(R.color.text_secondary))
            textSize = 14f
            setPadding(0, 0, dp(6), 0)
        }
        row.addView(valueLabel)

        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            setImageResource(R.drawable.ic_chevron)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_secondary))
        })

        row.setOnClickListener { onClick() }

        return row
    }

    /**
     * Linha de configuração com um slider (usada pelo ajuste de zoom/crop da página).
     * Retorna a linha montada, o próprio Slider e o label que mostra o valor atual em %.
     */
    private fun createSliderRow(
        title: String,
        description: String?,
        value: Int,
        max: Int,
        iconRes: Int? = null
    ): Triple<LinearLayout, Slider, TextView> {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }

        val topLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        if (iconRes != null) {
            topLine.addView(createIconBadge(iconRes, sizeDp = 36).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
            })
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
        })
        if (description != null) {
            textColumn.addView(TextView(this).apply {
                text = description
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(2), dp(8), 0)
            })
        }
        topLine.addView(textColumn)

        val valueLabel = TextView(this).apply {
            text = "${value}%"
            setTextColor(getColor(R.color.primary))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }
        topLine.addView(valueLabel)
        row.addView(topLine)

        val slider = Slider(material3WidgetContext()).apply {
            valueFrom = 0f
            valueTo = max.toFloat()
            stepSize = 1f
            this.value = value.toFloat().coerceIn(valueFrom, valueTo)
            // Tintas explícitas por tudo (em vez de herdar do tema Material 3), pelo
            // mesmo motivo do MaterialSwitch: garante renderização previsível dentro
            // de um tema Theme.MaterialComponents "clássico".
            val primaryColor = getColor(R.color.primary)
            trackActiveTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            trackInactiveTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.surface_variant))
            thumbTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            haloTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            tickActiveTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            tickInactiveTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        }
        row.addView(slider)

        return Triple(row, slider, valueLabel)
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingListRefresh?.let { mainHandler.removeCallbacks(it) }
        // Propositalmente NÃO desmancha a janela do display externo aqui: isso
        // permite que ela sobreviva a recriações da Activity (troca de tema,
        // por exemplo) sem piscar. Ela só é fechada quando o display externo
        // desconecta de fato (ver displayListener.onDisplayRemoved).
    }
}
