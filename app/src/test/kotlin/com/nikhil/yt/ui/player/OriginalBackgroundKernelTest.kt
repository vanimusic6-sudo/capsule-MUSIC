package com.nikhil.yt.ui.player

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The requested first delivered engine must remain exactly e32d0a35. */
class OriginalBackgroundKernelTest {
    @Test fun firstDeliveredBackgroundKernelIsRestoredByteForByte() {
        val originals = mapOf(
            "CapsuleBackgroundEffects.kt" to "f43927805994fcc9f93da490c25f08232b1e8df80b316692f418b3692701f10c",
            "CapsuleBackgroundMotion.kt" to "59f84720e153b8df028a5589d8c991424165bf1f9bf3e83466428fe7eff5aa5e",
            "CapsuleStaticBackground.kt" to "a63f94d3c8965c5efd24d26bad1c8d3831084e952a0a5cf9fb0be0d52594c509",
            "CapsuleImmersiveContent.kt" to "63218d909173793d51f970ed15349a29afbe5643f9e8ce618184f97467e9ec41",
            "CapsuleImmersiveField.kt" to "bf01e63dcc221f307a69eaefecdfe0ea7d12273a5cf9ec3a52a54533d169f7f2",
            "CapsuleImmersiveFloor.kt" to "067854f8e7a569a70d132e266e7a25670aed8668c992b9bb3204351aafd40a0c",
            "CapsuleVideoAmbient.kt" to "d692eee4d1d600412263cb3da2f917feeb3d06595b23bc8c29925c1aa421306b",
            "CapsuleVideoSurface.kt" to "8e56d0a1f0bcb2cc8ccd2d6276dd1bd1d7697acc28644cd44443e7bbebcc33e8",
            "Player.kt" to "f94e36410f217163e73086372af3758f07339a84fa37957f9e7ae5a9303e18f9",
        )
        for ((name, expected) in originals) {
            val path = "src/main/kotlin/com/nikhil/yt/ui/player/$name"
            val file = listOf(File(path), File("app/$path")).first { it.isFile }
            val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals("Original e32d0a35 background source: $name", expected, actual)
        }
    }
}
