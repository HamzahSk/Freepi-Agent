package com.freepi.lk21.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.freepi.lk21.AppContainer
import com.freepi.lk21.Lk21App

@Composable
fun rememberContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as Lk21App).container }
}

inline fun <VM : ViewModel> viewModelFactory(crossinline create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
