package com.alanbeidanci.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alanbeidanci.app.ui.LetterInput

enum class LetterCellType { LETTER, PUNCT, GAP }

fun letterCells(target: String): List<Pair<Char, LetterCellType>> =
    target.map { char ->
        when {
            char == ' ' -> char to LetterCellType.GAP
            char.isLetterOrDigit() -> char to LetterCellType.LETTER
            else -> char to LetterCellType.PUNCT
        }
    }

fun letterCharacters(target: String): String = target.filter { it.isLetterOrDigit() }

@Composable
fun LetterBoxInput(
    target: String,
    value: String,
    enabled: Boolean,
    autoFocus: Any?,
    modifier: Modifier = Modifier,
    markCorrectness: Boolean = true,
    onInput: (String) -> Unit,
    onEnter: (() -> Unit)? = null,
) {
    val cells = remember(target) { letterCells(target) }
    val letterCount = remember(target) { letterCharacters(target).length.coerceAtLeast(1) }
    val focusRequester = remember { FocusRequester() }
    val bringIntoView = remember(target) { BringIntoViewRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus, enabled) {
        if (!enabled) {
            keyboard?.hide()
            return@LaunchedEffect
        }
        focusRequester.requestFocus()
        keyboard?.show()
    }
    val caretIndex = value.length.coerceAtMost(letterCount - 1)
    LaunchedEffect(caretIndex, target) {
        bringIntoView.bringIntoView()
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val gap = 6.dp
        // 格子尽量缩小，让整词留在屏幕里。再长的词仍会横滑，所以上面另有一行完整已输入字母。
        val minCell = 20.dp
        val maxCell = 46.dp
        val punctCount = target.count { !it.isLetterOrDigit() && it != ' ' }
        val spaceCount = target.count { it == ' ' }
        val reserved = 8.dp + 14.dp * punctCount + gap * spaceCount
        val availablePx = with(density) { (maxWidth - reserved).toPx() }
        val gapPx = with(density) { gap.toPx() }
        val minCellPx = with(density) { minCell.toPx() }
        val maxCellPx = with(density) { maxCell.toPx() }
        val overflows = LetterInput.rowOverflows(availablePx, letterCount, gapPx, minCellPx, maxCellPx)
        val cellWidth = with(density) {
            LetterInput.cellWidth(availablePx, letterCount, gapPx, minCellPx, maxCellPx).toDp()
        }
        Column(Modifier.fillMaxWidth()) {
        Text(
            text = value.ifEmpty { " " },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            color = if (value.isEmpty()) Color.Transparent else MaterialTheme.colorScheme.onSurface,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = if (overflows) Alignment.CenterStart else Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var letterIndex = 0
                for ((char, type) in cells) {
                    when (type) {
                        LetterCellType.LETTER -> {
                            val index = letterIndex
                            val typed = value.getOrNull(index)
                            val matches = typed != null && typed.equals(char, ignoreCase = true)
                            val active = enabled && index == value.length
                            Box(
                                modifier = Modifier
                                    .size(width = cellWidth, height = 52.dp)
                                    .then(if (index == caretIndex) Modifier.bringIntoViewRequester(bringIntoView) else Modifier)
                                    .background(
                                        when {
                                            !enabled || typed == null -> MaterialTheme.colorScheme.surfaceVariant
                                            !markCorrectness -> MaterialTheme.colorScheme.primaryContainer
                                            matches -> MaterialTheme.colorScheme.primaryContainer
                                            else -> MaterialTheme.colorScheme.errorContainer
                                        },
                                        RoundedCornerShape(12.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    typed?.toString() ?: "",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        typed == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                        !markCorrectness -> MaterialTheme.colorScheme.onPrimaryContainer
                                        matches -> MaterialTheme.colorScheme.onPrimaryContainer
                                        else -> MaterialTheme.colorScheme.onErrorContainer
                                    },
                                )
                                if (active) {
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 6.dp)
                                            .size(width = 16.dp, height = 2.dp)
                                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)),
                                    )
                                }
                            }
                            letterIndex += 1
                        }
                        LetterCellType.PUNCT -> {
                            Text(
                                char.toString(),
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        LetterCellType.GAP -> Spacer(Modifier.width(10.dp))
                    }
                }
            }
            BasicTextField(
                value = value,
                onValueChange = onInput,
                modifier = Modifier
                    .matchParentSize()
                    .onPreviewKeyEvent { event ->
                        if (enabled && event.key == Key.Enter) {
                            if (event.type == KeyEventType.KeyDown) onEnter?.invoke()
                            true
                        } else {
                            false
                        }
                    }
                    .alpha(0f)
                    .focusRequester(focusRequester),
                textStyle = TextStyle(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Transparent),
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    // 密码键盘不会弹出候选词，避免点一下就塞进整个词。输入框本身是透明的，看不到圆点。
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.None,
                ),
                decorationBox = { innerTextField ->
                    Box(Modifier.height(52.dp)) { innerTextField() }
                },
            )
        }
        }
    }
}
