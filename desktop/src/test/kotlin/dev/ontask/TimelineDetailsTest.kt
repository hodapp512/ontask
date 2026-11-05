package dev.ontask

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.runComposeUiTest
import dev.ontask.ui.OnTaskTheme
import dev.ontask.ui.SummaryCard
import dev.ontask.ui.ThemeMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The timeline's details window with a phone stretch: picking keeps it open and shows the pick; a reload doesn't close it. */
@OptIn(ExperimentalTestApi::class)
class TimelineDetailsTest {
    @Test fun pickingKeepsTheWindowOpenAndChangesTheCategory() = runComposeUiTest {
        val app = UiShots.newApp()
        val ent = Category.ENTERTAINMENT.label
        val today = LocalDate.now()
        val dayStart = today.atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()
        // A day with nothing on the Mac and one phone stretch across the whole day, so any click on the bar hits it.
        val rep = Json.parseToJsonElement("""{"date":"$today","dayStart":$dayStart,"totals":{"DEEP":86000},"workTotals":{},
            "timeline":[{"a":0,"b":86000,"k":"D","c":"Work adjacent","t":[{"n":"Podcast Addict","s":60000},{"n":"Slack","s":26000}]}],
            "device":{"id":"phone-1","kind":"phone"}}""").jsonObject
        val items = Json.parseToJsonElement("""{"refs":{"Podcast Addict":{"subject":"Podcast Addict","pkg":"com.bambuna.podcastaddict"},"Slack":{"subject":"Slack","pkg":"com.Slack"}}}""").jsonObject
        val empty = app.reports.daily(today.minusYears(1)).copy(date = today, dayStart = dayStart)
        fun load() = dev.ontask.report.withDevices(empty, listOf(rep to items), app::phoneRule)
        var report by mutableStateOf(load(), androidx.compose.runtime.neverEqualPolicy())
        setContent { OnTaskTheme(ThemeMode.LIGHT) { SummaryCard(report, "Today so far", app, onRelabel = { report = load() }) } }

        // Open the stretch: click the bar.
        onNodeWithText("Today so far").assertExists()
        onAllNodesWithText("Work adjacent ▾", substring = true).assertCountEquals(0)
        clickTimeline()
        onNodeWithText("Podcast Addict").assertExists()

        // Pick Entertainment for Podcast Addict: the window stays, and its row says Entertainment.
        onAllNodesWithText("Work adjacent ▾", substring = true)[0].performMouseInput { click(center) }
        waitForIdle()
        onNodeWithText(ent).performMouseInput { click(center) }
        waitForIdle()
        onNodeWithText("Podcast Addict").assertExists()
        onNodeWithText("$ent ▾", substring = true).assertExists()
        // The rule went under the package, and the Mac now shows the phone's minutes as Entertainment.
        assertEquals(Category.ENTERTAINMENT, app.phoneRule(dev.ontask.report.ItemRef("Podcast Addict", pkg = "com.bambuna.podcastaddict")))
        assertEquals(Category.ENTERTAINMENT, report.timeline.first { "Podcast Addict" in it.activity }.category)

        // Slack can be changed too, in the same window.
        onNodeWithText("Work adjacent ▾", substring = true).performMouseInput { click(center) }
        waitForIdle()
        onNodeWithText(ent).performMouseInput { click(center) }
        waitForIdle()
        onAllNodesWithText("$ent ▾", substring = true).assertCountEquals(2)

        // Home's minute reload: the window stays open.
        report = load(); waitForIdle()
        onNodeWithText("Podcast Addict").assertExists()
        onAllNodesWithText("$ent ▾", substring = true).assertCountEquals(2)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.clickTimeline() {
        onNodeWithTag("timeline").performMouseInput { click(center) }
    }
}
