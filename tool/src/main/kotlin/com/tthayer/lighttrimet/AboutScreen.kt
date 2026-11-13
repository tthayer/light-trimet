package com.tthayer.lighttrimet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
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

/** Static information about the tool and the data it uses. */
class AboutScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.Text("About"),
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    AboutSection(
                        heading = "Live arrivals",
                        body = "Save a stop by its ID to see upcoming arrivals, " +
                            "updated about every minute.",
                    )
                    AboutSection(
                        heading = "Trip planning",
                        body = "Plan a trip between two stops, addresses, or landmarks. " +
                            "Enter a numeric stop ID for a saved stop, or free text for a place.",
                    )
                    AboutSection(
                        heading = "Data",
                        body = "Arrivals and trip plans come from TriMet's public web services. " +
                            "Times are estimates and may change. Schedules, fares, and service " +
                            "alerts are published at trimet.org.",
                    )
                    AboutSection(
                        heading = "Not affiliated",
                        body = "This tool is an independent project and is not affiliated with " +
                            "or endorsed by TriMet. TriMet is not responsible for this tool.",
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutSection(heading: String, body: String) {
    Column(modifier = Modifier.padding(bottom = 1.25f.gridUnitsAsDp())) {
        LightText(text = heading, variant = LightTextVariant.Heading)
        LightText(
            text = body,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
        )
    }
}
