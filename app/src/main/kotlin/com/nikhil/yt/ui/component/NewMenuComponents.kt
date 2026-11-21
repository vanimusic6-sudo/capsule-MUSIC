/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.ui.motion.CapsuleStandardEasing

/** Original opaque Capsule menu palette, shared by all action tiles. */
internal val CapsuleMenuBlack = Color(0xFF111113)
internal val CapsuleMenuPressed = Color(0xFF242428)
internal val CapsuleMenuTileShape = RoundedCornerShape(18.dp)
// An 8dp visual rhythm. Exterior row padding is never part of the hit target.
internal val CapsuleMenuTileGap = 8.dp
internal val CapsuleMenuRowPadding = 4.dp
internal val CapsuleMenuMinRowHeight = 64.dp
internal val CapsuleMenuMinActionHeight = 100.dp
internal val CapsuleMenuIconSize = 28.dp

internal fun capsuleCompactMenuStackedLabel(label: String): Boolean =
    label.split(' ', '-', '—').any { it.length >= 12 }

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
    val tileColor = capsuleMenuOpaqueTile(CapsuleMenuBlack, CapsuleMenuPressed, pressed)
    Card(
        modifier = actionModifier,
        colors = CardDefaults.cardColors(
            containerColor = tileColor,
        ),
        shape = CapsuleMenuTileShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Exact shared size, independent of one- or two-line titles.
                .heightIn(min = CapsuleMenuMinActionHeight, max = CapsuleMenuMinActionHeight)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // The icon is the decoration, not another grey button-in-a-button.
            Box(Modifier.size(CapsuleMenuIconSize), contentAlignment = Alignment.Center) {
                icon()
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = if (pressGuard) MaterialTheme.colorScheme.onSurface else animatedContent,
                textAlign = TextAlign.Center,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Short horizontal actions for the player sheet: two compact columns instead
 * of five enormous vertically stacked icon tiles. Unlike the old acrylic
 * cards every action has an opaque Material surface at rest.
 */
@Composable
internal fun CapsuleCompactMenuAction(
    action: NewAction,
    modifier: Modifier = Modifier,
) {
    val guard = LocalCapsuleMenuPressGuard.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val clickableModifier = if (guard) {
        modifier.combinedClickable(
            enabled = action.enabled,
            interactionSource = interaction,
            indication = null,
            onClick = action.onClick,
            onLongClick = {},
        )
    } else {
        modifier.clickable(
            enabled = action.enabled,
            interactionSource = interaction,
            indication = null,
            onClick = action.onClick,
        )
    }
    val tileColor = capsuleMenuOpaqueTile(CapsuleMenuBlack, CapsuleMenuPressed, pressed)
    val labelInk = if (action.enabled) Color.White else Color.White.copy(alpha = 0.52f)
    Card(
        modifier = clickableModifier,
        shape = CapsuleMenuTileShape,
        colors = CardDefaults.cardColors(containerColor = tileColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        // Uniform 24dp icons and up to two lines; no orientation switching.
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = CapsuleMenuMinActionHeight, max = CapsuleMenuMinActionHeight)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(CapsuleMenuIconSize), contentAlignment = Alignment.Center) {
                action.icon()
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = action.text,
                style = MaterialTheme.typography.labelLarge,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = labelInk,
                textAlign = TextAlign.Center,
                softWrap = true,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A universal, inexpensive row for all Capsule menus. Material3's default
 * ListItem container used to paint a black opaque rectangle even when its
 * parent sheet was already opaque. The hierarchy now owns exactly one solid
 * sheet and a soft transparent grouping. This also gives legacy menus the
 * same rounded icon wells as the current player, without replacing assets.
 *
 * Explicit caller colours and destructive-action semantics are preserved.
 */
@Composable
fun CapsuleMenuListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    highlightBorder: Boolean = false,
) {
    // The 4dp outside margin belongs to the layout, NOT to the click hitbox.
    // Adjacent 64dp targets therefore keep a visible 8dp rounded gap.
    Box(Modifier.fillMaxWidth().padding(vertical = CapsuleMenuRowPadding)) {
        ListItem(
            headlineContent = headlineContent,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CapsuleMenuMinRowHeight)
                .clip(CapsuleMenuTileShape)
                .background(CapsuleMenuBlack)
                .then(
                    if (highlightBorder) Modifier.border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)),
                        CapsuleMenuTileShape,
                    ) else Modifier
                )
                .then(modifier),
            overlineContent = overlineContent,
            supportingContent = supportingContent,
            leadingContent = if (leadingContent == null) null else {
                {
                    Box(Modifier.size(CapsuleMenuIconSize), contentAlignment = Alignment.Center) {
                        leadingContent()
                    }
                }
            },
            trailingContent = trailingContent,
            colors = colors,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
        )
    }
}

// All menu rows delegate to the same tappable surface and shape tokens.
@Composable
fun NewMenuItem(
    headlineContent: @Composable () -> Unit,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    CapsuleMenuListItem(
        headlineContent = headlineContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        supportingContent = supportingContent,
        modifier = modifier.capsulePressClickable(enabled = enabled) {
            onClick?.invoke()
        },
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
    compact: Boolean = false,
): Int = when {
    requested < 1 -> 1
    compact -> minOf(requested, 2)
    requested >= 3 && screenWidthDp < 480 && labels.any { it.length > 11 } -> 2
    else -> requested
}

// Enhanced Action Grid - Material 3 Expressive Design
@Composable
fun NewActionGrid(
    actions: List<NewAction>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    compact: Boolean = true,
) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val safeColumns = remember(actions, columns, widthDp, compact) {
        capsuleActionGridColumns(columns, widthDp, actions.map { it.text }, compact)
    }
    // Keep menu row groups stable while the sheet is animating or scrolling.
    val rows = remember(actions, safeColumns) { actions.chunked(safeColumns) }
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(CapsuleMenuTileGap),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CapsuleMenuTileGap),
            ) {
                row.forEach { action ->
                    if (compact) {
                        CapsuleCompactMenuAction(
                            action = action,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        NewActionButton(
                            icon = action.icon,
                            text = action.text,
                            onClick = action.onClick,
                            modifier = Modifier.weight(1f),
                            enabled = action.enabled,
                            backgroundColor = if (action.backgroundColor != Color.Unspecified) action.backgroundColor else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (action.contentColor != Color.Unspecified) action.contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // A final unpaired action retains its normal width.
                // Fill only the unused grid cell with a non-clickable spacer.
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
            containerColor = capsuleMenuOpaqueTile(
                CapsuleMenuBlack, CapsuleMenuPressed, pressed,
            )
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
            .padding(horizontal = 0.dp)
            .padding(bottom = 24.dp)
    ) {
        content()
    }
}

@Composable
fun MenuSurfaceSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Section groups never paint an opaque merged canvas.
    Column(modifier = modifier.fillMaxWidth(), content = content)
}
