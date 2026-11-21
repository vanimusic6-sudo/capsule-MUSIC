/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.ui.motion.CapsuleStandardEasing

/**
 * Every interactive menu control must remain visibly opaque regardless of
 * the sheet's presentation or a custom colour's alpha. This also eliminates
 * the former 6.5%-alpha "acrylic" cards, which vanished on some devices.
 */
internal fun capsuleMenuOpaqueTile(
    normal: Color,
    pressedColor: Color,
    pressed: Boolean,
): Color = (if (pressed) pressedColor else normal).copy(alpha = 1f)

// Enhanced Action Button - Material 3 Expressive Design
@Composable
fun NewActionButton(
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val animatedBackground by animateColorAsState(
        targetValue = if (enabled) backgroundColor else backgroundColor.copy(alpha = 0.5f),
        animationSpec = tween(200, easing = CapsuleStandardEasing),
        label = "background"
    )
    
    val animatedContent by animateColorAsState(
        targetValue = if (enabled) contentColor else contentColor.copy(alpha = 0.5f),
        animationSpec = tween(200, easing = CapsuleStandardEasing),
        label = "content"
    )

    // Only Capsule's More action buttons swallow long-press, which must
    // never be mistaken for a request to move the whole sheet. The real
    // ModalBottomSheet (including handle swipes and scroll) remains native.
    val pressGuard = LocalCapsuleMenuPressGuard.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val actionModifier =
        if (pressGuard) {
            modifier.combinedClickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = {},
            )
        } else {
            modifier.clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
        }
    // Both normal and pressed cards have an opaque, readable material.
    // Keep explicit action colours when supplied, but reject translucency
    // inside a modal sheet: the cross-window backdrop is not reliable.
    val tileColor = if (pressGuard) capsuleMenuOpaqueTile(
        normal = if (backgroundColor == MaterialTheme.colorScheme.surfaceVariant)
            MaterialTheme.colorScheme.surfaceContainerHighest else animatedBackground,
        pressedColor = MaterialTheme.colorScheme.secondaryContainer,
        pressed = pressed,
    ) else if (pressed) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = ActiveBoxAlpha)
    else animatedBackground
    Card(
        modifier = actionModifier,
        colors = CardDefaults.cardColors(
            containerColor = tileColor,
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = if (pressGuard) MaterialTheme.colorScheme.onSurface else animatedContent,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // Scrolling a sheet with 3–9 independently running marquee
                // clocks causes frequent small re-layouts at 60Hz. The text
                // already has a 2-line ellipsis, so do not run a marquee
                // inside the player/Lyrics More menu.
                modifier = if (pressGuard) Modifier else Modifier.basicMarquee(),
            )
        }
    }
}

// Enhanced Menu Item - Material 3 Expressive Design
@Composable
fun NewMenuItem(
    headlineContent: @Composable () -> Unit,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.ListItem(
        headlineContent = headlineContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        supportingContent = supportingContent,
        modifier = modifier
            .capsulePressClickable(enabled = enabled) { onClick?.invoke() }
            .padding(horizontal = 4.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        tonalElevation = 0.dp
    )
}

// Enhanced Menu Section Header - Material 3 Expressive Design
@Composable
fun NewMenuSectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp)
    )
}

// Two readable columns beat three broken Cyrillic words on a narrow phone.
// A tablet keeps the intended 3-column layout; no font-layout polling.
internal fun capsuleActionGridColumns(
    requested: Int,
    screenWidthDp: Int,
    labels: List<String>,
): Int = when {
    requested < 1 -> 1
    requested >= 3 && screenWidthDp < 480 && labels.any { it.length > 11 } -> 2
    else -> requested
}

// Enhanced Action Grid - Material 3 Expressive Design
@Composable
fun NewActionGrid(
    actions: List<NewAction>,
    modifier: Modifier = Modifier,
    columns: Int = 3
) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val safeColumns = remember(actions, columns, widthDp) {
        capsuleActionGridColumns(columns, widthDp, actions.map { it.text })
    }
    // Keep menu row groups stable while the sheet is animating or scrolling.
    val rows = remember(actions, safeColumns) { actions.chunked(safeColumns) }
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { action ->
                    NewActionButton(
                        icon = action.icon,
                        text = action.text,
                        onClick = action.onClick,
                        modifier = Modifier.weight(1f),
                        enabled = action.enabled,
                        backgroundColor = if (action.backgroundColor != Color.Unspecified) action.backgroundColor else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (action.contentColor != Color.Unspecified) action.contentColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Fill remaining space if row is not full
                repeat(safeColumns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// Enhanced Action Data Class
data class NewAction(
    val icon: @Composable () -> Unit,
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val backgroundColor: Color = Color.Unspecified,
    val contentColor: Color = Color.Unspecified
)

// Enhanced Menu Content - Material 3 Expressive Design
@Composable
fun NewMenuContent(
    headerContent: @Composable (() -> Unit)? = null,
    actionGrid: @Composable (() -> Unit)? = null,
    menuItems: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        headerContent?.invoke()
        
        // Action Grid
        actionGrid?.invoke()
        
        // Divider if both header and actions exist
        if (headerContent != null && actionGrid != null) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
        
        // Menu Items
        menuItems?.invoke()
    }
}

// Enhanced Icon Button - Material 3 Expressive Design
@Composable
fun NewIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val animatedBackground by animateColorAsState(
        targetValue = if (enabled) backgroundColor else backgroundColor.copy(alpha = 0.5f),
        animationSpec = tween(200, easing = CapsuleStandardEasing),
        label = "background"
    )
    
    val animatedContent by animateColorAsState(
        targetValue = if (enabled) contentColor else contentColor.copy(alpha = 0.5f),
        animationSpec = tween(200, easing = CapsuleStandardEasing),
        label = "content"
    )

    // Only Capsule's More action buttons swallow long-press, which must
    // never be mistaken for a request to move the whole sheet. The real
    // ModalBottomSheet (including handle swipes and scroll) remains native.
    val pressGuard = LocalCapsuleMenuPressGuard.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val actionModifier =
        if (pressGuard) {
            modifier.combinedClickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = {},
            )
        } else {
            modifier.clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
        }
    Card(
        modifier = actionModifier,
        colors = CardDefaults.cardColors(
            containerColor = if (pressGuard) capsuleMenuOpaqueTile(
                normal = animatedBackground,
                pressedColor = MaterialTheme.colorScheme.secondaryContainer,
                pressed = pressed,
            ) else if (pressed) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = ActiveBoxAlpha)
            else animatedBackground
        ),
        shape = CircleShape,
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
    }
}

// Enhanced Menu Container - Material 3 Expressive Design
@Composable
fun NewMenuContainer(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        content()
    }
}

@Composable
fun MenuSurfaceSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        // A list section MUST own a real opaque surface. Previously it was
        // fully transparent while the parent was an unreliable Haze window.
        // Use an adjacent tonal step, not a black overlay or GPU blur.
        color = if (LocalCapsuleMenuPressGuard.current)
            MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}
