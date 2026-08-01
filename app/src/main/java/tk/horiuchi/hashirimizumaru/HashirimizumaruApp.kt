package tk.horiuchi.hashirimizumaru

import android.app.Application
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class HashirimizumaruApp : Application() {
    val database by lazy { AppDatabase.create(this) }

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        val dispatcher = Dispatcher().apply {
            maxRequestsPerHost = 20
        }
        val mapClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request()
                        .newBuilder()
                        .header("User-Agent", userAgent)
                        .build()
                )
            }
            .build()
        HttpRequestUtil.setOkHttpClient(mapClient)
    }

    companion object {
        val userAgent: String
            get() = "Hashirimizumaru/${BuildConfig.VERSION_NAME} " +
                "(${BuildConfig.APPLICATION_ID}; +https://github.com/digihori/Hashirimizumaru)"
    }
}
