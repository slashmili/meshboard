package dev.meshboard.android

import android.Manifest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic QR/image and camera-less emulator coverage; physical scanning is separate. */
@OptIn(ExperimentalTestApi::class)
class QrScannerTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>(effectContext = StandardTestDispatcher())
    private val invite = "https://board.example/#crdt=123e4567-e89b-42d3-a456-426614174000&key=${"A".repeat(43)}"

    @Test fun generatedQrDecodesToFullEncryptedInviteAndRejectsArbitraryUrls() {
        val matrix = QRCodeWriter().encode(invite, BarcodeFormat.QR_CODE, 512, 512)
        val pixels = IntArray(512 * 512) { if (matrix[it % 512, it / 512]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(512,512,pixels)))).text
        assertEquals(invite, decoded)
        assertEquals("A".repeat(43), AndroidController.parseInvite(decoded).third)
        for (invalid in listOf("javascript:alert(1)", "https://board.example/", invite.replace("key=", "other="), invite.replace("https://", "http://"))) {
            assertTrue(runCatching { AndroidController.parseInvite(invalid) }.isFailure)
        }
    }

    @Test fun cameraUnavailableAndBackgroundingKeepDrawingAndPasteFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(rule.activity.packageName, Manifest.permission.CAMERA)
        val canvas = rule.onNodeWithTag("mobile-canvas")
        canvas.performTouchInput { swipe(Offset(300f,200f),Offset(500f,300f),300) }
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-join").performClick()
        rule.onNodeWithTag("mobile-scan-qr").performScrollTo().performClick()
        rule.onNodeWithText("Scan a board QR code").assertIsDisplayed()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithText("Cancel · Paste invite").performClick()
        rule.onNodeWithTag("mobile-invite-input").assertIsDisplayed()
        rule.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        assertEquals("1 objects", canvas.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        rule.onNodeWithTag("mobile-more").performClick()
        rule.onNodeWithTag("mobile-join").performClick()
        rule.onNodeWithTag("mobile-scan-qr").performScrollTo().performClick()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(5000) { runCatching { canvas.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "1 objects" }.getOrDefault(false) }
        rule.onNodeWithText("Scan a board QR code").assertDoesNotExist()
    }
}
