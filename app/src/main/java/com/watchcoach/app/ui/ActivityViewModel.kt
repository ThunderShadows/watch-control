package com.watchcoach.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * One ViewModel for the whole activity, so Dashboard, Settings, and detail
 * screens share sync progress and saved settings instead of each destination
 * keeping a private copy.
 */
@Composable
inline fun <reified VM : ViewModel> activityViewModel(): VM {
    val activity = LocalActivity.current as? ComponentActivity
        ?: error("Watch Coach screens run inside MainActivity")
    return viewModel(viewModelStoreOwner = activity)
}
