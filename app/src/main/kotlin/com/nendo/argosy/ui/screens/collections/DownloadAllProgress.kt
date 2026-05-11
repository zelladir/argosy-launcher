package com.nendo.argosy.ui.screens.collections

data class DownloadAllProgress(
    val isActive: Boolean = false,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val isOnCooldown: Boolean = false
)
