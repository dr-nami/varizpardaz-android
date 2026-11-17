package com.varizpardaz.listener.app

import android.app.Application
import com.varizpardaz.listener.app.data.AppContainer

class ListenerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
