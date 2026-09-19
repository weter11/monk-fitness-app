package com.monkfitness.app.platform

import com.monkfitness.app.domain.program.transfer.ProgramTransferFile
import com.monkfitness.app.domain.program.transfer.ProgramTransferFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §11 and §12's platform boundary — the one place an `Intent` is built — asserted against the sources, the
 * manifest and the resource the provider needs.
 *
 * The split this suite exists to keep is the one §11 asks for:
 *
 * ```text
 * pure and tested on the JVM      the format, the document, the bytes, the filename, the MIME type
 * the Android boundary            ACTION_SEND and ACTION_OPEN_DOCUMENT over a content:// URI
 * ```
 *
 * There is no device in this repository's unit-test source set, so the Android half is pinned where it is
 * *written*: the tokens that make a share correct (`ACTION_SEND`, `EXTRA_STREAM`, the read grant,
 * `FileProvider.getUriForFile`, the chooser), the tokens that make it a **crash** on this app's `minSdk`
 * (`file://`, `Uri.fromFile`), the manifest declaration that makes the `content://` URI legal, and the
 * resource path that scopes it to one cache directory. The pure half is asserted by value, which is what
 * §21 means by *"test pure exported bytes separately from Android intent construction"*.
 */
class ProgramTransferPlatformBoundaryTest {

    private val mainDir = File("src/main/java/com/monkfitness/app")
        .let { if (it.isDirectory) it else File("app/$it") }

    private val shareSource = "platform/ProgramShareSheet.kt"
    private val documentSource = "platform/ProgramDocumentImport.kt"

    private fun source(relativePath: String): String = File(mainDir, relativePath)
        .also { file -> assertTrue("expected $relativePath at ${file.absolutePath}", file.isFile) }
        .readText()

    /**
     * A file of the Android module, from either of the two working directories a unit test can run in
     * (`app/`, which is the repository's own test JVM, or the repository root).
     */
    private fun moduleFile(vararg relative: String): File {
        val joined = relative.joinToString("/")
        val candidate = listOf(File("app", joined), File(joined)).firstOrNull { file -> file.isFile }
        assertTrue("expected $joined under ${File("").absolutePath}", candidate != null)
        return candidate!!
    }

    private fun codeOf(relativePath: String): String = source(relativePath)
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    // ------------------------------------------------------------------ the share (§11)

    @Test
    fun theShareIsAnActionSendCarryingOneContentUriOfTheFormatsOwnType() {
        val share = codeOf(shareSource)

        assertTrue(
            "§11's primary mechanism is ACTION_SEND",
            share.contains("Intent(Intent.ACTION_SEND)")
        )
        assertTrue(
            "and the payload is the exported file, handed over as a stream — never as pasted text",
            share.contains("putExtra(Intent.EXTRA_STREAM, contentUri(context, file))")
        )
        assertTrue(
            "the receiving app needs no permission of its own: the share carries a read grant",
            share.contains("addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)")
        )
        assertTrue(
            "and the user picks the target, so the app does not choose who receives a Program",
            share.contains("Intent.createChooser(")
        )
        assertTrue(
            "the type is the file's own type, not a guess",
            share.contains("type = file.mimeType")
        )
        assertTrue(
            "the URI is minted by the platform's provider rather than assembled by hand",
            share.contains("FileProvider.getUriForFile(context, authority(context.packageName), staged)")
        )
    }

    @Test
    fun theShareNeverHandsOutAFilePath() {
        val share = codeOf(shareSource)

        // `absolutePath` deliberately is not in this list: the share names a path in one *error message*
        // (a cache directory that cannot be written), which is a sentence for the log rather than a value
        // handed to another app. What may never happen is a path becoming the payload.
        listOf("file://", "Uri.fromFile", "EXTRA_TEXT").forEach { token ->
            assertFalse(
                "a `file://` URI is a `FileUriExposedException` on every device this app runs on " +
                    "(minSdk 24), and a path in EXTRA_TEXT is a Program copied into someone's clipboard: " +
                    "the payload is one content:// stream. Found: $token",
                share.contains(token)
            )
        }
    }

    @Test
    fun theStagedFileIsTheExportersOwnBytesUnderTheFormatsOwnNameInTheAppsOwnCache() {
        val share = codeOf(shareSource)

        assertTrue(
            "the bytes written are the document the exporter produced, not a re-rendering of it",
            share.contains("staged.writeBytes(file.bytes)")
        )
        assertTrue(
            "the name is the one the format chose — never a Program's identity or title",
            share.contains("File(directory, file.fileName)")
        )
        assertTrue(
            "and the staging directory is the app's cache, which the system may reclaim",
            share.contains("File(context.cacheDir, SHARED_DIRECTORY)")
        )
        assertTrue(
            "the share refuses loudly when the cache cannot be written, rather than sharing nothing",
            share.contains("mkdirs()")
        )
    }

    @Test
    fun theProviderIsDeclaredForExactlyTheCacheDirectoryTheShareStagesIn() {
        val manifest = moduleFile("src", "main", "AndroidManifest.xml").readText()
        val paths = moduleFile("src", "main", "res", "xml", "program_file_paths.xml")
        val applicationId = moduleFile("build.gradle.kts")
            .readText()
            .lineSequence()
            .first { line -> line.trim().startsWith("applicationId") }
            .substringAfter("\"")
            .substringBefore("\"")

        assertTrue(
            "the provider is declared with the authority the share computes at runtime, derived from the " +
                "application id so the two cannot drift",
            manifest.contains("android:authorities=\"\${applicationId}.fileprovider\"") &&
                manifest.contains("androidx.core.content.FileProvider")
        )
        assertTrue(
            "it grants per-URI read permissions (that is what a share carries) and is not exported",
            manifest.contains("android:grantUriPermissions=\"true\"") &&
                manifest.contains("android:exported=\"false\"")
        )
        assertTrue(
            "and it serves the path declaration, without which a content:// URI from it is refused",
            manifest.contains("android.support.FILE_PROVIDER_PATHS") &&
                manifest.contains("@xml/program_file_paths")
        )
        assertTrue(
            "which names the cache directory the share stages in, and nothing else: the grant covers one " +
                "directory of regenerable files, never the app's own data",
            paths.readText().contains("<cache-path") &&
                paths.readText().contains("path=\"${ProgramShareSheet.SHARED_DIRECTORY}/\"")
        )
        assertEquals(
            "and the runtime authority is the manifest's, for this app's own application id",
            "$applicationId.fileprovider",
            ProgramShareSheet.authority(applicationId)
        )
        assertEquals("shared-programs", ProgramShareSheet.SHARED_DIRECTORY)
    }

    // ------------------------------------------------------------------ the import (§12)

    @Test
    fun thePickerAsksThePlatformForOneOpenableDocumentOfTheFormatsOwnType() {
        val picker = codeOf(documentSource)

        assertTrue(
            "§12's acquisition boundary is the platform's own document picker: no storage permission, no " +
                "guessed path, and a read grant for one document the user chose",
            picker.contains("Intent(Intent.ACTION_OPEN_DOCUMENT)")
        )
        assertTrue("only documents that can actually be streamed", picker.contains("Intent.CATEGORY_OPENABLE"))
        assertTrue("of the format's own type", picker.contains("type = ProgramTransferFormat.MIME_TYPE"))
        assertTrue(
            "and narrowed to it, so the picker offers a program file rather than every file the user owns",
            picker.contains("Intent.EXTRA_MIME_TYPES")
        )
    }

    @Test
    fun theReadIsBoundedAndItsFailureIsNotAnEmptyDocument() {
        val picker = codeOf(documentSource)

        assertTrue(
            "the read takes at most the format's limit plus one byte, so a document that is too large is " +
                "reported by the importer instead of being loaded in full first (§14)",
            picker.contains("readNBytes(ProgramTransferFormat.MAXIMUM_DOCUMENT_BYTES + 1)")
        )
        assertTrue(
            "a stream that cannot be opened is an IOException rather than an empty array: an import that " +
                "read nothing must not look like a file that says nothing (§13, §33)",
            picker.contains("throw IOException(")
        )
        assertTrue(
            "and it reads through the resolver, which is the only thing that can open a URI the user granted",
            picker.contains("contentResolver.openInputStream(uri)")
        )
    }

    // ------------------------------------------------------------------ the pure half

    @Test
    fun thePlatformBoundaryKnowsTheTransferredFileAndNothingElseAboutPrograms() {
        val forbidden = listOf(
            "ProgramRevision", "ProgramDay", "ProgramExercise", "ProgramId", "RevisionId", "WorkoutSlot",
            "ProgramMode", "ProgramSource", "AppContainer", "Repository", "Dao", "AppDatabase"
        )
        val offenders = listOf(shareSource, documentSource).flatMap { source ->
            val text = codeOf(source)
            forbidden.filter { token -> text.contains(token) }.map { token -> "$source: $token" }
        }

        assertTrue(
            "the boundary turns a value into a share and bytes into bytes: it holds no Program, no plan, no " +
                "repository and no composition (§25). Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theSharePayloadIsTheExportedBytesAndIsAssertableWithoutADevice() {
        val document = "{\n  \"format\": \"monkfitness.program\",\n  \"formatVersion\": 1\n}\n"
        val file = ProgramTransferFile(
            fileName = ProgramTransferFormat.FILE_NAME,
            mimeType = ProgramTransferFormat.MIME_TYPE,
            bytes = ProgramTransferFormat.encode(document)
        )

        assertEquals("the name a share carries is the format's own", "MonkFitnessProgram.mfp.json", file.fileName)
        assertEquals("and its type is the type of the bytes", "application/json", file.mimeType)
        assertEquals(
            "the bytes are the document, decoded the way the format defines it",
            document,
            file.text
        )
        assertTrue(
            "two files of the same document are the same file, to the byte",
            file.hasTheSameBytesAs(
                ProgramTransferFile(
                    ProgramTransferFormat.FILE_NAME,
                    ProgramTransferFormat.MIME_TYPE,
                    ProgramTransferFormat.encode(document)
                )
            )
        )
        assertFalse(
            "and a different document is a different file",
            file.hasTheSameBytesAs(
                ProgramTransferFile(
                    ProgramTransferFormat.FILE_NAME,
                    ProgramTransferFormat.MIME_TYPE,
                    ProgramTransferFormat.encode(document + " ")
                )
            )
        )
        assertTrue(
            "the payload carries no identity: the name is a constant of the format, not of a Program",
            file.fileName == ProgramTransferFormat.FILE_NAME &&
                !file.fileName.contains("program-")
        )
    }
}
