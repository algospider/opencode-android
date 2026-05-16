package com.opencode.android

import android.app.Application

class OpenCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: OpenCodeApp
            private set
    }
}
