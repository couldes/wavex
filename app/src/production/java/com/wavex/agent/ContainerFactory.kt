package com.wavex.agent

import android.content.Context

/** Production variants retain the normal request service. */
internal fun createAppContainer(appContext: Context): AppContainer =
    AppContainer(appContext)
