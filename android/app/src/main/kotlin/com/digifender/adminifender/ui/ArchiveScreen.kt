package com.digifender.adminifender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.ui.theme.Glyphs
import com.digifender.adminifender.ui.theme.ScreenHeader
import com.digifender.adminifender.core.data.network.ArchiveView
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.repository.CachedReason
import com.digifender.adminifender.core.data.repository.InvalidQuery
import com.digifender.adminifender.core.data.repository.SearchHit

/** Owner-editable search fields, kept in the view model so they survive rotation. */
class SearchFields(val query: String, val sender: String, val range: DateRange)

@Composable
fun ArchiveScreen(
    state: ArchiveUiState,
    fields: SearchFields,
    onQuery: (String) -> Unit,
    onSender: (String) -> Unit,
    onRange: (DateRange) -> Unit,
    onView: (ArchiveView) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onMoreResults: () -> Unit,
    onMoreList: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
    ScreenHeader(stringResource(R.string.nav_archive))
    LazyColumn(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArchiveView.entries.forEach { v ->
                    FilterChip(selected = state.view == v, onClick = { onView(v) }, label = { Text(stringResource(if (v == ArchiveView.ARCHIVE) R.string.view_archive else R.string.view_quarantine)) })
                }
            }
            if (state.view == ArchiveView.QUARANTINE) Text(stringResource(R.string.quarantine_note), style = MaterialTheme.typography.bodySmall)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = fields.query,
                    onValueChange = onQuery,
                    label = { Text(stringResource(R.string.search_label)) },
                    leadingIcon = { Icon(Glyphs.search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = pillColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = fields.sender,
                    onValueChange = onSender,
                    label = { Text(stringResource(R.string.search_sender_label)) },
                    leadingIcon = { Icon(Glyphs.mail, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = pillColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateRange.entries.forEach { r -> FilterChip(selected = fields.range == r, onClick = { onRange(r) }, label = { Text(rangeText(r)) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSubmit) { Text(stringResource(R.string.search_button)) }
                    if (state.search != null) TextButton(onClick = onClear) { Text(stringResource(R.string.search_clear)) }
                }
            }
        }
        val search = state.search
        if (search != null) {
            item { SearchStatus(search) }
            // No item keys: one message can match in several chunks with the same snippet.
            items(search.hits) { HitCard(it, onOpen) }
            if (search.nextCursor != null && !search.running) item { TextButton(onClick = onMoreResults) { Text(stringResource(R.string.search_more)) } }
            if (search.moreFailed) item { Text(stringResource(R.string.search_more_failed), color = MaterialTheme.colorScheme.error) }
        } else {
            val list = state.list
            when {
                list == null -> item { Text(stringResource(R.string.archive_loading), Modifier.padding(vertical = 24.dp)) }
                list.isEmpty() -> item { Text(stringResource(R.string.archive_empty), Modifier.padding(vertical = 24.dp)) }
                else -> {
                    item { Text(pluralStringResource(R.plurals.archive_count, state.total, state.total), style = MaterialTheme.typography.labelLarge) }
                    items(list, key = { it.id }) { SourceCard(it, onOpen) }
                    if (list.size < state.total) item { TextButton(onClick = onMoreList) { Text(stringResource(R.string.archive_more)) } }
                }
            }
        }
    }
    }
}

/** Search fields are filled pills, as on the canvas, with no underline. */
@Composable
fun pillColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)

@Composable
private fun SearchStatus(search: SearchUiState) {
    val invalid = search.invalid
    // Coverage is always spelled out, so cached-only results are never mistaken for the whole archive (FR-013).
    val (text, error) = when {
        search.running && search.hits.isEmpty() -> stringResource(R.string.search_running) to false
        invalid != null -> stringResource(
            when (invalid) {
                InvalidQuery.EMPTY -> R.string.search_empty
                InvalidQuery.TOO_LONG -> R.string.search_too_long
                InvalidQuery.TOO_MANY_TERMS -> R.string.search_too_many
                InvalidQuery.REFUSED -> R.string.search_refused
            },
        ) to true
        else -> when (val c = search.coverage) {
            Coverage.Complete -> stringResource(R.string.search_complete) to false
            is Coverage.Partial -> pluralStringResource(R.plurals.search_partial, c.partitionsRemaining, c.partitionsRemaining) to false
            is Coverage.CachedOnly -> stringResource(
                when (c.reason) {
                    CachedReason.OFFLINE -> R.string.search_cached_offline
                    CachedReason.SIGN_IN -> R.string.search_cached_sign_in
                    CachedReason.UNAVAILABLE -> R.string.search_cached_unavailable
                },
            ) to true
            null -> "" to false
        }
    }
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    if (!search.running && invalid == null && search.coverage != null && search.hits.isEmpty()) Text(stringResource(R.string.search_none))
}

@Composable
private fun rangeText(r: DateRange) = stringResource(
    when (r) {
        DateRange.ANY -> R.string.range_any
        DateRange.WEEK -> R.string.range_week
        DateRange.MONTH -> R.string.range_month
        DateRange.YEAR -> R.string.range_year
    },
)

/** Server and cached snippets mark matches with brackets; they are shown with [mark] rather than as brackets. */
fun highlighted(snippet: String, mark: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold)): AnnotatedString = buildAnnotatedString {
    var rest = snippet
    while (true) {
        val open = rest.indexOf('[')
        val close = if (open >= 0) rest.indexOf(']', open + 1) else -1
        if (open < 0 || close < 0) {
            append(rest)
            break
        }
        append(rest.substring(0, open))
        withStyle(mark) { append(rest.substring(open + 1, close)) }
        rest = rest.substring(close + 1)
    }
}

@Composable
private fun HitCard(hit: SearchHit, onOpen: (String) -> Unit) {
    val mark = SpanStyle(background = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
    ListRow({ onOpen(hit.sourceId) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(hit.subject?.ifBlank { null } ?: stringResource(R.string.no_subject), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(dateTime(hit.receivedAtMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        hit.snippet?.let { Text(highlighted(it, mark), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (hit.inAttachment) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.paperclip, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(hit.page?.let { stringResource(R.string.hit_in_attachment_page, it) } ?: stringResource(R.string.hit_in_attachment), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun sourceLabels(source: SourceSummary): String = buildList {
    add(dateTime(source.receivedAtMs))
    if (source.quarantined) add(stringResource(R.string.label_quarantined))
    when (source.state) {
        "staged", "pending_index" -> add(stringResource(R.string.state_pending))
        "parse_failed" -> add(stringResource(R.string.state_failed))
    }
    if (source.privacy != "hybrid") add(stringResource(R.string.label_local_only))
}.joinToString(" · ")

@Composable
fun SourceCard(source: SourceSummary, onOpen: (String) -> Unit) {
    ListRow({ onOpen(source.id) }) {
        if (source.from.isNotEmpty()) Text(source.from.joinToString(", "), style = MaterialTheme.typography.labelLarge)
        Text(source.subject?.ifBlank { null } ?: stringResource(R.string.no_subject), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(sourceLabels(source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A full-width list row with a hairline under it, as in the canvas's archive list. */
@Composable
private fun ListRow(onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
