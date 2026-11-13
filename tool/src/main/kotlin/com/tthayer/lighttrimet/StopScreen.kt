package com.tthayer.lighttrimet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val STOP_REFRESH_INTERVAL_MS = 60_000L

data class StopUiState(
    val loading: Boolean = true,
    val info: StopInfo? = null,
    val arrivals: List<ArrivalDto> = emptyList(),
    val error: String? = null,
    val confirmingRemove: Boolean = false,
    val removed: Boolean = false,
)

class StopViewModel(
    private val stop: SavedStop,
    private val repository: SavedStopsRepository,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(StopUiState())
    val uiState: StateFlow<StopUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    fun requestRemove() = _uiState.update { it.copy(confirmingRemove = true) }

    fun cancelRemove() = _uiState.update { it.copy(confirmingRemove = false) }

    fun confirmRemove() {
        viewModelScope.launch {
            repository.remove(stop.id)
            _uiState.update { it.copy(confirmingRemove = false, removed = true) }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        startRefresh()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        refreshJob?.cancel()
        refreshJob = null
    }

    fun refreshNow() = startRefresh()

    private fun startRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            refresh(showLoading = true)
            while (isActive) {
                delay(STOP_REFRESH_INTERVAL_MS)
                refresh(showLoading = false)
            }
        }
    }

    private suspend fun refresh(showLoading: Boolean) {
        if (showLoading) _uiState.update { it.copy(loading = true) }
        TriMetApi.arrivals(listOf(stop.id))
            .onSuccess { result ->
                val sorted = result.arrivals[stop.id].orEmpty()
                    .sortedBy { it.timeMillis() ?: Long.MAX_VALUE }
                _uiState.update {
                    it.copy(
                        loading = false,
                        info = result.stops[stop.id],
                        arrivals = sorted,
                        error = result.error,
                    )
                }
            }
            .onFailure { error ->
                _uiState.update { it.copy(loading = false, error = networkErrorMessage(error)) }
            }
    }
}

class StopScreen(
    sealedActivity: SealedLightActivity,
    private val stop: SavedStop,
) : LightScreen<Unit, StopViewModel>(sealedActivity) {

    override val viewModelClass: Class<StopViewModel>
        get() = StopViewModel::class.java

    override fun createViewModel(): StopViewModel =
        StopViewModel(stop, SavedStopsRepository(lightContext.dataStore))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val now = System.currentTimeMillis()

        LaunchedEffect(state.removed) {
            if (state.removed) goBack()
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back",
                        ),
                        center = LightTopBarCenter.TwoLineDetail(
                            line1 = stop.name,
                            line2 = listOfNotNull(
                                state.info?.direction?.takeIf { it.isNotBlank() },
                                stop.stateLabel,
                            ).joinToString(" · "),
                        ),
                        rightButton = LightBarButton.LightIcon(
                            icon = LightIcons.REFRESH,
                            onClick = viewModel::refreshNow,
                            contentDescription = "Refresh",
                        ),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )

                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            state.confirmingRemove -> EmptyMessage(
                                "Remove \"${stop.name}\" from your stops?",
                            )

                            state.arrivals.isEmpty() && state.loading -> EmptyMessage("Loading…")

                            state.arrivals.isEmpty() -> EmptyMessage(
                                state.error ?: "No upcoming arrivals at this stop.",
                            )

                            else -> ArrivalsList(state = state, nowMillis = now)
                        }
                    }

                    if (state.arrivals.isNotEmpty() && !state.confirmingRemove) {
                        state.error?.let { message ->
                            LightText(
                                text = message,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                align = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
                            )
                        }
                    }

                    if (state.confirmingRemove) {
                        LightBottomBar(
                            items = listOf(
                                LightBarButton.Text(text = "CANCEL", onClick = viewModel::cancelRemove),
                                LightBarButton.Text(text = "REMOVE", onClick = viewModel::confirmRemove),
                            ),
                        )
                    } else {
                        LightBottomBar(
                            items = listOf(
                                LightBarButton.LightIcon(
                                    icon = LightIcons.REFRESH,
                                    onClick = viewModel::refreshNow,
                                    contentDescription = "Refresh",
                                ),
                                LightBarButton.Text(
                                    text = "PLAN TRIP",
                                    onClick = {
                                        navigateTo({ sealed -> TripPlanScreen(sealed, destination = stop) })
                                    },
                                ),
                                LightBarButton.LightIcon(
                                    icon = LightIcons.TRASH,
                                    onClick = viewModel::requestRemove,
                                    contentDescription = "Remove stop",
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrivalsList(
    state: StopUiState,
    nowMillis: Long,
) {
    LightScrollView(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 1f.gridUnitsAsDp()),
    ) {
        state.arrivals.forEach { arrival ->
            ArrivalRow(arrival = arrival, nowMillis = nowMillis)
        }
    }
}
