package com.lmstudio.client

import android.app.Application
import com.google.gson.Gson
import com.lmstudio.client.data.preferences.AppPreferences
import com.lmstudio.client.data.repository.ChatRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LMStudioApp : Application() {

    val gson: Gson by lazy { Gson() }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val preferences: AppPreferences by lazy {
        AppPreferences(applicationContext)
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(okHttpClient, gson)
    }
}
