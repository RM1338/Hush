package com.hush

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// D-33 / D-34: light + one deep blue accent; dark mode follows the phone; amber = waiting; red only for Emergency.
val Amber = Color(0xFFB26A00)
val Emergency = Color(0xFFC62828)

private val Atkinson = FontFamily(Font(R.font.atkinson_regular), Font(R.font.atkinson_bold, FontWeight.Bold))

private val Light = lightColorScheme(
    primary = Color(0xFF1F6FEB), onPrimary = Color.White,
    background = Color(0xFFF7F8FA), surface = Color.White, surfaceVariant = Color(0xFFE9EDF3),
    onBackground = Color(0xFF14181F), onSurface = Color(0xFF14181F), onSurfaceVariant = Color(0xFF4A5363),
    error = Emergency,
)
private val Dark = darkColorScheme(
    primary = Color(0xFF4C8DFF), onPrimary = Color(0xFF07121F),
    background = Color(0xFF0F1216), surface = Color(0xFF171B21), surfaceVariant = Color(0xFF232A33),
    onBackground = Color(0xFFE8ECF2), onSurface = Color(0xFFE8ECF2), onSurfaceVariant = Color(0xFFA9B3C2),
    error = Color(0xFFEF5350),
)

@Composable
fun HushTheme(content: @Composable () -> Unit) {
    val t = Typography()
    fun TextStyle.a() = copy(fontFamily = Atkinson)
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography(
            displaySmall = t.displaySmall.a().copy(fontWeight = FontWeight.Bold),
            headlineMedium = t.headlineMedium.a().copy(fontWeight = FontWeight.Bold),
            titleLarge = t.titleLarge.a().copy(fontWeight = FontWeight.Bold),
            titleMedium = t.titleMedium.a(),
            bodyLarge = t.bodyLarge.a().copy(fontSize = 18.sp),
            bodyMedium = t.bodyMedium.a(),
            labelLarge = t.labelLarge.a().copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
            labelMedium = t.labelMedium.a(),
        ),
        content = content,
    )
}
