package com.monkfitness.app.character

/**
 * The contract an exported helper-bind manifest obeys, shared by the exporter that writes it and the
 * gate that reads it back, so the palette, the rest length and every emitted number are one rule.
 */
object HelperBindManifest {

    /** The most joints one skinning palette accepts. */
    const val PALETTE_LIMIT = 255

    /** The asset unit every position and basis in the manifest is expressed in. */
    const val UNITS = "metres"

    /**
     * The layer's whole joint count — the rig's own plus one per helper — refusing a palette the
     * renderable cannot bind. Returns the palette, so a caller writes and checks the same number.
     */
    fun requireWithinPalette(skinJoints: Int, helperCount: Int): Int {
        require(skinJoints > 0) { "the layer needs the rig's joints, got $skinJoints" }
        require(helperCount > 0) { "the layer needs helpers of its own, got $helperCount" }
        val palette = skinJoints + helperCount
        require(palette <= PALETTE_LIMIT) {
            "the layer needs $palette joints ($skinJoints body + $helperCount helpers), " +
                "over the $PALETTE_LIMIT the renderable accepts"
        }
        return palette
    }

    /**
     * A head's rest length, by arc. `+Inf` passes a bare `> 0` test and every station then samples a
     * fraction of it, so the length is required finite before the path is read.
     */
    fun requireRestLength(head: String, total: Double) {
        require(total.isFinite()) { "head $head has a non-finite rest length ($total)" }
        require(total > 0.0) { "head $head has a zero-length rest path" }
    }

    /**
     * The JSON text for [value]. `Double.toString` writes a non-finite value as `Infinity`/`NaN`,
     * which no JSON reader accepts, so it is refused here instead of written.
     */
    fun number(value: Double): String {
        require(value.isFinite()) { "$value is not representable in JSON" }
        return if (value == 0.0) "0.0" else java.lang.Double.toString(value)
    }
}
