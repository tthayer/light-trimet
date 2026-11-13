package com.tthayer.lighttrimet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TripField { From, To }

sealed interface TripMode {
    data object Form : TripMode
    data class EditField(val field: TripField, val session: Int) : TripMode
    data class PickSavedStop(val field: TripField) : TripMode
    data object Loading : TripMode
    data class Results(val plan: TripPlan) : TripMode
    data class Detail(val plan: TripPlan, val index: Int) : TripMode
    data class ChoosePlace(
        val field: TripField,
        val candidates: List<PlaceCandidate>,
        val message: String?,
    ) : TripMode
}

data class TripUiState(
    val mode: TripMode = TripMode.Form,
    val fromText: String = "",
    val toText: String = "",
    val fromResolved: TripEndpoint.Coordinate? = null,
    val toResolved: TripEndpoint.Coordinate? = null,
    val savedStops: List<SavedStop> = emptyList(),
    val error: String? = null,
    // Stop id -> TriMet's traffic direction ("Northbound", ...), used to name the side of the street.
    val stopSides: Map<Long, String> = emptyMap(),
)

class TripViewModel(
    private val repository: SavedStopsRepository,
    initialDestination: SavedStop?,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(
        TripUiState(toText = initialDestination?.id?.toString().orEmpty()),
    )
    val uiState: StateFlow<TripUiState> = _uiState.asStateFlow()

    private var editSession = 0

    init {
        viewModelScope.launch {
            repository.stops.collect { stops -> _uiState.update { it.copy(savedStops = stops) } }
        }
    }

    fun openField(field: TripField) {
        _uiState.update { it.copy(mode = TripMode.EditField(field, ++editSession), error = null) }
    }

    fun setFieldText(field: TripField, raw: CharSequence) {
        val text = raw.toString().trim()
        _uiState.update { state ->
            val cleared = if (field == TripField.From) {
                state.copy(fromText = text, fromResolved = null)
            } else {
                state.copy(toText = text, toResolved = null)
            }
            cleared.copy(mode = TripMode.Form, error = null)
        }
    }

    fun openPickSavedStop(field: TripField) {
        _uiState.update { it.copy(mode = TripMode.PickSavedStop(field)) }
    }

    fun chooseSavedStop(stop: SavedStop) {
        val field = (_uiState.value.mode as? TripMode.PickSavedStop)?.field ?: return
        setFieldText(field, stop.id.toString())
    }

    fun swapEndpoints() {
        _uiState.update {
            it.copy(
                fromText = it.toText,
                toText = it.fromText,
                fromResolved = it.toResolved,
                toResolved = it.fromResolved,
            )
        }
    }

    fun backToForm() {
        _uiState.update { it.copy(mode = TripMode.Form) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun plan() {
        val state = _uiState.value
        val from = endpointFor(state.fromText, state.fromResolved)
        val to = endpointFor(state.toText, state.toResolved)
        when {
            from == null -> {
                _uiState.update { it.copy(error = "Enter a starting point.") }
                return
            }

            to == null -> {
                _uiState.update { it.copy(error = "Enter a destination.") }
                return
            }
        }

        _uiState.update { it.copy(mode = TripMode.Loading, error = null) }
        viewModelScope.launch {
            TriMetApi.planTrip(from, to)
                .onSuccess { plan -> applyPlan(plan) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(mode = TripMode.Form, error = networkErrorMessage(error))
                    }
                }
        }
    }

    private fun applyPlan(plan: TripPlan) {
        val ambiguousFrom = !plan.fromResolved && plan.fromCandidates.isNotEmpty()
        val ambiguousTo = !plan.toResolved && plan.toCandidates.isNotEmpty()
        _uiState.update { state ->
            when {
                plan.itineraries.isNotEmpty() -> state.copy(mode = TripMode.Results(plan))

                ambiguousFrom -> state.copy(
                    mode = TripMode.ChoosePlace(TripField.From, plan.fromCandidates, plan.errorMessage),
                )

                ambiguousTo -> state.copy(
                    mode = TripMode.ChoosePlace(TripField.To, plan.toCandidates, plan.errorMessage),
                )

                else -> state.copy(
                    mode = TripMode.Form,
                    error = plan.errorMessage ?: "No trips found for those places.",
                )
            }
        }
    }

    fun choosePlace(candidate: PlaceCandidate) {
        val field = (_uiState.value.mode as? TripMode.ChoosePlace)?.field ?: return
        val endpoint = TripEndpoint.Coordinate(
            longitude = candidate.longitude,
            latitude = candidate.latitude,
            label = candidate.description,
        )
        _uiState.update { state ->
            if (field == TripField.From) {
                state.copy(fromText = candidate.description, fromResolved = endpoint)
            } else {
                state.copy(toText = candidate.description, toResolved = endpoint)
            }
        }
        plan()
    }

    fun openDetail(index: Int) {
        val results = _uiState.value.mode as? TripMode.Results ?: return
        val itinerary = results.plan.itineraries.getOrNull(index) ?: return
        _uiState.update {
            it.copy(mode = TripMode.Detail(results.plan, index), stopSides = emptyMap())
        }
        loadStopSides(itinerary)
    }

    fun backFromDetail() {
        val detail = _uiState.value.mode as? TripMode.Detail ?: return
        _uiState.update { it.copy(mode = TripMode.Results(detail.plan)) }
    }

    /** Resolves each stop's traffic direction so the detail view can name the side of the street. */
    private fun loadStopSides(itinerary: Itinerary) {
        val stopIds = itinerary.legs
            .flatMap { listOfNotNull(it.fromStopId, it.toStopId) }
            .mapNotNull { it.toLongOrNull() }
            .distinct()
        if (stopIds.isEmpty()) return
        viewModelScope.launch {
            TriMetApi.arrivals(stopIds).onSuccess { result ->
                _uiState.update { state ->
                    state.copy(stopSides = result.stops.mapValues { (_, info) -> info.direction })
                }
            }
        }
    }

    override fun onBackPressed(): Boolean {
        when (_uiState.value.mode) {
            is TripMode.Form -> return false
            is TripMode.Detail -> backFromDetail()
            else -> backToForm()
        }
        return true
    }

    private fun endpointFor(input: String, resolved: TripEndpoint.Coordinate?): TripEndpoint? {
        if (resolved != null) return resolved
        val text = input.trim()
        if (text.isEmpty()) return null
        return if (text.all { it.isDigit() }) TripEndpoint.StopId(text) else TripEndpoint.Place(text)
    }
}

/** Plans a trip between two places. Optionally pre-fills the destination with a saved stop. */
class TripPlanScreen(
    sealedActivity: SealedLightActivity,
    private val destination: SavedStop? = null,
) : LightScreen<Unit, TripViewModel>(sealedActivity) {

    override val viewModelClass: Class<TripViewModel>
        get() = TripViewModel::class.java

    override fun createViewModel(): TripViewModel =
        TripViewModel(SavedStopsRepository(lightContext.dataStore), destination)

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
                when (val mode = state.mode) {
                    is TripMode.EditField -> EditFieldContent(
                        mode = mode,
                        state = state,
                        vm = viewModel,
                    )

                    is TripMode.PickSavedStop -> PickSavedStopContent(
                        stops = state.savedStops,
                        onSelect = viewModel::chooseSavedStop,
                        onBack = viewModel::backToForm,
                    )

                    is TripMode.Loading -> LoadingContent()

                    is TripMode.Results -> ResultsContent(
                        plan = mode.plan,
                        onSelect = viewModel::openDetail,
                        onEdit = viewModel::backToForm,
                        onClose = { goBack() },
                    )

                    is TripMode.Detail -> DetailContent(
                        plan = mode.plan,
                        index = mode.index,
                        stopSides = state.stopSides,
                        onBack = viewModel::backFromDetail,
                        onClose = { goBack() },
                    )

                    is TripMode.ChoosePlace -> ChoosePlaceContent(
                        mode = mode,
                        onSelect = viewModel::choosePlace,
                        onBack = viewModel::backToForm,
                    )

                    is TripMode.Form -> FormContent(
                        state = state,
                        onEditField = viewModel::openField,
                        onSwap = viewModel::swapEndpoints,
                        onPlan = viewModel::plan,
                        onClose = { goBack() },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditFieldContent(
    mode: TripMode.EditField,
    state: TripUiState,
    vm: TripViewModel,
) {
    val keyboardOptionsFlow = rememberKeyboardOptions()
    val initial = if (mode.field == TripField.From) state.fromText else state.toText
    key(mode.session) {
        val textFieldState = rememberTextFieldState(initial)
        LightTextInputEditor(
            title = if (mode.field == TripField.From) "From" else "To",
            editorKey = mode.session,
            keyboardOptionsFlow = keyboardOptionsFlow,
            state = textFieldState,
            onSubmit = { vm.setFieldText(mode.field, it) },
            onBack = vm::backToForm,
            submitLabel = "OK",
            submitIcon = LightIcons.ACCEPT,
            leftBottomBarItem = LightBarButton.LightIcon(
                icon = LightIcons.STAR,
                onClick = { vm.openPickSavedStop(mode.field) },
                contentDescription = "Saved stops",
            ),
            showBackButton = true,
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun FormContent(
    state: TripUiState,
    onEditField: (TripField) -> Unit,
    onSwap: () -> Unit,
    onPlan: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            center = LightTopBarCenter.Text("Plan Trip"),
            modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            EndpointRow(
                label = "From",
                value = state.fromText,
                hint = "Enter a stop, address, or landmark",
                onClick = { onEditField(TripField.From) },
            )
            EndpointRow(
                label = "To",
                value = state.toText,
                hint = "Enter a stop, address, or landmark",
                onClick = { onEditField(TripField.To) },
            )

            state.error?.let { message ->
                LightText(
                    text = message,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.REVERSE_ORDER,
                    onClick = onSwap,
                    contentDescription = "Swap",
                ),
                LightBarButton.Text(text = "PLAN", onClick = onPlan),
                LightBarButton.LightIcon(
                    icon = LightIcons.CLOSE,
                    onClick = onClose,
                    contentDescription = "Close",
                ),
            ),
        )
    }
}

@Composable
private fun EndpointRow(
    label: String,
    value: String,
    hint: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Detail, lighten = true)
        LightText(
            text = value.ifBlank { hint },
            variant = LightTextVariant.Heading,
            lighten = value.isBlank(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PickSavedStopContent(
    stops: List<SavedStop>,
    onSelect: (SavedStop) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Saved Stops"),
            modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (stops.isEmpty()) {
                EmptyMessage("No saved stops yet. Add one from the home screen.")
            } else {
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 1f.gridUnitsAsDp()),
                ) {
                    stops.forEach { stop ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable { onSelect(stop) }
                                .padding(vertical = 0.75f.gridUnitsAsDp()),
                        ) {
                            LightText(text = stop.name, variant = LightTextVariant.Copy)
                            LightText(
                                text = stop.stateLabel,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoosePlaceContent(
    mode: TripMode.ChoosePlace,
    onSelect: (PlaceCandidate) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text(
                if (mode.field == TripField.From) "Pick Start" else "Pick Destination",
            ),
            modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
        ) {
            mode.message?.let {
                LightText(text = it, variant = LightTextVariant.Detail, lighten = true)
            }
            LightText(
                text = "Select the closest match:",
                variant = LightTextVariant.Detail,
                lighten = true,
            )
        }

        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp()),
        ) {
            mode.candidates.forEach { candidate ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { onSelect(candidate) }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                ) {
                    LightText(text = candidate.description, variant = LightTextVariant.Copy)
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            center = LightTopBarCenter.Text("Trips"),
            modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyMessage("Planning…")
        }
    }
}

@Composable
private fun ResultsContent(
    plan: TripPlan,
    onSelect: (Int) -> Unit,
    onEdit: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            center = LightTopBarCenter.Text("Trips"),
            modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
        )

        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp(), end = 0.5f.gridUnitsAsDp()),
        ) {
            LightText(
                text = "Tap a trip for step-by-step directions.",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(bottom = 0.75f.gridUnitsAsDp()),
            )
            plan.itineraries.forEachIndexed { index, itinerary ->
                ItineraryBlock(
                    itinerary = itinerary,
                    onClick = { onSelect(index) },
                )
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "EDIT", onClick = onEdit),
                LightBarButton.Text(text = "CLOSE", onClick = onClose),
            ),
        )
    }
}

@Composable
private fun ItineraryBlock(
    itinerary: Itinerary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(bottom = 1.5f.gridUnitsAsDp()),
    ) {
        LightText(
            text = "${itinerary.startTime} – ${itinerary.endTime}",
            variant = LightTextVariant.Subheading,
        )
        LightText(
            text = itinerarySummary(itinerary),
            variant = LightTextVariant.Detail,
            lighten = true,
        )
        itinerary.legs.forEach { leg ->
            LegRow(leg)
        }
    }
}

/**
 * Step-by-step directions for one itinerary: which stop to walk to, which stop ID to board at,
 * how long to ride, and which side of the street each stop is on.
 */
@Composable
private fun DetailContent(
    plan: TripPlan,
    index: Int,
    stopSides: Map<Long, String>,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val itinerary = plan.itineraries.getOrNull(index) ?: return

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Trip ${index + 1} of ${plan.itineraries.size}"),
            modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
        )

        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp(), end = 0.5f.gridUnitsAsDp()),
        ) {
            LightText(
                text = itinerarySummary(itinerary),
                variant = LightTextVariant.Subheading,
            )
            LightText(
                text = "${itinerary.startTime} – ${itinerary.endTime}",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            itinerary.legs.forEachIndexed { i, leg ->
                DetailStep(number = i + 1, leg = leg, stopSides = stopSides)
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "BACK", onClick = onBack),
                LightBarButton.Text(text = "CLOSE", onClick = onClose),
            ),
        )
    }
}

@Composable
private fun DetailStep(
    number: Int,
    leg: TripLeg,
    stopSides: Map<Long, String>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 1.25f.gridUnitsAsDp()),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            LegModeIcon(
                leg = leg,
                modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp(), top = 0.15f.gridUnitsAsDp()),
            )
            Column(modifier = Modifier.weight(1f)) {
                LightText(
                    text = "$number. ${tripLegHeadline(leg)}",
                    variant = LightTextVariant.Copy,
                )
                directionLines(leg, stopSides).forEach { line ->
                    LightText(
                        text = line,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 0.2f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

@Composable
private fun LegRow(leg: TripLeg) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 0.4f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegModeIcon(leg = leg, modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()))
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = tripLegHeadline(leg),
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = tripLegDetail(leg),
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun itinerarySummary(itinerary: Itinerary): String {
    val transfers = when (itinerary.transfers) {
        0 -> "no transfers"
        1 -> "1 transfer"
        else -> "${itinerary.transfers} transfers"
    }
    val fare = itinerary.fare?.takeIf { it.isNotBlank() }?.let { " · \$$it" }.orEmpty()
    return "${formatDuration(itinerary.durationMinutes)} · $transfers$fare"
}

/** Instruction lines under one step: where to walk, where to board, where to get off, which side. */
private fun directionLines(leg: TripLeg, stopSides: Map<Long, String>): List<String> {
    if (leg.isTransit) {
        return buildList {
            add("Board at ${stopRef(leg.fromStopId, leg.fromDescription)}")
            sideFor(leg.fromStopId, stopSides)?.let { add("Board from the $it of the street") }
            add("Ride ${formatDuration(leg.durationMinutes)} to ${stopRef(leg.toStopId, leg.toDescription)}")
            sideFor(leg.toStopId, stopSides)?.let { add("Get off on the $it") }
        }
    }

    val target = stopRef(leg.toStopId, leg.toDescription)
    val meta = listOfNotNull(
        walkHeading(leg.direction),
        formatDistance(leg.distanceMiles).ifBlank { null },
        formatDuration(leg.durationMinutes).takeIf { it != "—" },
    ).joinToString(" · ")
    return buildList {
        add(listOf(meta, "to $target").filter { it.isNotBlank() }.joinToString(" · "))
        sideFor(leg.toStopId, stopSides)?.let { add("The stop is on the $it of the street") }
    }
}

/** "stop 13721 (Milwaukie/Main St MAX Station)" or just the name when TriMet gives no id. */
private fun stopRef(stopId: String?, description: String): String {
    val name = placeLabel(description)
    return when {
        stopId != null && name.isNotEmpty() -> "stop $stopId ($name)"
        stopId != null -> "stop $stopId"
        name.isNotEmpty() -> name
        else -> "the marked point"
    }
}

private fun sideFor(stopId: String?, stopSides: Map<Long, String>): String? =
    stopId?.toLongOrNull()?.let { streetSide(stopSides[it]) }
