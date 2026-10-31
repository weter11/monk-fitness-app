package com.monkfitness.app.domain.program.transfer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The transfer format's identity, its encoding boundary and the one size guard it carries.
 *
 * Everything here is a constant of the format rather than a decision of a layer: the marker a reader uses
 * to tell "not a program file" from "this program file is broken", the version the writer stamps and the
 * reader supports, the filename and MIME type the Android Share Sheet is given, and the byte limit a
 * document may reach.
 *
 * [decode] is where "UTF-8 JSON" is true rather than hoped: it *reports* malformed input and unmappable
 * characters instead of substituting `U+FFFD`. The default `String(bytes, UTF_8)` replaces every illegal
 * sequence with a replacement character, which would turn a corrupt file into a program whose name
 * quietly contains `?` — a silent repair of input the format says is untrusted.
 *
 * [MAXIMUM_DOCUMENT_BYTES] exists because a shared file is user-supplied: "as much as the file happens
 * to be" is not a bound. One megabyte is roughly four hundred times the largest program the domain can
 * hold, so it refuses a file that is not this format without ever refusing a real one. No second guard
 * for collection sizes is carried: the byte limit bounds every count downstream of it.
 */
object ProgramTransferFormat {

    /**
     * The marker every document carries. It is what makes "this JSON is not a Monk Fitness program" a
     * distinct, explainable answer rather than a schema violation reported in the middle of a document
     * that was never a program file at all.
     */
    const val MARKER: String = "monkfitness.program"

    /**
     * The version this writer stamps and the only version this reader accepts.
     *
     * A compatibility policy, not a number: a reader accepts exactly the versions it knows how to read,
     * and a document from a newer app is refused with
     * [ProgramTransferRejection.UnsupportedFormatVersion] rather than partially understood — so adding a
     * field is a version bump, which keeps "same program in, byte-identical JSON out" a claim about one
     * known format.
     *
     * `revision.preferredExercises` joined version 1's revision object, which is why a version-1 file is
     * refused as unsupported and **not** read as a program with no preference: its `preferredExercises`
     * is absent, not empty, and treating an older file as today's would be a guess about what its author
     * meant.
     */
    const val VERSION: Int = 2

    /**
     * The name the shared file carries.
     *
     * Fixed, and deliberately not built from the Program: a filename travels through the receiving app's
     * file system, its share history and its thumbnails, so it may not contain an identity or a runtime
     * fact. `.mfp` names the app's own program file; the `.json` tail keeps it openable by anything that
     * reads JSON.
     */
    const val FILE_NAME: String = "MonkFitnessProgram.mfp.json"

    /**
     * The MIME type of the shared file.
     *
     * `application/json` rather than a vendor type: the file *is* JSON, the tail of [FILE_NAME] says so,
     * and a `vnd.`-style type would hide the file from every receiving app that dispatches on what it can
     * actually open.
     */
    const val MIME_TYPE: String = "application/json"

    /** The largest document this format reads or writes. See the class KDoc for why exactly one guard. */
    const val MAXIMUM_DOCUMENT_BYTES: Int = 1024 * 1024

    fun encode(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /**
     * The text [bytes] holds, or a [ProgramTransferTextError] naming what was wrong with them.
     *
     * @throws ProgramTransferTextError when the bytes are not UTF-8 — malformed input is refused, never
     *   replaced.
     */
    fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        throw ProgramTransferTextError(
            "the document is not UTF-8 text: ${failure.javaClass.simpleName} at the byte the decoder " +
                "stopped on (§5)"
        )
    }
}

/**
 * A document whose bytes are not the text the format is written in.
 *
 * Typed rather than a message, so an import reports *which* boundary refused the file — the encoding
 * one, before JSON parsing even begins — instead of attributing an encoding failure to the document's
 * syntax.
 */
class ProgramTransferTextError(val reason: String) : IllegalArgumentException(reason)
