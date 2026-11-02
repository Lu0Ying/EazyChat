package com.eazychat.app

import android.app.Application
import com.eazychat.app.ui.theme.ThemePreferenceManager

/**
 * Main application class for p2p-chat Android
 */
class P2PChatApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()

        // Initialize favorites persistence early
        try {
            com.eazychat.app.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)


    }
}