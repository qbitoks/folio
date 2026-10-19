package app.folio.core.data.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.folio.core.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Imports new books from the user-chosen folder (Storage Access Framework tree URI). */
@Singleton
class FolderScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) {
    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        val tree = settings.library.first().watchedFolder ?: return@withContext 0
        val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return@withContext 0
        if (!root.canRead()) return@withContext 0
        val known = library.knownSourceUris()
        var added = 0
        for (file in collect(root, depth = 0)) {
            val uri = file.uri.toString()
            if (uri in known) continue
            runCatching { library.import(file.uri, sourceUri = uri) }
                .onSuccess { if (it is ImportResult.Added) added++ }
        }
        added
    }

    private fun collect(dir: DocumentFile, depth: Int): List<DocumentFile> {
        if (depth > MAX_DEPTH) return emptyList()
        val result = ArrayList<DocumentFile>()
        for (f in dir.listFiles()) {
            val name = f.name?.lowercase() ?: continue
            when {
                f.isDirectory && !name.startsWith(".") -> result += collect(f, depth + 1)
                f.isFile && extensions.any { name.endsWith(it) } -> result += f
            }
        }
        return result
    }

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork("folder-scan-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<FolderScanWorker>().build())
        wm.enqueueUniquePeriodicWork(
            "folder-scan",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FolderScanWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }

    private companion object {
        const val MAX_DEPTH = 4
        val extensions = listOf(
            ".epub", ".fb2", ".fb2.zip", ".mobi", ".azw", ".azw3", ".prc", ".rtf", ".docx", ".pdf", ".djvu", ".djv", ".htm", ".html", ".xhtml",
            ".txt", ".text", ".md", ".markdown", ".rst", ".log", ".csv", ".tsv",
            ".conf", ".cfg", ".ini", ".properties", ".toml", ".yaml", ".yml", ".json", ".xml",
        )
    }
}

@HiltWorker
class FolderScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanner: FolderScanner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching { scanner.scan() }.fold({ Result.success() }, { Result.retry() })
}
