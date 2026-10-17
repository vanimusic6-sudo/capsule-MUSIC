package com.nikhil.yt.constants

/** Only used when each preference key is absent; never overwrite saved values. */
object ReleaseAppearanceDefaults {
    const val capsuleTheme = true
    const val dynamicTheme = false
    const val pureBlack = false
    const val disableBlur = false
    const val cropThumbnail = true
    const val usePlayerThemeInLyrics = true
    const val lightLyricLine = false
    val playerDesign = CapsulePlayerDesign.LIGHT
    val playerBackground = PlayerBackgroundStyle.CAPSULE_GLOW
    val miniPlayerBackground = MiniPlayerBackgroundStyle.CAPSULE_GLOW
    val lyricsAnimation = LyricsAnimationStyle.ARCHIVE_TUNE
}
