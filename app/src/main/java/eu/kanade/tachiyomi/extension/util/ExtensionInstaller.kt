package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The installer which installs, updates and uninstalls the extensions.
 *
 * @param context The application context.
 */
@Inject
class ExtensionInstaller(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    basePreferences: BasePreferences,
    networkHelper: NetworkHelper,
) {

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeSteps = ConcurrentHashMap<Long, MutableStateFlow<InstallStep>>()
    private val extensionInstaller = basePreferences.extensionInstaller

    private val httpClient: OkHttpClient by lazy { networkHelper.client }

    /**
     * Adds the given extension to the downloads queue and returns an observable containing its
     * step in the installation process.
     *
     * @param extension The listing to install, whose apk must be signed with its store's key.
     * @param isUpdateForPrivatelyInstalled If this is an update for a privately installed extension
     */
    fun downloadAndInstall(
        extension: Extension.Available,
        isUpdateForPrivatelyInstalled: Boolean = false,
    ): Flow<InstallStep> {
        val downloadId = extension.pkgName.hashCode().toLong()
        cancelInstall(extension.pkgName)

        val step = MutableStateFlow<InstallStep>(InstallStep.Pending)
        activeSteps[downloadId] = step

        val job = scope.launch {
            val tmpFile = File(context.cacheDir, "extension_${extension.pkgName}.apk")
            try {
                step.value = InstallStep.Downloading
                downloadApkWithRetry(extension.apkUrl, tmpFile)

                // The store's index names the apk, but nothing ties what's served there to the store's key
                val packageInfo = ExtensionLoader.getArchivePackageInfo(context, tmpFile)
                    ?.takeIf { extension.store.signingKey in ExtensionLoader.getSignatures(it).orEmpty() }
                if (packageInfo == null) {
                    tmpFile.delete()
                    throw SignatureMismatchException(extension.store.name)
                }

                ensureActive()
                step.value = InstallStep.Installing
                installApk(downloadId, tmpFile, packageInfo, isUpdateForPrivatelyInstalled)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.INFO, e)
                tmpFile.delete()
                step.value = InstallStep.Error(e.toInstallErrorReason())
            }
        }

        activeJobs[extension.pkgName] = job

        return step.asStateFlow()
            .onCompletion {
                activeJobs.remove(extension.pkgName)
                activeSteps.remove(downloadId)
                job.cancel()
            }
    }

    /**
     * Starts an intent to install the extension at the given uri.
     *
     * @param tempFile The file of the extension to install. Delete after use.
     * @param packageInfo The package info already read from [tempFile].
     * @param isUpdateForPrivatelyInstalled If this install is an update for a privately installed extension
     */
    private fun installApk(
        downloadId: Long,
        tempFile: File,
        packageInfo: PackageInfo,
        isUpdateForPrivatelyInstalled: Boolean = false,
    ) {
        if (isUpdateForPrivatelyInstalled) {
            installApkPrivately(downloadId, tempFile, packageInfo)
            return
        }

        when (val installer = extensionInstaller.get()) {
            BasePreferences.ExtensionInstaller.LEGACY -> {
                val intent = Intent(context, ExtensionInstallActivity::class.java)
                    .setDataAndType(tempFile.getUriCompat(context), APK_MIME)
                    .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

                context.startActivity(intent)
            }

            BasePreferences.ExtensionInstaller.PRIVATE -> {
                installApkPrivately(downloadId, tempFile, packageInfo)
            }

            else -> {
                val intent = ExtensionInstallService.getIntent(
                    context,
                    downloadId,
                    tempFile.getUriCompat(context),
                    installer,
                )
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    private fun installApkPrivately(downloadId: Long, tempFile: File, packageInfo: PackageInfo) {
        try {
            ExtensionLoader.installPrivateExtensionFile(context, tempFile, packageInfo)
            updateInstallStep(downloadId, InstallStep.Installed)
        } catch (e: Exception) {
            logcat(LogPriority.INFO, e) { "Failed to install extension privately" }
            updateInstallStep(downloadId, InstallStep.Error(e.toInstallErrorReason()))
        }

        tempFile.delete()
    }

    /**
     * Downloads the extension apk, retrying transient network failures with a short backoff.
     * Permanent failures like 4xx responses and signature problems are not retried.
     */
    private suspend fun downloadApkWithRetry(url: String, tmpFile: File) {
        var attempt = 0
        while (true) {
            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).await().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX) {
                            throw RetryableDownloadException("HTTP ${response.code}")
                        }
                        throw DownloadFailedException("HTTP ${response.code}")
                    }
                    // Interruptible so cancelInstall stops the body read too
                    runInterruptible {
                        response.body.byteStream().use { input ->
                            tmpFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
                return
            } catch (e: IOException) {
                attempt++
                if (attempt >= MAX_DOWNLOAD_ATTEMPTS) {
                    throw if (e is RetryableDownloadException) {
                        DownloadFailedException(e.message ?: "network error")
                    } else {
                        e
                    }
                }
                delay(RETRY_BACKOFF_MILLIS * (1L shl (attempt - 1)))
            }
        }
    }

    /**
     * Cancels extension install and remove from download manager and installer.
     */
    fun cancelInstall(pkgName: String) {
        activeJobs.remove(pkgName)?.cancel()
        Installer.cancelInstallQueue(pkgName.hashCode().toLong())
    }

    /**
     * Starts an intent to uninstall the extension by the given package name.
     *
     * @param pkgName The package name of the extension to uninstall
     */
    fun uninstallApk(pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    /**
     * Sets the step of the installation of an extension.
     *
     * @param downloadId The id of the download.
     * @param step New install step.
     */
    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        activeSteps[downloadId]?.let { it.value = step }
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_DOWNLOAD_ID = "ExtensionInstaller.extra.DOWNLOAD_ID"

        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MILLIS = 750L
        private const val HTTP_SERVER_ERROR_MIN = 500
        private const val HTTP_SERVER_ERROR_MAX = 599
    }
}

private class SignatureMismatchException(storeName: String) : Exception(
    "Signature verification failed: the apk is not signed with the key of $storeName",
)

private class RetryableDownloadException(message: String) : IOException(message)

private class DownloadFailedException(message: String) : Exception("Download failed: $message")

private fun Exception.toInstallErrorReason(): String {
    return when (this) {
        is SignatureMismatchException -> message ?: "Signature verification failed"
        is DownloadFailedException -> message ?: "Download failed"
        is java.net.SocketTimeoutException -> "The download timed out. Check your connection and try again."
        is IOException ->
            "Network error: ${message ?: "connection problem"}. The installed extension was left unchanged."
        else -> message ?: "Installation failed. The installed extension was left unchanged."
    }
}
