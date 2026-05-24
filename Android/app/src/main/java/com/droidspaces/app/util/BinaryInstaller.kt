package com.droidspaces.app.util

import android.content.Context
import android.os.Build
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed class InstallationStep {
    data class DetectingArchitecture(val arch: String) : InstallationStep()
    data class CreatingDirectories(val path: String) : InstallationStep()
    data class CopyingBinary(val binary: String) : InstallationStep()
    data class SettingPermissions(val path: String) : InstallationStep()
    data class Verifying(val path: String) : InstallationStep()
    object Success : InstallationStep()
    data class Error(val message: String) : InstallationStep()
}

object BinaryInstaller {
    private const val INSTALL_PATH = Constants.INSTALL_PATH
    private const val DROIDSPACES_BINARY_NAME = Constants.DROIDSPACES_BINARY_NAME

    /**
     * Map Android architecture to binary name suffix
     */
    private fun getArchitectureSuffix(): String {
        val arch = Build.SUPPORTED_ABIS[0] // Primary ABI
        return when {
            arch.contains("arm64") || arch.contains("aarch64") -> "aarch64"
            arch.contains("armeabi") || arch.contains("arm") -> "armhf"
            arch.contains("x86_64") -> "x86_64"
            arch.contains("x86") -> "x86"
            else -> "aarch64" // Default to aarch64
        }
    }

    /**
     * Get droidspaces binary name for architecture
     */
    private fun getDroidspacesBinaryName(): String {
        return "droidspaces-${getArchitectureSuffix()}"
    }

    /**
     * Get human-readable architecture name
     */
    fun getArchitectureName(): String {
        val arch = Build.SUPPORTED_ABIS[0]
        return when {
            arch.contains("arm64") || arch.contains("aarch64") -> "ARM64 (aarch64)"
            arch.contains("armeabi") || arch.contains("arm") -> "ARM (armhf)"
            arch.contains("x86_64") -> "x86_64"
            arch.contains("x86") -> "x86"
            else -> arch
        }
    }

    /**
     * Install droidspaces binary with progress updates
     */
    suspend fun install(
        context: Context,
        onProgress: (InstallationStep) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Detect architecture
            val arch = getArchitectureName()
            onProgress(InstallationStep.DetectingArchitecture(arch))

            val droidspacesBinaryName = getDroidspacesBinaryName()

            // Always install to the canonical path. The daemon's g_self_path fix
            // means this is safe even while the daemon is running - the mv is
            // atomic and the daemon automatically re-execs the new binary.
            val droidspacesTargetPath = Constants.DROIDSPACES_BINARY_PATH

            // Step 2: Create directories
            onProgress(InstallationStep.CreatingDirectories(INSTALL_PATH))
            val mkdirResult = Shell.cmd("mkdir -p '$INSTALL_PATH'").exec()
            if (!mkdirResult.isSuccess) {
                val errorMsg = mkdirResult.err.joinToString() + mkdirResult.out.joinToString()
                return@withContext Result.failure(
                    Exception("Failed to create directory: ${errorMsg.ifEmpty { "unknown error" }}")
                )
            }

            // Helper function to install a binary
            // Uses atomic move operation to avoid "text file busy" error when binary is running.
            // Strategy: Copy to temp file in same directory, then use mv (atomic on same filesystem).
            // This works even if the target binary is currently executing.
            fun installBinary(assetName: String, targetPath: String, displayName: String): Result<Unit> {
                onProgress(InstallationStep.CopyingBinary(displayName))
                val assetManager = context.assets
                val inputStream = assetManager.open("binaries/$assetName")

                // Write to a temp file in app cache first (we can write here without root)
                val tempFile = File("${context.cacheDir}/$assetName")
                FileOutputStream(tempFile).use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()

                // Create temp file path in the same directory as target (same filesystem for atomic move)
                // Using .tmp suffix - the move is atomic so no race condition
                val tempTargetPath = "$targetPath.tmp"

                // Step 1: Copy from app cache to temp location in target directory (requires root)
                val copyResult = Shell.cmd("cp '${tempFile.absolutePath}' '$tempTargetPath'").exec()
                if (!copyResult.isSuccess) {
                    tempFile.delete()
                    val errorMsg = copyResult.err.joinToString() + copyResult.out.joinToString()
                    return Result.failure(
                        Exception("Failed to copy $displayName to temp location: ${errorMsg.ifEmpty { "unknown error" }}")
                    )
                }
                tempFile.delete() // Clean up app cache temp file

                // Step 2: Set permissions on temp file (must be done before move)
                val chmodResult = Shell.cmd("chmod 755 '$tempTargetPath'").exec()
                if (!chmodResult.isSuccess) {
                    Shell.cmd("rm -f '$tempTargetPath'").exec() // Clean up temp file
                    val errorMsg = chmodResult.err.joinToString() + chmodResult.out.joinToString()
                    return Result.failure(
                        Exception("Failed to set permissions for $displayName: ${errorMsg.ifEmpty { "unknown error" }}")
                    )
                }

                // Step 3: Use atomic move (mv -f) to replace target file
                // mv is atomic on the same filesystem - it just renames the inode
                // This works even if the target binary is currently executing (no "text file busy" error)
                onProgress(InstallationStep.SettingPermissions(targetPath))
                val moveResult = Shell.cmd("mv -f '$tempTargetPath' '$targetPath'").exec()
                if (!moveResult.isSuccess) {
                    Shell.cmd("rm -f '$tempTargetPath'").exec() // Clean up temp file on failure
                    val errorMsg = moveResult.err.joinToString() + moveResult.out.joinToString()
                    return Result.failure(
                        Exception("Failed to install $displayName: ${errorMsg.ifEmpty { "unknown error" }}")
                    )
                }

                // Step 4: Final permission check (mv preserves permissions, but ensure they're correct)
                val verifyChmodResult = Shell.cmd("chmod 755 '$targetPath'").exec()
                if (!verifyChmodResult.isSuccess) {
                    // Non-fatal warning - permissions might already be correct
                    // Continue as the move succeeded
                }

                return Result.success(Unit)
            }

            // Step 3: Install droidspaces binary (only core binary now)
            installBinary(droidspacesBinaryName, droidspacesTargetPath, "droidspaces")
                .getOrElse { error -> return@withContext Result.failure(error) }

            // Note: busybox is no longer bundled. Scripts will dynamically detect:
            // 1. System busybox (via PATH)
            // 2. Fallback to /data/local/Droidspaces/bin/busybox if present
            // This reduces APK size and improves compatibility.

            // Step 4: Verification
            onProgress(InstallationStep.Verifying("droidspaces"))
            val verifyDroidspaces = Shell.cmd("test -x '$droidspacesTargetPath' && echo 'verified' || echo 'verification_failed'").exec()

            if (!verifyDroidspaces.isSuccess || !verifyDroidspaces.out.any { it.contains("verified") }) {
                return@withContext Result.failure(
                    Exception("Droidspaces binary verification failed: file is not executable at $droidspacesTargetPath")
                )
            }

            // Success
            onProgress(InstallationStep.Success)
            Result.success(Unit)

        } catch (e: Exception) {
            onProgress(InstallationStep.Error(e.message ?: "Unknown error"))
            Result.failure(e)
        }
    }

    /**
     * After a live binary swap, send SIGUSR2 to the running daemon
     * so it acknowledges the update (used for logging).
     */
    suspend fun signalDaemon(): Unit = withContext(Dispatchers.IO) {
        val pidResult = Shell.cmd("cat '${Constants.DAEMON_PID_FILE}' 2>/dev/null").exec()
        if (pidResult.isSuccess && pidResult.out.isNotEmpty()) {
            val pid = pidResult.out[0].trim()
            if (pid.isNotEmpty()) {
                Shell.cmd("kill -USR2 '$pid' 2>/dev/null").exec()
            }
        }
    }

    /**
     * Check if binaries are already installed
     */
    suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
        val droidspacesResult = Shell.cmd("test -x '$INSTALL_PATH/$DROIDSPACES_BINARY_NAME' && echo 'installed' || echo 'not_installed'").exec()
        val droidspacesOk = droidspacesResult.isSuccess && droidspacesResult.out.any { it.contains("installed") }

        droidspacesOk
    }
}

