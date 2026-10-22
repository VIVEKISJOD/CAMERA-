package app.fovea.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fovea.camera.ui.theme.Fovea

@Composable
fun FoveaChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true
) {
    val bg = if (selected) Fovea.Accent else Fovea.Surface2
    val fg = when {
        selected -> Fovea.Black
        enabled -> Fovea.Text
        else -> Fovea.Muted.copy(alpha = 0.6f)
    }
    Box(
        modifier
            .heightIn(min = Fovea.TouchTarget)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(bg)
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) { Text(label, color = fg, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun UiOnlyTag(text: String = "UI ONLY") {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(Fovea.Line)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) { Text(text, color = Fovea.Muted, style = MaterialTheme.typography.labelSmall) }
}

@Composable
fun FoveaTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(Fovea.TouchTarget)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Fovea.Text)
            }
        } else Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Fovea.Text, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().background(Fovea.Black)) {
        FoveaTopBar(title, onBack, actions)
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(), color = Fovea.Muted, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = Fovea.ScreenPadding, top = 20.dp, bottom = 6.dp)
    )
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Fovea.ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Fovea.Text, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, color = Fovea.Muted, style = MaterialTheme.typography.bodySmall)
        }
        trailing()
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Fovea.Black, checkedTrackColor = Fovea.Accent,
                uncheckedThumbColor = Fovea.Muted, uncheckedTrackColor = Fovea.Surface2,
                uncheckedBorderColor = Fovea.Line
            )
        )
    }
}

@Composable
fun StateView(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = Fovea.Muted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = Fovea.Text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, color = Fovea.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            FoveaChip(actionLabel, onAction, selected = true)
        }
    }
}

@Composable
fun LoadingView(label: String = "Loading") {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Fovea.Accent, strokeWidth = 3.dp)
        Spacer(Modifier.height(12.dp))
        Text(label, color = Fovea.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(Fovea.TouchTarget).clip(CircleShape).background(Fovea.Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = Fovea.Text) }
}
