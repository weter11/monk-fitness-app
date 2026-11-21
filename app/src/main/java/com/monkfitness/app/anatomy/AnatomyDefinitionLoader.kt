package com.monkfitness.app.anatomy

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses the anatomy JSON without knowing where it came from: the caller reads the asset, so this
 * stays free of Android types and a JVM test can load the shipped file directly.
 *
 * Every failure names the key path it happened at — a definition that silently drops a muscle is
 * worse than one that refuses to load.
 */
object AnatomyDefinitionLoader {

    fun load(json: String): AnatomyDefinition {
        val root = try {
            JSONObject(json)
        } catch (error: JSONException) {
            throw IllegalArgumentException("anatomy definition is not a JSON object: ${error.message}", error)
        }
        return AnatomyDefinition(
            id = root.optString("id").takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("anatomy definition has no id"),
            version = root.optInt("version", -1).takeIf { it > 0 }
                ?: throw IllegalArgumentException("anatomy definition has no positive version"),
            muscles = root.requiredArray("muscles").items().mapIndexed { index, item ->
                muscle(item.requiredObject(index), "muscles[$index]")
            },
            wrapSurfaces = root.requiredArray("wrapSurfaces").items().mapIndexed { index, item ->
                wrapSurface(item.requiredObject(index), "wrapSurfaces[$index]")
            },
            landmarks = root.requiredArray("landmarks").items().mapIndexed { index, item ->
                LandmarkDefinition(
                    id = item.requiredObject(index).requiredString("id", "landmarks[$index]"),
                    point = attachment(item.requiredObject(index).requiredObject("point", "landmarks[$index].point"), "landmarks[$index].point")
                )
            },
            epsNumMetres = root.requiredFloat("epsNumMetres", "definition"),
            sampling = sampling(root.requiredObject("sampling", "definition")),
            thetaMaxRadians = root.optDouble("thetaMaxRadians", 2.6).toFloat()
        )
    }

    private fun muscle(obj: JSONObject, path: String) = MuscleDefinition(
        id = obj.requiredString("id", path),
        name = obj.requiredString("name", path),
        layer = obj.requiredEnum("layer", path),
        heads = obj.requiredArray("heads", path).items().mapIndexed { index, item ->
            head(item.requiredObject(index), "$path.heads[$index]")
        },
        widthMetres = obj.requiredFloat("widthMetres", path),
        thicknessMetres = obj.requiredFloat("thicknessMetres", path),
        tendon = obj.requiredObject("tendon", path).let { tendon ->
            TendonSpec(
                originFraction = tendon.requiredFloat("originFraction", "$path.tendon"),
                insertionFraction = tendon.requiredFloat("insertionFraction", "$path.tendon")
            )
        },
        wraps = obj.stringList("wraps"),
        flat = obj.optBoolean("flat", false),
        profile = obj.optDouble("profile", 0.5).toFloat(),
        radiusKnots = obj.requiredArray("radiusKnots", path).items().mapIndexed { index, item ->
            val pair = item as? JSONArray ?: throw IllegalArgumentException("$path.radiusKnots[$index] is not a pair")
            if (pair.length() != 2) throw IllegalArgumentException("$path.radiusKnots[$index] is not a pair")
            pair.getDouble(0).toFloat() to pair.getDouble(1).toFloat()
        },
        passCap = obj.optInt("passCap", 8),
        corridorMetres = obj.requiredFloat("corridorMetres", path),
        contactExemptions = obj.optArray("contactExemptions").mapIndexed { index, item ->
            val exemption = item.requiredObject(index)
            val exemptionPath = "$path.contactExemptions[$index]"
            ContactExemption(
                obstacleId = exemption.requiredString("obstacleId", exemptionPath),
                headIndex = exemption.requiredInt("headIndex", exemptionPath),
                fromStation = exemption.requiredFloat("fromStation", exemptionPath),
                toStation = exemption.requiredFloat("toStation", exemptionPath),
                envelopeSlackMetres = exemption.optDouble("envelopeSlackMetres", 0.0).toFloat(),
                source = exemption.requiredString("source", exemptionPath)
            )
        }
    )

    private fun head(obj: JSONObject, path: String) = MuscleHead(
        origin = attachment(obj.requiredObject("origin", path), "$path.origin"),
        insertion = attachment(obj.requiredObject("insertion", path), "$path.insertion"),
        waypoints = obj.optArray("waypoints").mapIndexed { index, item ->
            attachment(item.requiredObject(index), "$path.waypoints[$index]")
        },
        label = obj.opt("label").let { if (it == null || it == JSONObject.NULL) "" else it.toString() }
    )

    private fun attachment(obj: JSONObject, path: String) = Attachment(
        segment = obj.requiredEnum("segment", path),
        x = obj.requiredFloat("x", path),
        y = obj.requiredFloat("y", path),
        z = obj.requiredFloat("z", path),
        // Absent is the honest default: nobody recorded which convention produced the numbers.
        frame = obj.opt("frame").let { raw ->
            if (raw == null || raw == JSONObject.NULL) AttachmentFrame.UNRECORDED
            else enumOf<AttachmentFrame>(raw.toString(), "$path.frame")
        }
    )

    private fun wrapSurface(obj: JSONObject, path: String) = WrapSurface(
        id = obj.requiredString("id", path),
        segment = obj.requiredEnum("segment", path),
        centreMetres = obj.triple("centreMetres", path),
        radiiMetres = obj.triple("radiiMetres", path),
        global = obj.optBoolean("global", false)
    )

    private fun sampling(obj: JSONObject) = Sampling(
        spacingMetres = obj.requiredFloat("spacingMetres", "sampling"),
        minSamples = obj.requiredInt("minSamples", "sampling"),
        maxSamples = obj.requiredInt("maxSamples", "sampling"),
        maxPointsPerHead = obj.requiredInt("maxPointsPerHead", "sampling")
    )

    private fun JSONObject.triple(key: String, path: String): Triple<Float, Float, Float> {
        val array = requiredArray(key, path)
        if (array.length() != 3) throw IllegalArgumentException("$path.$key must be three numbers, was ${array.length()}")
        return Triple(array.getDouble(0).toFloat(), array.getDouble(1).toFloat(), array.getDouble(2).toFloat())
    }

    private fun JSONObject.requiredObject(key: String, path: String): JSONObject =
        opt(key) as? JSONObject ?: throw IllegalArgumentException("$path has no object \"$key\"")

    private fun JSONObject.requiredArray(key: String, path: String = "definition"): JSONArray =
        opt(key) as? JSONArray ?: throw IllegalArgumentException("$path has no array \"$key\"")

    private fun JSONArray.items(): List<Any?> = (0 until length()).map { get(it) }

    private fun JSONObject.optArray(key: String): List<Any?> {
        val array = opt(key) as? JSONArray ?: return emptyList()
        return (0 until array.length()).map { array.get(it) }
    }

    private fun JSONObject.stringList(key: String): List<String> =
        optArray(key).map { it?.toString() ?: throw IllegalArgumentException("\"$key\" contains a non-string entry") }

    private fun JSONObject.requiredString(key: String, path: String): String {
        val value = opt(key)
        if (value == null || value == JSONObject.NULL || value.toString().isEmpty()) {
            throw IllegalArgumentException("$path has no \"$key\"")
        }
        return value.toString()
    }

    private fun JSONObject.requiredInt(key: String, path: String): Int {
        val value = opt(key) as? Number ?: throw IllegalArgumentException("$path has no number \"$key\"")
        return value.toInt()
    }

    private fun JSONObject.requiredFloat(key: String, path: String): Float {
        val value = opt(key) as? Number ?: throw IllegalArgumentException("$path has no number \"$key\"")
        return value.toFloat()
    }

    private inline fun <reified T : Enum<T>> JSONObject.requiredEnum(key: String, path: String): T =
        enumOf(requiredString(key, path), "$path.$key")

    private inline fun <reified T : Enum<T>> enumOf(raw: String, path: String): T =
        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException("$path has no value \"$raw\" (expected ${enumValues<T>().joinToString()})")

    private fun Any?.requiredObject(index: Int): JSONObject =
        this as? JSONObject ?: throw IllegalArgumentException("entry $index is not an object")
}
