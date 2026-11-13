package com.tthayer.lighttrimet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val REFRESH_INTERVAL_MS = 30_000L
private const val PREVIEW_ARRIVALS = 3

data class HomeUiState(
    val loading: Boolean = true,
    val stops: List<SavedStop> = emptyList(),
    val stopInfo: Map<Long, StopInfo> = emptyMap(),
    val arrivals: Map<Long, List<ArrivalDto>> = emptyMap(),
    val error: String? = null,
)

class HomeViewModel(
    private val repository: SavedStopsRepository,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            repository.stops.collect { stops -> _uiState.update { it.copy(stops = stops) } }
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
                delay(REFRESH_INTERVAL_MS)
                refresh(showLoading = false)
            }
        }
    }

    private suspend fun refresh(showLoading: Boolean) {
        val stops = _uiState.value.stops
        if (stops.isEmpty()) {
            _uiState.update {
                it.copy(loading = false, stopInfo = emptyMap(), arrivals = emptyMap(), error = null)
            }
            return
        }
        if (showLoading) _uiState.update { it.copy(loading = true) }
        TriMetApi.arrivals(stops.map { it.id })
            .onSuccess { result ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        stopInfo = result.stops,
                        arrivals = result.arrivals,
                        error = result.error,
                    )
                }
            }
            .onFailure { error ->
                _uiState.update { it.copy(loading = false, error = networkErrorMessage(error)) }
            }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel =
        HomeViewModel(SavedStopsRepository(lightContext.dataStore))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        center = LightTopBarCenter.Text("TriMet"),
                        rightButton = LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            onClick = { navigateTo({ sealed -> AboutScreen(sealed) }) },
                            contentDescription = "About",
                        ),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )

                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            state.stops.isEmpty() && state.loading ->
                                EmptyMessage("Loading…")

                            state.stops.isEmpty() ->
                                EmptyMessage(
                                    "No stops saved yet.\n\n" +
                                        "Tap + to add a stop. Find the stop ID on trimet.org " +
                                        "or on the sign at your stop.",
                                )

                            else -> FavoritesList(
                                state = state,
                                onSelect = { stop -> navigateTo({ sealed -> StopScreen(sealed, stop) }) },
                            )
                        }
                    }

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

                    LightBottomBar(
                        items = listOf(
                            LightBarButton.LightIcon(
                                icon = LightIcons.REFRESH,
                                onClick = viewModel::refreshNow,
                                contentDescription = "Refresh",
                            ),
                            LightBarButton.Text(
                                text = "PLAN TRIP",
                                onClick = { navigateTo({ sealed -> TripPlanScreen(sealed) }) },
                            ),
                            LightBarButton.LightIcon(
                                icon = LightIcons.ADD,
                                onClick = {
                                    navigateTo(
                                        screenFactory = { sealed -> AddStopScreen(sealed) },
                                        resultCallback = { saved ->
                                            saved?.let { stop ->
                                                navigateTo({ sealed -> StopScreen(sealed, stop) })
                                            }
                                        },
                                    )
                                },
                                contentDescription = "Add stop",
                            ),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoritesList(
    state: HomeUiState,
    onSelect: (SavedStop) -> Unit,
) {
    val now = System.currentTimeMillis()
    LightScrollView(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 1f.gridUnitsAsDp()),
    ) {
        state.stops.forEach { stop ->
            val info = state.stopInfo[stop.id]
            val arrivals = state.arrivals[stop.id].orEmpty()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { onSelect(stop) }
                    .padding(bottom = 1.25f.gridUnitsAsDp()),
            ) {
                LightText(
                    text = stop.name,
                    variant = LightTextVariant.Heading,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = listOfNotNull(
                    info?.direction?.takeIf { it.isNotBlank() },
                    stop.stateLabel,
                ).joinToString(" · ")
                LightText(
                    text = detail,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                )

                if (arrivals.isEmpty()) {
                    LightText(
                        text = "No upcoming arrivals",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                    )
                } else {
                    arrivals.take(PREVIEW_ARRIVALS).forEach { arrival ->
                        ArrivalRow(arrival = arrival, nowMillis = now)
                    }
                }
            }
        }
    }
}
