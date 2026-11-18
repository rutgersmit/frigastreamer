package io.github.rutgersmit.frigastreamer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.rutgersmit.frigastreamer.R
import io.github.rutgersmit.frigastreamer.data.RefreshStatus

/** Localized one-line summary of the last camera list refresh. */
@Composable
fun refreshSummary(refresh: RefreshStatus): String = when {
    refresh.notConfigured -> stringResource(R.string.refresh_not_configured)
    refresh.ok == null -> stringResource(R.string.refresh_not_fetched)
    refresh.ok == false -> stringResource(R.string.refresh_failed, refresh.errorDetail ?: "?")
    else -> buildString {
        append(pluralStringResource(R.plurals.refresh_streams, refresh.streamCount, refresh.streamCount))
        append(", ")
        append(pluralStringResource(R.plurals.refresh_cameras, refresh.cameraCount, refresh.cameraCount))
        if (refresh.configUnavailable) {
            append(" ")
            append(stringResource(R.string.refresh_config_unavailable))
        }
        if (refresh.unmatched.isNotEmpty()) {
            append(", ")
            append(pluralStringResource(R.plurals.refresh_unmatched, refresh.unmatched.size, refresh.unmatched.size))
        }
    }
}
