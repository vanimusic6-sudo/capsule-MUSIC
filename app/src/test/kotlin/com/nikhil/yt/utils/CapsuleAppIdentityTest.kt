package com.nikhil.yt.utils

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Xml
import com.nikhil.yt.BuildConfig
import com.nikhil.yt.MainActivity
import com.nikhil.yt.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class CapsuleAppIdentityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun installedVariantIsIndependentOfTheUpstreamAndSourceNamespace() {
        val suffix = when (BuildConfig.BUILD_TYPE) {
            "debug" -> ".debug"
            "devRelease" -> ".dev"
            else -> ""
        }
        assertEquals("com.vanimusic.capsule" + suffix, context.packageName)
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        assertEquals("com.vanimusic.capsule", ReleaseApkVerifier.OFFICIAL_PACKAGE)
        assertEquals(
            "com.nikhil.yt.MainActivity",
            context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0).name,
        )
    }

    @Test fun launcherShortcutsResolveInsideTheirOwnVariant() {
        val intents = mutableListOf<Intent>()
        context.resources.getXml(R.xml.shortcuts).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "intent") {
                    intents += Intent.parseIntent(context.resources, parser, Xml.asAttributeSet(parser))
                }
            }
        }
        assertEquals(setOf(MainActivity.ACTION_SEARCH, MainActivity.ACTION_LIBRARY), intents.map { it.action }.toSet())
        assertEquals(2, intents.size)
        for (intent in intents) {
            assertEquals(context.packageName, intent.component?.packageName)
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertTrue(intent.action!!.startsWith(context.packageName + ".action."))
            assertNotNull(context.packageManager.resolveActivity(intent, 0))
        }
    }

    @Test fun fileAndVideoAuthoritiesFollowTheInstalledVariant() {
        for (suffix in listOf(".FileProvider", ".capsule.video.extractor")) {
            val provider = context.packageManager.resolveContentProvider(context.packageName + suffix, 0)
            assertNotNull(suffix, provider)
            assertEquals(context.packageName, provider!!.packageName)
            assertFalse(provider.exported)
            assertNull(context.packageManager.resolveContentProvider("com.nikhil.yt" + suffix, 0))
        }
    }

    @Test fun capsuleLinksOpenCapsuleWithoutClaimingVeluneLinks() {
        fun resolve(scheme: String, host: String) = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://$host/example"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(context.packageName),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        for (host in listOf("together", "playlist")) {
            assertEquals(MainActivity::class.java.name, resolve("capsule", host)?.activityInfo?.name)
            assertNull(resolve("velune", host))
        }
    }
}
