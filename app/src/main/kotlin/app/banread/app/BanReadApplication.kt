package app.banread.app

import android.app.Application
import app.banread.app.di.BanReadAppContainer

class BanReadApplication : Application() {
    val container: BanReadAppContainer by lazy { BanReadAppContainer(this) }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }
}
