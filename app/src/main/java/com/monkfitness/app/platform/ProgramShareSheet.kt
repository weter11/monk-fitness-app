package com.monkfitness.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.monkfitness.app.domain.program.transfer.ProgramTransferFile
import java.io.File

/**
 * The Android **Share Sheet** boundary: `ACTION_SEND` over a shareable `content://` URI.
 *
 * `content://` and not `file://` is not a preference: since API 24 an app that hands another app a `file://`
 * URI through an `Intent` does not merely misbehave — the platform throws `FileUriExposedException` and the
 * share crashes. `minSdk` is 24, so a `file://` share would be broken on **every** device this app runs on.
 * The file is therefore staged in the app's own cache and handed over through [FileProvider], which mints a
 * `content://` URI the receiving app may read *for this grant only*; nothing else in the cache becomes
 * visible.
 *
 * It is the **only** place in the app that builds an `Intent` for a Program transfer. The value it is handed
 * ([ProgramTransferFile]) knows nothing about Android, and the bytes, filename and MIME type are the
 * exporter's and the format's own constants — so the transfer layer cannot leak an `Intent` because it has
 * no `Intent` to leak.
 */
object ProgramShareSheet {

    /**
     * The suffix of the [FileProvider] authority this app declares; the manifest registers the provider as
     * `${applicationId}.fileprovider`. The runtime authority is derived from the same application id rather
     * than hard-coded, so a build with another application id (a fork, a variant) shares correctly instead
     * of silently handing over an authority nothing serves.
     */
    const val FILE_PROVIDER_AUTHORITY_SUFFIX: String = ".fileprovider"

    /** The cache subdirectory shared files are staged in, matching `res/xml/program_file_paths.xml`. */
    const val SHARED_DIRECTORY: String = "shared-programs"

    /** The authority this app's provider serves, for the running package's application id. */
    fun authority(applicationId: String): String = applicationId + FILE_PROVIDER_AUTHORITY_SUFFIX

    /**
     * Shares [file] with whatever app the user picks.
     *
     * The chooser is started with `FLAG_ACTIVITY_NEW_TASK` because the caller may hold an application
     * context, and a chooser started from one needs the flag; on an activity context it is harmless.
     */
    fun share(context: Context, file: ProgramTransferFile) {
        context.startActivity(chooser(context, file))
    }

    /** The chooser the user sees, over [sendIntent]. */
    internal fun chooser(context: Context, file: ProgramTransferFile): Intent =
        Intent.createChooser(sendIntent(context, file), null).apply {
            // The grant is carried on both the chooser and the inner intent: the platform forwards the
            // chooser's flags to the target it resolves to, and older releases read it from the inner intent.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * The `ACTION_SEND` intent for [file]: one stream, the format's MIME type, and a read grant the
     * receiving app did not have to ask for.
     *
     * `EXTRA_TEXT` is deliberately absent: the receiving app is offered a *file* to save or open, not a
     * paragraph to paste, and putting the document's text in the extra would copy a program definition into
     * every clipboard a share target keeps.
     */
    internal fun sendIntent(context: Context, file: ProgramTransferFile): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = file.mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * Stages [file] in the app's cache and returns the `content://` URI that speaks for it. It lives in a
     * directory only this app's provider serves, so the grant is scoped to one file rather than to the cache
     * as a whole, and the bytes written are the exported document exactly — a share must carry the file the
     * exporter produced, not a re-rendering of it.
     */
    internal fun contentUri(context: Context, file: ProgramTransferFile): Uri {
        val directory = File(context.cacheDir, SHARED_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException(
                "the app's cache directory is not writable, so a program file cannot be staged for " +
                    "sharing: ${directory.absolutePath}"
            )
        }
        val staged = File(directory, file.fileName)
        staged.writeBytes(file.bytes)
        return FileProvider.getUriForFile(context, authority(context.packageName), staged)
    }
}
