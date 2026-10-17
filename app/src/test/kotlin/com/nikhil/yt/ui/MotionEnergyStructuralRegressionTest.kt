package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guards for the motion ownership/energy regressions. These are intentionally
 * structural; they complement but do not replace real frame-timing and thermal device tests.
 */
class MotionEnergyStructuralRegressionTest {
    private fun source(relativePath: String): String {
        val relative = "com/nikhil/yt/ui/$relativePath"
        return listOf(
            File("src/main/kotlin/$relative"),
            File("app/src/main/kotlin/$relative"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing source file: $relative")
    }

    @Test
    fun navigationIndicatorSamplesTheSpringDuringPlacementNotComposition() {
        val content = source("component/FluidSlidingNavigationBar.kt")
        assertTrue(content.contains("val indicatorOffset = animateDpAsState("))
        assertTrue(content.contains("x = indicatorOffset.value.roundToPx()"))
        assertTrue(content.contains(".offset {"))
        assertFalse(content.contains("val indicatorOffset by animateDpAsState("))
        assertFalse(content.contains("Modifier.offset(x = indicatorOffset,"))
    }

    @Test
    fun settingsModeReorderReusesSpecsAndCannotBounce() {
        val content = source("screens/settings/VeluneSettingsScreen.kt")
        assertEquals(14, content.split(".animateItem(").size - 1)
        assertEquals(14, content.split("placementSpec = SettingsRowPlacement").size - 1)
        assertEquals(1, content.split("private val SettingsRowPlacement = spring<IntOffset>(").size - 1)
        assertTrue(content.contains("dampingRatio = Spring.DampingRatioNoBouncy"))
        assertEquals(14, content.split("fadeInSpec = SettingsRowFadeIn").size - 1)
        assertEquals(14, content.split("fadeOutSpec = SettingsRowFadeOut").size - 1)
    }

    @Test
    fun animatedBottomSheetRetainsGestureShieldUntilItIsGone() {
        val content = source("component/BottomSheetPage.kt")
        assertTrue(content.contains("MutableTransitionState(false)"))
        assertTrue(content.contains("visibility.targetState = state.isVisible"))
        assertTrue(content.contains("visibility.currentState || visibility.targetState"))
        assertTrue(content.contains("visibleState = visibility"))
        assertTrue(content.contains("BackHandler(enabled = state.isVisible)"))
        assertFalse(content.contains("visible = state.isVisible,"))
    }

    @Test
    fun settingsPressIsPhysicalButDoesNotRelayoutOrCreateRippleOverlays() {
        val preference = source("component/Preference.kt")
        val category = source("screens/settings/VeluneSettingsScreen.kt")
        assertTrue(preference.contains("collectIsPressedAsState()"))
        assertTrue(preference.contains("label = \"preferenceRowPress\""))
        assertTrue(preference.contains("indication = null"))
        assertTrue(category.contains("settingsRowPress(onClick)"))
        assertTrue(category.contains("label = \"settingsCategoryPress\""))
        assertTrue(category.contains("scaleX = scale"))
        assertFalse(category.contains(".clickable(onClick = onClick)"))
    }

    @Test
    fun settingsSwitchUsesNativeThumbGrowthWithoutTransparentTapHalo() {
        val source = source("component/Preference.kt")
        assertTrue(source.contains("LocalRippleConfiguration provides null"))
        assertTrue(source.contains("thumbContent = if (checked)"))
        assertTrue(source.contains("} else {\n                        null\n                    },"))
        assertFalse(source.contains("R.drawable.close"))
    }

    @Test
    fun dragPriorityHasNonBouncyNeighbourMovementAndNoPersistPerCrossing() {
        val source = source("screens/settings/PriorityOrderDialog.kt")
        assertTrue(source.contains("animateItemModifier = Modifier.animateItem("))
        assertTrue(source.contains("placementSpec = PriorityMoveSpec"))
        assertTrue(source.contains("dampingRatio = Spring.DampingRatioNoBouncy"))
        assertTrue(source.contains("pendingOrderChange = true"))
        assertTrue(source.contains("onDragStopped = {"))
        assertTrue(source.contains("saveSettledOrder()"))
        val onMove = source.substringAfter("rememberReorderableLazyListState(lazyListState) { from, to ->")
            .substringBefore("Dialog(onDismissRequest")
        assertFalse("Do not write DataStore once per crossing", onMove.contains("onOrderChange(reordered)"))
        assertTrue(source.contains("shadowElevation = lift.value.toPx()"))
        assertTrue(source.contains("shadowElevation = 0.dp"))
        assertFalse(source.contains("val lift by animateDpAsState("))
    }

    @Test
    fun priorityReorderNeverPublishesDuringDragAndKeepsActionsVisible() {
        val source = source("screens/settings/PriorityOrderDialog.kt")
        assertTrue(source.contains("var isReordering by remember"))
        assertTrue(source.contains("LaunchedEffect(isReordering, pendingOrderChange)"))
        assertTrue(source.contains("if (!isReordering && pendingOrderChange)"))
        assertTrue(source.contains("delay(320)"))
        assertFalse("no immediate DataStore write on finger-up", source.contains("onDragStopped = {\n                                        saveSettledOrder()"))
        assertTrue(source.contains(".heightIn(max = maxHeight - 24.dp)"))
        assertTrue(source.contains(".weight(1f, fill = false)"))
        assertTrue(source.contains(".size(48.dp)"))
        assertTrue(source.contains(".heightIn(min = 104.dp)"))
        assertTrue(source.contains("maxLines = 2"))
        assertTrue(source.contains("scaleX = scale.value"))
    }

    @Test
    fun settingsNavigationIsFullWidthPushWithoutNestedScaleOrFade() {
        val routes = source("screens/ScreenTransitions.kt")
        assertTrue(routes.contains("SettingsRouteMotion.NESTED"))
        assertTrue(routes.contains("initialOffsetX = { if (isPop) -it else it }"))
        assertTrue(routes.contains("targetOffsetX = { if (isPop) it else -it }"))
        assertTrue(routes.contains("TopRight = TransformOrigin(1f, 0f)"))
        assertTrue(routes.contains("initialScale = SettingsRootInitialScale"))
        assertTrue(routes.contains("targetScale = SettingsRootInitialScale"))
        assertTrue(routes.contains("durationMillis = 340"))
        assertFalse(routes.contains("settingsRootOffset"))
        assertFalse(routes.contains("slideIn(\n"))
        val nested = routes.substringAfter("SettingsRouteMotion.NESTED ->")
            .substringBefore("SettingsRouteMotion.ROOT ->")
        assertFalse("nested route must not squeeze glyphs", nested.contains("scaleIn("))
        assertFalse("nested route must not fade and overlap", nested.contains("fadeIn("))
    }

    @Test
    fun sharedSettingsDialogsUseFiniteEntranceOnly() {
        val helper = source("motion/SettingsDialogEntrance.kt")
        val dialog = source("component/Dialog.kt")
        assertTrue(helper.contains("LaunchedEffect(arrival)"))
        assertTrue(helper.contains("arrival.animateTo("))
        assertTrue(helper.contains("translationY = remaining * 16.dp.toPx()"))
        assertTrue(helper.contains("scaleX = sizeFactor"))
        assertTrue(helper.contains("dampingRatio = 0.93f"))
        assertFalse(helper.contains("rememberInfiniteTransition"))
        assertTrue(dialog.contains(".capsuleDialogEntrance()"))
    }
}
