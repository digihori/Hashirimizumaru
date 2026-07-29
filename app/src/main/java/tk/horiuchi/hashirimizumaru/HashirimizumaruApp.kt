package tk.horiuchi.hashirimizumaru

import android.app.Application
import org.maplibre.android.MapLibre

class HashirimizumaruApp : Application() {
    val database by lazy { AppDatabase.create(this) }
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
