package com.lifeguard.app.network

import com.lifeguard.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    private var currentBaseUrl: String = BuildConfig.BASE_URL
    private var _apiService: ApiService = createService(currentBaseUrl)

    val apiService: ApiService
        get() = _apiService

    fun setBaseUrl(newUrl: String) {
        val sanitized = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
        if (sanitized != currentBaseUrl) {
            currentBaseUrl = sanitized
            _apiService = createService(currentBaseUrl)
        }
    }

    private fun createService(baseUrl: String): ApiService {
        val finalUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(finalUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
