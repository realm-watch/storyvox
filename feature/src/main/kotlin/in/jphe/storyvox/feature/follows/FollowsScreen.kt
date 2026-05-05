package `in`.jphe.storyvox.feature.follows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FollowsScreen(
    onOpenFiction: (String) -> Unit,
    viewModel: FollowsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is FollowsUiEvent.OpenFiction) onOpenFiction(event.fictionId)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Follows", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    BrassButton(
                        label = "Mark all caught up",
                        onClick = viewModel::markAllCaughtUp,
                        variant = BrassButtonVariant.Text,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            LazyColumn(
                contentPadding = PaddingValues(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                items(state.follows) { follow ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.open(follow.fiction.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            FictionCoverThumb(
                                coverUrl = follow.fiction.coverUrl,
                                title = follow.fiction.title,
                                authorInitial = follow.fiction.author.firstOrNull()?.uppercaseChar() ?: '?',
                                modifier = Modifier.size(width = 56.dp, height = 84.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(follow.fiction.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                Text(follow.fiction.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (follow.unreadCount > 0) {
                                Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                    Text(follow.unreadCount.toString())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
