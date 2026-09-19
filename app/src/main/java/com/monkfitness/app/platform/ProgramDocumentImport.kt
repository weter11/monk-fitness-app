package com.monkfitness.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.monkfitness.app.domain.program.transfer.ProgramTransferFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * The Android **document-open** boundary — §12's *"document picker/open-document intent"*, `URI` access,
 * MIME handling and reading bytes from a `ContentResolver`.
 *
 * ### The split this class implements
 *
 * ```text
 * Android layer   the picker intent, the URI, the permission, the bytes            this file
 * core importer   bytes → parse → validate → draft → save                          ProgramImportService
 * ```
 *
 * The core importer is handed **bytes** and knows nothing else — no `Uri`, no `Context`, no
 * `ContentResolver`, no `Activity` result. That is what makes it testable without a device and what keeps
 * §12's *"do not make JSON parsing depend on an Android `Uri`"* a property of the shape rather than a
 * rule to remember: [ProgramImportService.review] takes a `ByteArray` and has nowhere to put a URI.
 *
 * ### Why `ACTION_OPEN_DOCUMENT` and not a new activity of our own
 *
 * §12 asks for the cleanest platform boundary, and the platform's own document picker is it: the user
 * chooses the file, the system grants this app a read permission for that one document, and the app reads
 * it through the resolver. No permission is requested in the manifest, no storage path is guessed, and the
 * app never sees a filesystem path — only a URI it was granted.
 *
 * A manifest `VIEW` intent-filter for the format is deliberately **not** added here: receiving a program
 * file by opening it from another app would make the import a launch destination, which is a navigation
 * decision (§30 step 14's Settings/navigation cleanup), and this stage adds no destination. The mechanism
 * this class provides is what such a destination would call.
 */
object ProgramDocumentImport {

    /**
     * The picker intent: documents only, of the format's own MIME type.
     *
     * `CATEGORY_OPENABLE` limits the answer to documents that can actually be streamed (the platform offers
     * cloud providers that are not), and `EXTRA_MIME_TYPES` narrows the filter to JSON — the format's type —
     * rather than to every file the user owns.
     */
    fun openDocumentIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = ProgramTransferFormat.MIME_TYPE
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(ProgramTransferFormat.MIME_TYPE))
    }

    /**
     * The bytes of the document the user picked.
     *
     * The read is bounded: at most [ProgramTransferFormat.MAXIMUM_DOCUMENT_BYTES] plus one byte is taken
     * from the stream, so a document that is larger than the format allows is *reported* by the importer
     * (which refuses anything over the limit) rather than loaded in full first. One extra byte is enough to
     * know the file is too large, which is exactly the reason §14 gives for having a limit at all.
     *
     * @throws IOException when the URI cannot be read — a revoked grant, a provider that is gone, or a
     *   stream that fails part-way. The caller reports it; it is never swallowed into "no file".
     */
    suspend fun read(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val stream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("the picked document could not be opened: $uri")
        stream.use { open ->
            open.readNBytes(ProgramTransferFormat.MAXIMUM_DOCUMENT_BYTES + 1)
        }
    }
}
