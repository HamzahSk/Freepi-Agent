package com.freepi.lk21

import android.app.Application
import com.freepi.lk21.data.MovieRepository
import com.freepi.lk21.data.local.AppDatabase
import com.freepi.lk21.data.remote.Lk21Client
import com.freepi.lk21.data.remote.Lk21Parser
import com.freepi.lk21.data.remote.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Manual dependency container (small app, no DI framework required). */
class AppContainer(application: Application) {

    private val cacheDir = File(application.cacheDir, "lk21").apply { mkdirs() }

    val client: Lk21Client by lazy { Lk21Client(cacheDir) }
    val parser: Lk21Parser by lazy { Lk21Parser() }
    val resolver: StreamResolver by lazy { StreamResolver(client) }
    val database: AppDatabase by lazy { AppDatabase.get(application) }
    val repository: MovieRepository by lazy {
        MovieRepository(client, parser, resolver, database)
    }

    /** Outlives individual screens so final watch-progress writes always land. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class Lk21App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
