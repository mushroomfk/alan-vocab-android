package com.alanbeidanci.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alanbeidanci.app.ui.theme.AlanAmber
import com.alanbeidanci.app.ui.theme.AlanHero

data class BottomDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val bottomDestinations = listOf(
    BottomDestination("home", "首页", Icons.Outlined.Home),
    BottomDestination("search", "查词", Icons.Outlined.Search),
    BottomDestination("review", "复习", Icons.Outlined.Refresh),
    BottomDestination("settings", "我的", Icons.Outlined.Person),
)

@Composable
fun AlanTopBar(darkMode: Boolean, onToggleTheme: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("A", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("Alan背单词", style = MaterialTheme.typography.titleMedium)
                    Text("让每个单词留下来", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(
                onClick = onToggleTheme,
                modifier = Modifier
                    .size(42.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(15.dp)),
            ) {
                Icon(
                    imageVector = if (darkMode) Icons.Outlined.WbSunny else Icons.Rounded.DarkMode,
                    contentDescription = if (darkMode) "切换到浅色模式" else "切换到深色模式",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun AlanBottomBar(currentRoute: String?, onNavigate: (String) -> Unit) {
    NavigationBar(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
    ) {
        bottomDestinations.forEach { destination ->
            val selected = currentRoute == destination.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(destination.route) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) { content() }
}

@Composable
fun PageHeading(eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            eyebrow.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 2.sp,
        )
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HeroColors(content: @Composable (background: Color, accent: Color) -> Unit) {
    content(AlanHero, AlanAmber)
}
