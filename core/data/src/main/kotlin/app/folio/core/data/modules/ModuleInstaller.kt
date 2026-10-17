package app.folio.core.data.modules

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import app.folio.core.opds.HttpsRequest
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Where an install of one package stands. */
sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val fraction: Float?) : InstallState
    /** Handed to the system installer; it may ask the user to confirm. */
    data object Installing : InstallState
    /** Folio may not install apps yet: the user was sent to the «install unknown apps» setting. */
    data object NeedsPermission : InstallState
    data class Failed(val message: String) : InstallState
}

/**
 * Installs and updates the speech engines listed in the modules repository and Folio itself: downloads the
 * APK over HTTPS, checks its SHA-256 against the index, its package name and that it is signed with Folio's
 * own certificate, then commits it to [PackageInstaller] (Android asks the user to confirm).
 */
@Singleton
class ModuleInstaller @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pm = context.packageManager
    private val prefs = context.getSharedPreferences("modules", Context.MODE_PRIVATE)
    private val dir = File(context.cacheDir, "modules")
    private val jobs = mutableMapOf<String, Job>()

    private val _modules = MutableStateFlow(ModuleIndexCodec.modules(prefs.getString(KEY_INDEX, null).orEmpty()).orEmpty())
    /** Modules published in the modules repository (the last list fetched). */
    val modules: StateFlow<List<ModuleApk>> = _modules.asStateFlow()
    /** Speech engines among [modules]. */
    val engines: StateFlow<List<ModuleApk>> = kind(ModuleKind.Engine)
    /** Book viewers among [modules] (Folio Документы). */
    val viewers: StateFlow<List<ModuleApk>> = kind(ModuleKind.Viewer)

    private val _appUpdate = MutableStateFlow<ModuleApk?>(null)
    /** A newer Folio release, if there is one. */
    val appUpdate: StateFlow<ModuleApk?> = _appUpdate.asStateFlow()

    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    /** Package name → install state; absent = idle. */
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()

    private val _installed = MutableStateFlow(installedVersions())
    /** Package name → installed versionCode of the listed modules. */
    val installed: StateFlow<Map<String, Long>> = _installed.asStateFlow()

    init {
        // Engines installed or removed outside Folio (or by the system installer) show up at once.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refreshInstalled()
        }, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    /** Fetches the module index and Folio's latest release; at most every [CHECK_INTERVAL_MS] unless [force]. */
    suspend fun refresh(force: Boolean = false) {
        refreshInstalled()
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_CHECKED, 0) < CHECK_INTERVAL_MS && _modules.value.isNotEmpty()) {
            checkApp()
            return
        }
        val text = fetch(ModulesRepository.MODULES_JSON)
        ModuleIndexCodec.modules(text)?.let { list ->
            _modules.value = list
            prefs.edit().putString(KEY_INDEX, text).putLong(KEY_CHECKED, now).apply()
        }
        checkApp()
        refreshInstalled()
    }

    /** Re-reads installed versions (after returning from the system installer or settings). */
    fun refreshInstalled() {
        _installed.value = installedVersions()
        // Back from the «install unknown apps» setting: the user can tap install again.
        if (pm.canRequestPackageInstalls()) {
            _states.update { states -> states.mapValues { (_, s) -> if (s == InstallState.NeedsPermission) InstallState.Idle else s } }
        }
    }

    fun installedVersion(packageName: String): Long? = try {
        pm.getPackageInfo(packageName, 0).longVersionCode
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** Downloads, checks and installs [apk]; a second call while one runs is ignored. */
    fun install(apk: ModuleApk) {
        if (jobs[apk.packageName]?.isActive == true) return
        if (!pm.canRequestPackageInstalls()) {
            set(apk.packageName, InstallState.NeedsPermission)
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        set(apk.packageName, InstallState.Downloading(0f))
        jobs[apk.packageName] = scope.launch {
            val file = File(dir, "${apk.id}.apk")
            try {
                download(apk, file)
                verify(apk, file)
                set(apk.packageName, InstallState.Installing)
                commit(apk, file)
            } catch (e: CancellationException) {
                file.delete()
                set(apk.packageName, InstallState.Idle)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Install of ${apk.packageName} failed", e)
                file.delete()
                set(apk.packageName, InstallState.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun cancel(packageName: String) {
        jobs.remove(packageName)?.cancel()
    }

    /** Opens Android's uninstall dialog for [packageName]. */
    fun uninstall(packageName: String) {
        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The system installer's answer for a session committed by [commit]. */
    internal fun onResult(intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                File(dir, "${intent.getStringExtra(EXTRA_ID)}.apk").delete()
                set(packageName, InstallState.Idle)
                refreshInstalled()
                if (packageName == context.packageName) _appUpdate.value = null
            }
            else -> {
                File(dir, "${intent.getStringExtra(EXTRA_ID)}.apk").delete()
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Installer status $status for $packageName: $message")
                // Cancelled by the user: back to idle, nothing to report.
                set(packageName, if (status == PackageInstaller.STATUS_FAILURE_ABORTED) InstallState.Idle
                    else InstallState.Failed(message ?: "status $status"))
            }
        }
    }

    /** Reads Folio's latest GitHub release into [appUpdate]; false when the release could not be fetched. */
    suspend fun checkApp(): Boolean {
        val latest = runCatching {
            val metadata = fetch(ModulesRepository.APP_METADATA)
            val sums = fetch(ModulesRepository.APP_RELEASES + "SHA256SUMS.txt")
            ModuleIndexCodec.app(metadata, sums, context.packageName, ModulesRepository.APP_RELEASES)
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "App update check failed", it)
            return false
        }.getOrNull()
        val current = installedVersion(context.packageName) ?: 0
        _appUpdate.value = latest?.takeIf { it.versionCode > current }
        return true
    }

    private suspend fun fetch(url: String): String = runInterruptible(Dispatchers.IO) {
        HttpsRequest().read(url) { input, _, _ -> String(bounded(input, MAX_TEXT_BYTES), Charsets.UTF_8) }
    }

    private suspend fun download(apk: ModuleApk, file: File) = runInterruptible {
        dir.mkdirs()
        HttpsRequest(readTimeoutMs = 60_000).open(apk.url) { response ->
            val total = response.contentLength.takeIf { it > 0 } ?: apk.bytes
            if (total > MAX_APK_BYTES) throw IOException("APK too big: $total")
            var written = 0L
            var published = 0L
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val n = response.stream.read(buffer)
                    if (n < 0) break
                    written += n
                    if (written > MAX_APK_BYTES) throw IOException("APK too big")
                    out.write(buffer, 0, n)
                    val now = System.nanoTime()
                    if (now - published > 200_000_000L && total > 0) {
                        published = now
                        set(apk.packageName, InstallState.Downloading((written.toFloat() / total).coerceIn(0f, 1f)))
                    }
                }
            }
        }
    }

    /** SHA-256 from the index, the expected package, and Folio's own signing certificate. */
    private fun verify(apk: ModuleApk, file: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(apk.sha256, ignoreCase = true)) throw IOException("Контрольная сумма не совпала")
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IOException("Файл не является APK")
        if (archive.packageName != apk.packageName) throw IOException("Неожиданный пакет ${archive.packageName}")
        val own = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners.orEmpty().toSet()
        val theirs = archive.signingInfo?.apkContentsSigners.orEmpty().toSet()
        if (own.isEmpty() || theirs != own) throw IOException("APK подписан чужим ключом")
    }

    private fun commit(apk: ModuleApk, file: File) {
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(apk.packageName)
            setSize(file.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(context, InstallResultReceiver::class.java)
                .putExtra(EXTRA_PACKAGE, apk.packageName)
                .putExtra(EXTRA_ID, apk.id)
            // Mutable: the installer adds its status extras.
            val pending = PendingIntent.getBroadcast(context, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    private fun installedVersions(): Map<String, Long> =
        (_modules.value.map { it.packageName } + KNOWN_ENGINES).distinct()
            .mapNotNull { pkg -> installedVersion(pkg)?.let { pkg to it } }.toMap()

    private fun kind(kind: ModuleKind): StateFlow<List<ModuleApk>> =
        _modules.map { list -> list.filter { it.kind == kind } }
            .stateIn(scope, SharingStarted.Eagerly, _modules.value.filter { it.kind == kind })

    private fun set(packageName: String, state: InstallState) = _states.update { it + (packageName to state) }

    private fun bounded(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (output.size().toLong() + n > limit) throw IOException("Response too big")
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    private companion object {
        const val TAG = "ModuleInstaller"
        // "_v2": refetch once, an index cached by 0.9 may predate the `viewers` array.
        const val KEY_INDEX = "index_v2"
        const val KEY_CHECKED = "checked_v2"
        const val EXTRA_PACKAGE = "app.folio.extra.PACKAGE"
        const val EXTRA_ID = "app.folio.extra.MODULE_ID"
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val MAX_TEXT_BYTES = 256L * 1024
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        /** Engines Folio knows even before the index is fetched. */
        val KNOWN_ENGINES = listOf("app.folio.tts.piper", "app.folio.tts.silero")
    }
}

/** Receives [PackageInstaller] results for [ModuleInstaller]'s sessions (only this app can send to it). */
class InstallResultReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Installer {
        fun installer(): ModuleInstaller
    }

    override fun onReceive(context: Context, intent: Intent) {
        EntryPointAccessors.fromApplication(context, Installer::class.java).installer().onResult(intent)
    }
}
