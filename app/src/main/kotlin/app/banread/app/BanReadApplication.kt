package app.banread.app

import android.app.Application
import app.banread.app.di.BanReadAppContainer
import app.banread.sync.SyncCoordinatorFactory

class BanReadApplication : Application() {
    val container: BanReadAppContainer by lazy { BanReadAppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        SyncCoordinatorFactory.registerBackgroundSyncProvider { ctx ->
            (ctx.applicationContext as? BanReadApplication)?.container?.syncCoordinator
        }
    }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }
}
