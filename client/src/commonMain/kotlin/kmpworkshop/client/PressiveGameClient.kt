@file:Suppress("FunctionName")
@file:OptIn(ExperimentalFoundationApi::class)

package kmpworkshop.client

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PressiveGame() {
    var hint by remember { mutableStateOf("Connecting to host...") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        getFlowOfPressiveGameHints().collect { newHint ->
            hint = newHint
        }
    }
    Row(
        modifier = Modifier.combinedClickable(
            onClick = { /* doSinglePress() */ },
            onDoubleClick = { /* doDoublePress() */ },
            onLongClick = { /* doLongPress() */ }
        )
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Column {
            Spacer(modifier = Modifier.weight(1f))
            Text(hint)
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}