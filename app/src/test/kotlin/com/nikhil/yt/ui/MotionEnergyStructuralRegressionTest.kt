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
    fun settingsSwitchUsesOneSofterSpringForThumbColorAndPosition() {
        val preference = source("component/Preference.kt")
        val softSwitch = source("component/CapsuleSoftSwitch.kt")
        assertTrue(preference.contains("LocalRippleConfiguration provides null"))
        assertTrue(preference.contains("CapsuleSoftSwitch("))
        assertFalse(preference.contains("thumbContent = if (checked)"))
        assertFalse(preference.contains("R.drawable.check"))
        assertTrue(softSwitch.contains("dampingRatio = 0.72f"))
        assertTrue(softSwitch.contains("stiffness = 680f"))
        assertTrue(softSwitch.contains("val motion = animateFloatAsState("))
        assertTrue(softSwitch.contains("val amount = motion.value.coerceIn(0f, 1f)"))
        assertTrue(softSwitch.contains("role = Role.Switch"))
        assertTrue(softSwitch.contains("16f + 20f * physicalPosition"))
        assertTrue(softSwitch.contains("8f + 4f * amount"))
        assertTrue(softSwitch.contains("drawOval("))
        assertTrue(softSwitch.contains("val compression = sin("))
        assertTrue(softSwitch.contains("physicalPosition = motion.value.coerceIn(-0.075f, 1.075f)"))
        assertTrue(softSwitch.contains(".minimumInteractiveComponentSize()"))
        assertFalse(softSwitch.contains("rememberInfiniteTransition"))
    }

    @Test
    fun dragPriorityHasNonBouncyNeighbourMovementAndNoPersistPerCrossing() {
        val source = source("screens/settings/PriorityOrderDialog.kt")
        assertTrue(source.contains("animateItemModifier = Modifier.animateItem("))
        assertTrue(source.contains("placementSpec = PriorityMoveSpec"))
        assertTrue(source.contains("dampingRatio = 0.93f"))
        assertTrue(source.contains("stiffness = 235f"))
        assertTrue(source.contains("pendingOrderChange = true"))
        assertTrue(source.contains("onDragStopped = {"))
        assertTrue(source.contains("saveSettledOrder()"))
        val onMove = source.substringAfter("rememberReorderableLazyListState(lazyListState) { from, to ->")
            .substringBefore("CapsuleMotionDialog(onDismissRequest")
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
        assertTrue(source.contains("delay(310)"))
        assertTrue(source.contains("enabled = !isReordering && !dialogClosing"))
        assertTrue(source.contains("if (dialogClosing) Modifier else Modifier.draggableHandle("))
        assertTrue(source.contains("targetState = position"))
        assertTrue(source.contains("label = \"priorityRankChange\""))
        assertFalse("no immediate DataStore write on finger-up", source.contains("onDragStopped = {\n                                        saveSettledOrder()"))
        assertTrue(source.contains(".heightIn(max = maxHeight - 24.dp)"))
        assertTrue(source.contains(".weight(1f, fill = false)"))
        assertTrue(source.contains(".size(48.dp)"))
        assertTrue(source.contains(".heightIn(min = 104.dp)"))
        assertTrue(source.contains("maxLines = 2"))
        assertTrue(source.contains("scaleX = scale.value"))
    }

    @Test
    fun settingsSubpagesUseTheSameShorterHalfPushAsRoot() {
        val routes = source("screens/ScreenTransitions.kt")
        assertTrue(routes.contains("NESTED_ENTER_MS = 235"))
        assertTrue(routes.contains("NESTED_EXIT_MS = 190"))
        assertTrue(routes.contains("if (isPop) -width / 2 else width / 2"))
        assertTrue(routes.contains("if (isPop) width / 2 else -width / 2"))
        assertTrue(routes.contains("animationSpec = tween(NESTED_ENTER_MS, easing = NavigationEase)"))
        assertTrue(routes.contains("animationSpec = tween(NESTED_EXIT_MS, easing = ExitEase)"))
        assertFalse(routes.contains("if (isPop) -width else width"))
    }

    @Test
    fun rootTransitionPreventsTwoReadableSettingsScreensOverlapping() {
        val routes = source("screens/ScreenTransitions.kt")
        assertTrue(routes.contains("initialAlpha = 0.82f"))
        assertTrue(routes.contains("animationSpec = tween(110, easing = ExitEase)"))
        assertTrue(routes.contains("animationSpec = tween(85, easing = ExitEase)"))
        assertTrue(routes.contains("animationSpec = tween(130, easing = NavigationEase)"))
        assertTrue(routes.contains("animationSpec = tween(110, easing = NavigationEase)"))
        assertFalse(routes.contains("initialAlpha = 0.02f"))
    }

    @Test
    fun settingsArchiveTuneBoundaryMatchesHalfWidthAndCrossFade() {
        val routes = source("screens/ScreenTransitions.kt")
        assertTrue(routes.contains("SettingsRouteMotion.NESTED"))
        assertTrue(routes.contains("SettingsRootInitialScale = 1f"))
        assertTrue(routes.contains("SettingsPageTravel(0.5f, 1f)"))
        assertTrue(routes.contains("if (isPop) width / 2 else -width / 2"))
        assertTrue(routes.contains("direction * width / 2"))
        assertTrue(routes.contains("ROOT_ENTER_MS = 255"))
        assertTrue(routes.contains("ROOT_EXIT_MS = 205"))
        assertTrue(routes.contains("fadeIn("))
        assertTrue(routes.contains("fadeOut("))
        assertFalse("No half-shrunken page should pop at destination disposal", routes.contains("scaleOut("))
        assertFalse("No full-viewport page slide should hang offscreen", routes.contains("targetOffsetX = { it }"))
    }

    @Test
    fun dialogsFadeAwayBeforeWindowDisposalInsteadOfReversingEntrance() {
        val helper = source("motion/SettingsDialogEntrance.kt")
        val modal = source("motion/CapsuleModalDialog.kt")
        val dialog = source("component/Dialog.kt")
        val prefs = source("component/Preference.kt")
        assertTrue(helper.contains("parentProgress ?: localProgress"))
        assertTrue(helper.contains("LocalCapsuleModalExit.current"))
        assertTrue(helper.contains("alpha = departureAlpha"))
        assertTrue(helper.contains("down * 21f"))
        assertTrue(helper.contains("val lift = -2.5f"))
        assertTrue(helper.contains("down * 0.022f"))
        val settings = source("screens/settings/VeluneSettingsScreen.kt")
        assertTrue(settings.contains("val closeDialog = LocalCapsuleModalClose.current"))
        assertTrue(settings.contains("DefaultDialog("))
        assertFalse(settings.contains("AlertDialog("))
        assertTrue(modal.contains("exitProgress.animateTo("))
        assertTrue(modal.contains("targetValue = 1f"))
        assertTrue(modal.contains("durationMillis = 185"))
        assertFalse(modal.contains("targetValue = 0f,"))
        assertTrue(modal.contains("LocalCapsuleModalExit provides exitProgress"))
        assertTrue(dialog.contains("CapsuleMotionDialog("))
        assertTrue(prefs.contains("closeSelection {"))
        assertTrue(prefs.contains("enabled = !isDialogClosing"))
        assertTrue(modal.contains("LocalCapsuleModalClosing provides closing"))
        assertTrue(dialog.contains(".capsuleDialogEntrance()"))
        assertFalse(modal.contains("rememberInfiniteTransition"))
    }
}
