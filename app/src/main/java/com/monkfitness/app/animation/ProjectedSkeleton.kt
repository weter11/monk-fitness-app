package com.monkfitness.app.animation

class ProjectedPoint {
    var x: Float = 0f
    var y: Float = 0f
    var depth: Float = 0f
    var perspectiveScale: Float = 1f

    fun update(x: Float, y: Float, depth: Float, scale: Float) {
        this.x = x
        this.y = y
        this.depth = depth
        this.perspectiveScale = scale
    }

    fun copyFrom(other: ProjectedPoint) {
        this.x = other.x
        this.y = other.y
        this.depth = other.depth
        this.perspectiveScale = other.perspectiveScale
    }
}

class ProjectedJoint {
    var id: Joint = Joint.PELVIS
    val point = ProjectedPoint()
    var isIndicator: Boolean = false

    fun update(source: ProjectedPoint, jointId: Joint, indicator: Boolean = false) {
        id = jointId
        point.copyFrom(source)
        isIndicator = indicator
    }
}

class ProjectedBone {
    val p1 = ProjectedPoint()
    val p2 = ProjectedPoint()
    var thickness: Float = 0f
    var colorMultiplier: Float = 1.0f
    var avgDepth: Float = 0f
    var isForeground: Boolean = false

    fun update(src1: ProjectedPoint, src2: ProjectedPoint, thick: Float, colorM: Float) {
        p1.copyFrom(src1)
        p2.copyFrom(src2)
        thickness = thick
        colorMultiplier = colorM
        avgDepth = (src1.depth + src2.depth) / 2f
        isForeground = colorM >= 1.0f
    }
}

class ProjectedFace {
    val points = Array(4) { ProjectedPoint() }
    var avgDepth: Float = 0f

    fun update(p1: ProjectedPoint, p2: ProjectedPoint, p3: ProjectedPoint, p4: ProjectedPoint) {
        points[0].copyFrom(p1)
        points[1].copyFrom(p2)
        points[2].copyFrom(p3)
        points[3].copyFrom(p4)
        avgDepth = (p1.depth + p2.depth + p3.depth + p4.depth) / 4f
    }
}

class ProjectedGridLine {
    val p1 = ProjectedPoint()
    val p2 = ProjectedPoint()
}

/**
 * Encapsulates a skeleton after it has been projected into screen space.
 * Designed for buffer reuse to eliminate heap allocations in the hot path.
 */
class ProjectedSkeleton {
    val jointsMap = Array(Joint.entries.size) { ProjectedPoint() }
    val indicators = Array(2) { ProjectedJoint() }
    val bones = Array(30) { ProjectedBone() }
    val faces = Array(6) { ProjectedFace() }
    val shadowPoints = Array(5) { ProjectedPoint() }
    val gridLines = Array(20) { ProjectedGridLine() }

    var boneCount: Int = 0
    var faceCount: Int = 0
    var gridLineCount: Int = 0
    var depthMin: Float = 0f
    var depthMax: Float = 0f
}
