package com.sebasgl1.streamiocast

import android.app.Application
import com.sebasgl1.streamiocast.data.AddonRepository

class StremioApp : Application() {
    lateinit var repo: AddonRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = AddonRepository(this)
    }
}
