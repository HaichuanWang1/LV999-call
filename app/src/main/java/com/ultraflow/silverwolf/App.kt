package com.ultraflow.silverwolf

import android.app.Application
import com.ultraflow.silverwolf.di.AppModule

class App : Application() {

    lateinit var appModule: AppModule
        private set

    override fun onCreate() {
        super.onCreate()
        appModule = AppModule(this)
    }
}
