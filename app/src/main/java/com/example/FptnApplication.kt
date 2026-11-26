package com.example

import android.app.Application
import com.example.core.logging.AppLogStore
import com.example.data.local.FptnDatabase
import com.example.data.local.SettingsDataStore
import com.example.data.remote.WeatherRepository
import com.example.data.repository.FptnRepository
import com.example.vpn.root.RootShellManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FptnAppContainer(application: Application) {
    val database: FptnDatabase = FptnDatabase.getInstance(application)
    val settingsDataStore: SettingsDataStore = SettingsDataStore(application)
    val weatherRepository: WeatherRepository = WeatherRepository(application)
    val repository: FptnRepository = FptnRepository(
        context = application,
        tokenDao = database.tokenDao(),
        settingsDataStore = settingsDataStore,
    )
}

class FptnApplication : Application() {

    lateinit var container: FptnAppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = FptnAppContainer(this)
        RootShellManager.configureShellFactory()
        AppLogStore.info("App", "FPTN Client запущен")
        appScope.launch {
            RootShellManager.sweepOrphanRulesOnStartup()
        }
    }
}
