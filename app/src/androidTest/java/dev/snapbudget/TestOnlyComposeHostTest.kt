package dev.snapbudget

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Minimal test-only semantics host; intentionally does not launch or represent a product screen. */
class TestOnlyComposeHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun testHostExposesSemanticIdentityAndBoundedGeometry() {
        compose.setContent {
            Box(Modifier.size(160.dp).testTag("test-harness-root")) {
                Text("Local test host", Modifier.testTag("test-harness-label"))
            }
        }
        compose.onNodeWithTag("test-harness-label").assertTextEquals("Local test host")
        val root = compose.onNodeWithTag("test-harness-root").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithTag("test-harness-label").fetchSemanticsNode().boundsInRoot
        val expectedPixels = 160f * compose.density.density
        assertTrue("Root width should match the bounded 160 dp host", kotlin.math.abs(root.width - expectedPixels) <= 2f)
        assertTrue("Root height should match the bounded 160 dp host", kotlin.math.abs(root.height - expectedPixels) <= 2f)
        assertTrue("Label should remain inside the test host", label.left >= root.left && label.right <= root.right)
        compose.onRoot().assertExists()
    }
}
