package com.tthayer.lighttrimet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddStopUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val saved: SavedStop? = null,
)

class AddStopViewModel(
    private val repository: SavedStopsRepository,
) : LightViewModel<SavedStop?>() {

    private val _uiState = MutableStateFlow(AddStopUiState())
    val uiState: StateFlow<AddStopUiState> = _uiState.asStateFlow()

    fun submit(raw: CharSequence) {
        val id = raw.toString().trim().toLongOrNull()
        if (id == null) {
            _uiState.update { it.copy(error = "Enter a numeric stop ID, e.g. 6849.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            TriMetApi.lookupStop(id)
                .onSuccess { info ->
                    if (info == null) {
                        _uiState.update { it.copy(loading = false, error = "Stop $id was not found.") }
                    } else {
                        val saved = SavedStop(id = info.id, name = info.desc.ifBlank { "Stop $id" })
                        repository.add(saved)
                        _uiState.update { it.copy(loading = false, saved = saved) }
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(loading = false, error = networkErrorMessage(error)) }
                }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }
}

/** Text entry for a stop id; saves the stop and returns it as the screen result. */
class AddStopScreen(sealedActivity: SealedLightActivity) :
    LightScreen<SavedStop?, AddStopViewModel>(sealedActivity) {

    override val viewModelClass: Class<AddStopViewModel>
        get() = AddStopViewModel::class.java

    override fun createViewModel(): AddStopViewModel =
        AddStopViewModel(SavedStopsRepository(lightContext.dataStore))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LaunchedEffect(state.saved) {
            state.saved?.let { goBack(it) }
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTextInputEditor(
                    title = "Add Stop",
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    state = textFieldState,
                    onSubmit = viewModel::submit,
                    onBack = { goBack(null) },
                    submitLabel = "ADD",
                    submitIcon = LightIcons.ADD,
                    showBackButton = true,
                    modifier = Modifier.fillMaxSize(),
                )

                state.error?.let { message ->
                    LightFullscreenModal(
                        message = message,
                        onClose = viewModel::dismissError,
                    )
                }
            }
        }
    }
}
