package com.example.pdfexternal

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Jetpack Compose exige um [LifecycleOwner] (e também [ViewModelStoreOwner] e
 * [SavedStateRegistryOwner]) associado à árvore de Views para funcionar - normalmente
 * fornecidos automaticamente por uma Activity. Como [android.app.Presentation] não é
 * uma Activity, precisamos fornecer essa implementação manualmente para poder usar
 * Compose (necessário para a biblioteca de efeito de virar página) dentro dela.
 */
class PresentationLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    /** Chamar quando a janela do Presentation for criada e exibida. */
    fun handleCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Chamar quando a janela do Presentation for fechada, para liberar recursos do Compose. */
    fun handleDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
