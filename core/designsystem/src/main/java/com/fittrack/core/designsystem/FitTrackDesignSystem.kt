package com.fittrack.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

enum class ReadinessTone { NEUTRAL, GREEN, YELLOW, RED }

/**
 * Performance Lime palette.
 *
 * Accent is reserved for navigation, calls to action and selected content.
 * Success, warning and error colors only communicate state.
 */
object FitTrackPalette {
    val Accent = Color(0xFFA3B86A)
    val OnAccent = Color(0xFF111506)
    val AccentContainer = Color(0xFF28301D)
    val OnAccentContainer = Color(0xFFDCE7BF)

    val Background = Color(0xFF090B0D)
    val Surface = Color(0xFF12161A)
    val SurfaceRaised = Color(0xFF1A2025)
    val TextPrimary = Color(0xFFF4F7F8)
    val TextMuted = Color(0xFFAEB8BE)
    val Outline = Color(0xFF3A444C)
    val OutlineSubtle = Color(0xFF283139)

    val Success = Color(0xFF4ADE80)
    val SuccessContainer = Color(0xFF14331F)
    val Warning = Color(0xFFF7C948)
    val WarningContainer = Color(0xFF3B2F10)
    val Error = Color(0xFFFF6B6B)
    val ErrorContainer = Color(0xFF421D22)
    val OnErrorContainer = Color(0xFFFFDADB)

    val ReadinessGood = Color(0xFF176B3A)
    val ReadinessCaution = Color(0xFF725A08)
    val ReadinessLow = Color(0xFF9A3338)
    val ReadinessNeutral = Color(0xFF46515A)
}

private val FitTrackDarkColors = darkColorScheme(
    primary = FitTrackPalette.Accent,
    onPrimary = FitTrackPalette.OnAccent,
    primaryContainer = FitTrackPalette.AccentContainer,
    onPrimaryContainer = FitTrackPalette.OnAccentContainer,
    secondary = FitTrackPalette.Accent,
    onSecondary = FitTrackPalette.OnAccent,
    secondaryContainer = FitTrackPalette.AccentContainer,
    onSecondaryContainer = FitTrackPalette.OnAccentContainer,
    tertiary = FitTrackPalette.Accent,
    onTertiary = FitTrackPalette.OnAccent,
    background = FitTrackPalette.Background,
    onBackground = FitTrackPalette.TextPrimary,
    surface = FitTrackPalette.Surface,
    onSurface = FitTrackPalette.TextPrimary,
    surfaceVariant = FitTrackPalette.SurfaceRaised,
    onSurfaceVariant = FitTrackPalette.TextMuted,
    outline = FitTrackPalette.Outline,
    outlineVariant = FitTrackPalette.OutlineSubtle,
    error = FitTrackPalette.Error,
    errorContainer = FitTrackPalette.ErrorContainer,
    onErrorContainer = FitTrackPalette.OnErrorContainer,
)

@Composable
fun FitTrackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FitTrackDarkColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(30.dp),
        ),
        typography = MaterialTheme.typography.copy(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}

object FitTrackColors {
    val Success = FitTrackPalette.Success
    val SuccessContainer = FitTrackPalette.SuccessContainer
    val Warning = FitTrackPalette.Warning
    val WarningContainer = FitTrackPalette.WarningContainer
    val HeartRate = FitTrackPalette.Error
    val Rest = FitTrackPalette.Accent
    val RestContainer = FitTrackPalette.AccentContainer
    val OnRestContainer = FitTrackPalette.OnAccentContainer
}

@Composable
fun FitTrackCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun SectionTitle(title: String, detail: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        detail?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 76.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun readinessColor(tone: ReadinessTone): Color = when (tone) {
    ReadinessTone.GREEN -> FitTrackPalette.ReadinessGood
    ReadinessTone.YELLOW -> FitTrackPalette.ReadinessCaution
    ReadinessTone.RED -> FitTrackPalette.ReadinessLow
    ReadinessTone.NEUTRAL -> FitTrackPalette.ReadinessNeutral
}

@Composable
fun ReadinessBadge(tone: ReadinessTone, label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(readinessColor(tone), RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics { contentDescription = "Gotowość: $label" },
    )
}

@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(12.dp))
        Text(label)
    }
}

@Composable
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Nie udało się załadować danych", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRetry) { Text("Spróbuj ponownie") }
        }
    }
}

@Composable
fun DestructiveOutlinedButton(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { role = Role.Button; contentDescription = label },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) { Text(label) }
}
