package com.henrybeevers.birdy

import android.app.Application
import com.henrybeevers.birdy.data.PreferencesRepository
import com.henrybeevers.birdy.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BirdyApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: PreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepository(this)
        appScope.launch {
            val s = prefs.settingsFlow.first()
            if (s.firstRunDone) {
                RefreshWorker.enqueue(this@BirdyApp, s.refreshHours)
            }
        }
    }
}
