package com.jarvis.app.ui.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.R
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.components.ActionLogWidgetCard
import com.jarvis.app.ui.components.AgendaWidgetCard
import com.jarvis.app.ui.components.RoutinesWidgetCard
import com.jarvis.app.ui.theme.LocalJarvisColors

@Composable
fun RoutinesSheet(
    viewModel: JarvisViewModel,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val agenda by viewModel.todayAgenda.collectAsStateWithLifecycle()
    val actionLogs by viewModel.actionLogs.collectAsStateWithLifecycle()

    var routinesCollapsed by remember { mutableStateOf(false) }
    var agendaCollapsed by remember { mutableStateOf(false) }
    var logsCollapsed by remember { mutableStateOf(true) }

    BackHandler(onBack = onClose)

    Surface(color = hud.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Routines & Agenda",
                            style = MaterialTheme.typography.titleLarge,
                            color = hud.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("routines_close_button")
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cd_close_sheet),
                                tint = hud.textPrimary
                            )
                        }
                    }
                }

                item {
                    RoutinesWidgetCard(
                        routines = routines,
                        collapsed = routinesCollapsed,
                        density = "comfortable",
                        onToggleCollapse = { routinesCollapsed = !routinesCollapsed },
                        onSaveRoutine = { viewModel.saveRoutine(it) },
                        onRunRoutine = { viewModel.runRoutine(it) },
                        onDeleteRoutine = { viewModel.deleteRoutine(it) }
                    )
                }

                item {
                    AgendaWidgetCard(
                        agenda = agenda,
                        collapsed = agendaCollapsed,
                        density = "comfortable",
                        onToggleCollapse = { agendaCollapsed = !agendaCollapsed },
                        onAddAgenda = { time, task -> viewModel.addAgendaItem(time, task) },
                        onToggleItem = { viewModel.toggleAgendaItem(it) },
                        onDeleteItem = { viewModel.deleteAgendaItem(it) }
                    )
                }

                item {
                    ActionLogWidgetCard(
                        logs = actionLogs,
                        collapsed = logsCollapsed,
                        density = "comfortable",
                        onToggleCollapse = { logsCollapsed = !logsCollapsed }
                    )
                }
            }
        }
    }
}
