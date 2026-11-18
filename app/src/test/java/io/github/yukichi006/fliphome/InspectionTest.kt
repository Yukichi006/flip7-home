package io.github.yukichi006.fliphome

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * リングの診断（[Inspection]）のテスト。
 * 0.36.1 では、専用アプリへ切り替えると FlipHome の設定パネルが閉じ、そのときに診断も止まって、記録が取れなかった。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class InspectionTest {
    @After
    fun tearDown() = Inspection.stop()

    @Test
    fun `専用アプリへ切り替えてホーム画面に戻っても、診断は続き、記録が残る`() {
        shadowOf(T.app as android.app.Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val ring = T.app.getSystemService(BluetoothManager::class.java)!!.adapter.getRemoteDevice("74:02:CB:40:C8:E7")
        Inspection.start(controller.get(), ring)
        T.idle()
        assertTrue("診断が始まっていない", Inspection.running)

        // ほかのアプリへ切り替え、ホームボタンで FlipHome に戻る（開いていたパネルは閉じる）
        controller.pause().stop()
        controller.restart().resume()
        controller.newIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        T.idle()

        assertTrue("パネルが閉じたときに、診断が止まってしまった", Inspection.running)
        assertTrue("記録が残っていない", Inspection.lines.any { "evofit" in it || "74:02:CB:40:C8:E7" in it })
    }
}
