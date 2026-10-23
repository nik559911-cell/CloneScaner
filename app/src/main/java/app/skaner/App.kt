package app.skaner

import android.app.Application
import android.util.Log
import androidx.room.Room
import app.skaner.data.AppDb
import app.skaner.data.FileStore
import app.skaner.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

class App : Application() {
    lateinit var repo: Repo
        private set

    /** Область для работы, которая должна пережить экран (например, обработка после «Готово»). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e("Skaner", "OpenCV не загрузился")
        val db = Room.databaseBuilder(this, AppDb::class.java, "skaner.db").build()
        repo = Repo(this, db, FileStore(this))
        appScope.launch { runCatching { repo.cleanup() } }
    }
}
