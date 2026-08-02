package io.github.clinal.cordis

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.clinal.cordis.data.InstanceRepository
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class CordisAppE2eTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Before
    fun clearState() {
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getSharedPreferences("cordis_instances", 0)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun addInstanceStartsRuntime() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntilAtLeastOneExists("cordis.addInstance", FIRST_RUN_TIMEOUT_MILLIS)
            composeRule.waitUntilNoNodes("cordis.bootstrapOverlay", FIRST_RUN_TIMEOUT_MILLIS)

            composeRule.onNodeWithTag("cordis.title").assertIsDisplayed()
            composeRule.onNodeWithTag("cordis.addInstance").assertIsEnabled()

            composeRule.onNodeWithTag("cordis.addInstance").performClick()

            composeRule.onNodeWithTag("cordis.instance.instance-1").assertIsDisplayed()
            composeRule.onNodeWithTag("cordis.instance.instance-1.start").performClick()

            composeRule.waitUntilAtLeastOneExists(
                tag = "cordis.status.running",
                timeoutMillis = RUNTIME_START_TIMEOUT_MILLIS,
            )
            composeRule.onNodeWithTag("cordis.status.running").assertIsDisplayed()
            composeRule.waitUntil(RUNTIME_START_TIMEOUT_MILLIS) {
                cordisServerResponds()
            }
            composeRule.onNodeWithTag("cordis.instance.instance-1.stop").performClick()
        }
    }

    private fun cordisServerResponds(): Boolean {
        val connection = URL("http://127.0.0.1:${InstanceRepository.DEFAULT_BASE_PORT}")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = HTTP_TIMEOUT_MILLIS.toInt()
        connection.readTimeout = HTTP_TIMEOUT_MILLIS.toInt()
        return try {
            connection.responseCode in 200..499
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun ComposeTestRule.waitUntilAtLeastOneExists(tag: String, timeoutMillis: Long) {
        waitUntil(timeoutMillis) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeTestRule.waitUntilNoNodes(tag: String, timeoutMillis: Long) {
        waitUntil(timeoutMillis) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        const val FIRST_RUN_TIMEOUT_MILLIS = 120_000L
        const val RUNTIME_START_TIMEOUT_MILLIS = 180_000L
        const val HTTP_TIMEOUT_MILLIS = 1_000L
    }
}
