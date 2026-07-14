package com.monkfitness.app.animation

/**
 * Configuration options for the ExerciseValidator.
 */
data class ValidatorConfig(
    val allowFootGroundPenetration: Boolean = false,
    val maxDisplacementThreshold: Float = 15f,
    val maxVelocityChangeThreshold: Float = 150f,
    val maxAccelerationThreshold: Float = 500f,
    val isStaticExercise: Boolean = false,
    val supportMargin: Float = 10f,
    val expectedSupportJoints: Set<Joint> = emptySet(),
    val checkBilateralSymmetry: Boolean = false,
    val checkHandShoulderAlignment: Boolean = false,
    val checkIkTargetReachability: Boolean = false,
    val checkAngularJointLimits: Boolean = false
) {
    companion object {
        /**
         * Config used when validating the engineering reference poses. Unlike normal product
         * validation, this turns on IK reachability detection so unreachable (clamped) targets
         * surface as [IK_TARGET_UNREACHABLE] instead of being silently ignored, and enables the
         * angular joint-limit rule ([ANGULAR_JOINT_LIMIT]) so impossible joint angles surface.
         */
        val ENGINEERING_VALIDATION = ValidatorConfig(
            checkIkTargetReachability = true,
            checkAngularJointLimits = true
        )
    }
}

/**
 * ExerciseValidator performs automated, read-only biomechanical validation
 * on a SkeletonPose. It ensures joints are finite, bones are constant length,
 * the head is visible, IK limits are respected, and movement is smooth.
 */
class ExerciseValidator(
    val config: ValidatorConfig = ValidatorConfig()
) {
    private val scratchHeadPoint = ProjectedPoint()

    companion object {
        // Pre-allocated static arrays to completely avoid heap allocations at runtime
        private val JOINTS_ARRAY = Joint.values()

        private val FEET_JOINTS = arrayOf(
            Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F,
            Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B
        )

        private val HANDS_JOINTS = arrayOf(
            Joint.HAND_A, Joint.HAND_P,
            Joint.WRIST_A, Joint.WRIST_P
        )

        private val CANDIDATE_SUPPORT_JOINTS = arrayOf(
            Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F,
            Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B,
            Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.FINGERTIPS_A,
            Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.FINGERTIPS_P,
            Joint.KNEE_F, Joint.KNEE_B
        )

        private val ALL_RULES = arrayOf(
            "FINITE_COORDINATES",
            "BONE_LENGTH",
            "HEAD_VIEWPORT",
            "FOOT_GROUND_PENETRATION",
            "HAND_SLIDING",
            "IK_CONSTRAINT_LIMIT",
            "POSITION_DISCONTINUITY",
            "VELOCITY_DISCONTINUITY",
            "ACCELERATION_SPIKE",
            "STATIC_SUPPORT_POLYGON",
            "BILATERAL_SYMMETRY",
            "HAND_SHOULDER_ALIGNMENT",
            "IK_TARGET_UNREACHABLE",
            "ANGULAR_JOINT_LIMIT"
        )
    }

    /**
     * Validates a SkeletonPose under the current environment, camera, and history.
     */
    fun validate(
        pose: SkeletonPose,
        definition: SkeletonDefinition,
        environment: EnvironmentDefinition,
        camera: Camera,
        width: Float,
        height: Float,
        previousPose: SkeletonPose? = null,
        prePreviousPose: SkeletonPose? = null,
        deltaTime: Float = 0f
    ): ValidationReport {
        val issues = ArrayList<ValidationIssue>()

        // Rule 1: Finite Coordinates
        validateFiniteCoordinates(pose, issues)

        // Rule 2: Constant Bone Lengths
        validateBoneLengths(pose, definition, issues)

        // Rule 3: Head inside Viewport
        validateHeadViewport(pose, camera, width, height, issues)

        // Rule 4: Feet penetrate ground
        validateFeetGroundPenetration(pose, environment, issues)

        // Rule 5: Hands sliding
        validateHandsSliding(pose, previousPose, environment, issues)

        // Rule 6: IK Constraint Limits
        validateIKConstraints(pose, definition, issues)

        // Rule 7, 8, 10: Velocity, Acceleration, and Discontinuities
        validateDynamics(pose, previousPose, prePreviousPose, deltaTime, issues)

        // Rule 9: Body support polygon
        validateSupportPolygon(pose, environment, issues)

        // Rule 11: Bilateral Symmetry
        validateBilateralSymmetry(pose, issues)

        // Rule 12: Hand Shoulder Alignment
        validateHandShoulderAlignment(pose, issues)

        // Rule 13: IK Target Reachability (unreachable checks)
        validateIkTargetReachability(pose, issues)

        // Rule 14: Angular joint limits (beyond the reach-distance band)
        validateAngularJointLimits(pose, definition, issues)

        // Assemble report (allocations allowed here)
        val results = ArrayList<ValidationResult>(ALL_RULES.size)
        for (r in 0 until ALL_RULES.size) {
            val ruleId = ALL_RULES[r]
            val ruleIssues = ArrayList<ValidationIssue>()
            for (i in 0 until issues.size) {
                val issue = issues[i]
                if (issue.ruleId == ruleId) {
                    ruleIssues.add(issue)
                }
            }
            var isValidRule = true
            for (i in 0 until ruleIssues.size) {
                if (ruleIssues[i].severity == ValidationSeverity.ERROR) {
                    isValidRule = false
                    break
                }
            }
            results.add(ValidationResult(ruleId, isValidRule, ruleIssues))
        }

        var overallValid = true
        for (i in 0 until issues.size) {
            if (issues[i].severity == ValidationSeverity.ERROR) {
                overallValid = false
                break
            }
        }

        return ValidationReport(overallValid, results, issues)
    }

    private fun validateFiniteCoordinates(pose: SkeletonPose, issues: MutableList<ValidationIssue>) {
        for (i in 0 until JOINTS_ARRAY.size) {
            val joint = JOINTS_ARRAY[i]
            val pos = pose.getJoint(joint)
            if (!pos.x.isFinite() || !pos.y.isFinite() || !pos.z.isFinite()) {
                issues.add(ValidationIssue(
                    ruleId = "FINITE_COORDINATES",
                    message = "Joint ${joint.name} contains non-finite coordinates: x=${pos.x}, y=${pos.y}, z=${pos.z}",
                    severity = ValidationSeverity.ERROR,
                    joint = joint
                ))
            }
        }
    }

    private fun validateBoneLengths(pose: SkeletonPose, def: SkeletonDefinition, issues: MutableList<ValidationIssue>) {
        validateBoneLength(pose, def, Joint.PELVIS, Joint.CHEST, def.torsoLength, issues)
        validateBoneLength(pose, def, Joint.CHEST, Joint.NECK_END, def.neckLength, issues)
        validateBoneLength(pose, def, Joint.NECK_END, Joint.HEAD_POS, 18f, issues)
        validateBoneLength(pose, def, Joint.PELVIS, Joint.HIP_F, def.hipWidth, issues)
        validateBoneLength(pose, def, Joint.PELVIS, Joint.HIP_B, def.hipWidth, issues)
        validateBoneLength(pose, def, Joint.CHEST, Joint.SHOULDER_A, def.shoulderWidth, issues)
        validateBoneLength(pose, def, Joint.CHEST, Joint.SHOULDER_P, def.shoulderWidth, issues)
        validateBoneLength(pose, def, Joint.HIP_F, Joint.KNEE_F, def.thighLength, issues)
        validateBoneLength(pose, def, Joint.HIP_B, Joint.KNEE_B, def.thighLength, issues)
        validateBoneLength(pose, def, Joint.KNEE_F, Joint.ANKLE_F, def.shinLength, issues)
        validateBoneLength(pose, def, Joint.KNEE_B, Joint.ANKLE_B, def.shinLength, issues)
        validateBoneLength(pose, def, Joint.ANKLE_F, Joint.HEEL_F, def.foot.footLength * def.foot.heelRatio, issues)
        validateBoneLength(pose, def, Joint.ANKLE_F, Joint.TOE_F, def.foot.footLength * def.foot.toeRatio, issues)
        validateBoneLength(pose, def, Joint.ANKLE_B, Joint.HEEL_B, def.foot.footLength * def.foot.heelRatio, issues)
        validateBoneLength(pose, def, Joint.ANKLE_B, Joint.TOE_B, def.foot.footLength * def.foot.toeRatio, issues)
        validateBoneLength(pose, def, Joint.SHOULDER_A, Joint.ELBOW_A, def.upperArmLength, issues)
        validateBoneLength(pose, def, Joint.SHOULDER_P, Joint.ELBOW_P, def.upperArmLength, issues)
        validateBoneLength(pose, def, Joint.ELBOW_A, Joint.HAND_A, def.forearmLength, issues)
        validateBoneLength(pose, def, Joint.ELBOW_P, Joint.HAND_P, def.forearmLength, issues)
        validateBoneLength(pose, def, Joint.HAND_A, Joint.WRIST_A, 0f, issues)
        validateBoneLength(pose, def, Joint.HAND_P, Joint.WRIST_P, 0f, issues)
        validateBoneLength(pose, def, Joint.WRIST_A, Joint.PALM_A, def.hand.palmLength * 0.5f, issues)
        validateBoneLength(pose, def, Joint.WRIST_P, Joint.PALM_P, def.hand.palmLength * 0.5f, issues)
        validateBoneLength(pose, def, Joint.PALM_A, Joint.KNUCKLES_A, def.hand.palmLength * 0.5f, issues)
        validateBoneLength(pose, def, Joint.PALM_P, Joint.KNUCKLES_P, def.hand.palmLength * 0.5f, issues)
        validateBoneLength(pose, def, Joint.KNUCKLES_A, Joint.FINGERTIPS_A, def.hand.fingerLength, issues)
        validateBoneLength(pose, def, Joint.KNUCKLES_P, Joint.FINGERTIPS_P, def.hand.fingerLength, issues)
    }

    private fun validateBoneLength(pose: SkeletonPose, def: SkeletonDefinition, parent: Joint, child: Joint, expected: Float, issues: MutableList<ValidationIssue>) {
        val p1 = pose.getJoint(parent)
        val p2 = pose.getJoint(child)
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        val dz = p1.z - p2.z
        val actual = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (expected == 0f) {
            if (actual > 0.01f) {
                issues.add(ValidationIssue(
                    ruleId = "BONE_LENGTH",
                    message = "Bone ${parent.name} -> ${child.name} actual length $actual deviates from expected 0.0",
                    severity = ValidationSeverity.ERROR,
                    joint = child
                ))
            }
        } else {
            val diff = kotlin.math.abs(actual - expected)
            if (diff > expected * 0.01f) {
                issues.add(ValidationIssue(
                    ruleId = "BONE_LENGTH",
                    message = "Bone ${parent.name} -> ${child.name} actual length $actual deviates from expected $expected (error: ${(diff / expected) * 100}%)",
                    severity = ValidationSeverity.ERROR,
                    joint = child
                ))
            }
        }
    }

    private fun validateHeadViewport(pose: SkeletonPose, camera: Camera, width: Float, height: Float, issues: MutableList<ValidationIssue>) {
        camera.project(pose.getJoint(Joint.HEAD_POS), width, height, scratchHeadPoint)
        if (scratchHeadPoint.x < 0f || scratchHeadPoint.x > width || scratchHeadPoint.y < 0f || scratchHeadPoint.y > height) {
            issues.add(ValidationIssue(
                ruleId = "HEAD_VIEWPORT",
                message = "Head is outside camera viewport: projected (x=${scratchHeadPoint.x}, y=${scratchHeadPoint.y}), viewport bounds width=$width, height=$height",
                severity = ValidationSeverity.ERROR,
                joint = Joint.HEAD_POS
            ))
        }
    }

    private fun validateFeetGroundPenetration(pose: SkeletonPose, environment: EnvironmentDefinition, issues: MutableList<ValidationIssue>) {
        if (!config.allowFootGroundPenetration) {
            val groundLevel = environment.ground.level
            for (i in 0 until FEET_JOINTS.size) {
                val joint = FEET_JOINTS[i]
                val pos = pose.getJoint(joint)
                if (pos.y < groundLevel - 0.01f) {
                    issues.add(ValidationIssue(
                        ruleId = "FOOT_GROUND_PENETRATION",
                        message = "Foot joint ${joint.name} penetrates the ground: y=${pos.y} is below ground level=$groundLevel",
                        severity = ValidationSeverity.ERROR,
                        joint = joint
                    ))
                }
            }
        }
    }

    private fun validateHandsSliding(pose: SkeletonPose, previousPose: SkeletonPose?, environment: EnvironmentDefinition, issues: MutableList<ValidationIssue>) {
        if (previousPose != null) {
            val groundLevel = environment.ground.level
            for (i in 0 until HANDS_JOINTS.size) {
                val joint = HANDS_JOINTS[i]
                val currPos = pose.getJoint(joint)
                val prevPos = previousPose.getJoint(joint)

                var isNearGround = kotlin.math.abs(currPos.y - groundLevel) < 2.0f
                var isNearProp = false
                val props = environment.props
                for (j in 0 until props.size) {
                    val prop = props[j]
                    val topY = when (prop) {
                        is BoxProp -> prop.center.y + prop.height * 0.5f
                        is StepProp -> prop.center.y + prop.height * 0.5f
                        is BenchProp -> prop.center.y + prop.height * 0.5f
                        is WallProp -> prop.center.y + prop.height * 0.5f
                    }
                    if (kotlin.math.abs(currPos.y - topY) < 2.0f) {
                        isNearProp = true
                        break
                    }
                }

                if (isNearGround || isNearProp || config.expectedSupportJoints.contains(joint)) {
                    val dx = currPos.x - prevPos.x
                    val dz = currPos.z - prevPos.z
                    val slideDist = kotlin.math.sqrt(dx * dx + dz * dz)
                    if (slideDist > 0.1f) {
                        issues.add(ValidationIssue(
                            ruleId = "HAND_SLIDING",
                            message = "Hand ${joint.name} slid while support was expected: horizontal displacement of $slideDist exceeds threshold 0.1",
                            severity = ValidationSeverity.ERROR,
                            joint = joint
                        ))
                    }
                }
            }
        }
    }

    private fun validateIKConstraints(pose: SkeletonPose, def: SkeletonDefinition, issues: MutableList<ValidationIssue>) {
        validateIKConstraint(pose, Joint.SHOULDER_A, Joint.ELBOW_A, Joint.HAND_A, def.upperArmLength, def.forearmLength, def.armIKConstraint, issues)
        validateIKConstraint(pose, Joint.SHOULDER_P, Joint.ELBOW_P, Joint.HAND_P, def.upperArmLength, def.forearmLength, def.armIKConstraint, issues)
        // For hand-derived straight leg poses, we validate up to 1.0 (100% of maximum physical extension), not the 0.98 solver-specific cap
        validateIKConstraint(pose, Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F, def.thighLength, def.shinLength, def.legIKConstraint, issues, overrideMaxRatio = 1.0f)
        validateIKConstraint(pose, Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B, def.thighLength, def.shinLength, def.legIKConstraint, issues, overrideMaxRatio = 1.0f)
    }

    private fun validateIKConstraint(
        pose: SkeletonPose,
        startJoint: Joint,
        midJoint: Joint,
        endJoint: Joint,
        L1: Float,
        L2: Float,
        constraint: IKConstraint,
        issues: MutableList<ValidationIssue>,
        overrideMaxRatio: Float? = null
    ) {
        val pStart = pose.getJoint(startJoint)
        val pEnd = pose.getJoint(endJoint)

        val dx = pEnd.x - pStart.x
        val dy = pEnd.y - pStart.y
        val dz = pEnd.z - pStart.z
        val actualDist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)

        val maxRatio = overrideMaxRatio ?: constraint.maximumExtensionRatio
        val maxDist = (L1 + L2) * maxRatio
        val minCos = kotlin.math.cos(constraint.minimumFlexionAngle * kotlin.math.PI.toFloat() / 180f)
        val minDist = kotlin.math.sqrt(L1 * L1 + L2 * L2 - 2f * L1 * L2 * minCos)

        if (actualDist > maxDist + 0.1f) {
            issues.add(ValidationIssue(
                ruleId = "IK_CONSTRAINT_LIMIT",
                message = "IK chain ${startJoint.name} -> ${endJoint.name} actual distance $actualDist exceeds max extension limit $maxDist",
                severity = ValidationSeverity.ERROR,
                joint = midJoint
            ))
        }
        if (actualDist < minDist - 0.1f) {
            issues.add(ValidationIssue(
                ruleId = "IK_CONSTRAINT_LIMIT",
                message = "IK chain ${startJoint.name} -> ${endJoint.name} actual distance $actualDist is below min flexion limit $minDist",
                severity = ValidationSeverity.ERROR,
                joint = midJoint
            ))
        }
    }

    private fun validateDynamics(
        pose: SkeletonPose,
        previousPose: SkeletonPose?,
        prePreviousPose: SkeletonPose?,
        deltaTime: Float,
        issues: MutableList<ValidationIssue>
    ) {
        if (previousPose != null) {
            val dt = if (deltaTime > 0f) deltaTime else 0.033f
            for (i in 0 until JOINTS_ARRAY.size) {
                val joint = JOINTS_ARRAY[i]
                val curr = pose.getJoint(joint)
                val prev = previousPose.getJoint(joint)

                val dx = curr.x - prev.x
                val dy = curr.y - prev.y
                val dz = curr.z - prev.z
                val displacement = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)

                if (displacement > config.maxDisplacementThreshold) {
                    issues.add(ValidationIssue(
                        ruleId = "POSITION_DISCONTINUITY",
                        message = "Joint ${joint.name} has frame-to-frame position discontinuity of $displacement (threshold: ${config.maxDisplacementThreshold})",
                        severity = ValidationSeverity.ERROR,
                        joint = joint
                    ))
                }

                val vx = dx / dt
                val vy = dy / dt
                val vz = dz / dt

                if (prePreviousPose != null) {
                    val prevPrev = prePreviousPose.getJoint(joint)
                    val pdx = prev.x - prevPrev.x
                    val pdy = prev.y - prevPrev.y
                    val pdz = prev.z - prevPrev.z
                    val pvx = pdx / dt
                    val pvy = pdy / dt
                    val pvz = pdz / dt

                    val dvx = vx - pvx
                    val dvy = vy - pvy
                    val dvz = vz - pvz
                    val velChange = kotlin.math.sqrt(dvx * dvx + dvy * dvy + dvz * dvz)

                    if (velChange > config.maxVelocityChangeThreshold) {
                        issues.add(ValidationIssue(
                            ruleId = "VELOCITY_DISCONTINUITY",
                            message = "Joint ${joint.name} has frame-to-frame velocity jump of $velChange (threshold: ${config.maxVelocityChangeThreshold})",
                            severity = ValidationSeverity.ERROR,
                            joint = joint
                        ))
                    }

                    val ax = dvx / dt
                    val ay = dvy / dt
                    val az = dvz / dt
                    val accel = kotlin.math.sqrt(ax * ax + ay * ay + az * az)

                    if (accel > config.maxAccelerationThreshold) {
                        issues.add(ValidationIssue(
                            ruleId = "ACCELERATION_SPIKE",
                            message = "Joint ${joint.name} has acceleration spike of $accel (threshold: ${config.maxAccelerationThreshold})",
                            severity = ValidationSeverity.WARNING,
                            joint = joint
                        ))
                    }
                }
            }
        }
    }

    private fun validateSupportPolygon(pose: SkeletonPose, environment: EnvironmentDefinition, issues: MutableList<ValidationIssue>) {
        if (config.isStaticExercise) {
            var minX = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var minZ = Float.MAX_VALUE
            var maxZ = Float.MIN_VALUE
            var contactCount = 0

            val groundLevel = environment.ground.level
            for (i in 0 until CANDIDATE_SUPPORT_JOINTS.size) {
                val joint = CANDIDATE_SUPPORT_JOINTS[i]
                val pos = pose.getJoint(joint)
                var onSupport = kotlin.math.abs(pos.y - groundLevel) < 2.0f
                if (!onSupport) {
                    val props = environment.props
                    for (j in 0 until props.size) {
                        val prop = props[j]
                        val topY = when (prop) {
                            is BoxProp -> prop.center.y + prop.height * 0.5f
                            is StepProp -> prop.center.y + prop.height * 0.5f
                            is BenchProp -> prop.center.y + prop.height * 0.5f
                            is WallProp -> prop.center.y + prop.height * 0.5f
                        }
                        if (kotlin.math.abs(pos.y - topY) < 2.0f) {
                            onSupport = true
                            break
                        }
                    }
                }

                if (onSupport) {
                    if (pos.x < minX) minX = pos.x
                    if (pos.x > maxX) maxX = pos.x
                    if (pos.z < minZ) minZ = pos.z
                    if (pos.z > maxZ) maxZ = pos.z
                    contactCount++
                }
            }

            if (contactCount >= 2) {
                val center = pose.getJoint(Joint.PELVIS)
                val margin = config.supportMargin
                if (center.x < minX - margin || center.x > maxX + margin ||
                    center.z < minZ - margin || center.z > maxZ + margin) {
                    issues.add(ValidationIssue(
                        ruleId = "STATIC_SUPPORT_POLYGON",
                        message = "Body center (Pelvis) at (x=${center.x}, z=${center.z}) is outside support polygon bounding box: X=[$minX, $maxX], Z=[$minZ, $maxZ] with margin $margin",
                        severity = ValidationSeverity.ERROR,
                        joint = Joint.PELVIS
                    ))
                }
            }
        }
    }

    private fun getSignedPerpendicularDeviation2D(a: Vector3, b: Vector3, p: Vector3): Float {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val lenSq = vx * vx + vy * vy
        if (lenSq < 1e-4f) return 0f
        val cross = vx * (p.y - a.y) - vy * (p.x - a.x)
        return cross / kotlin.math.sqrt(lenSq)
    }

    private fun validateBilateralSymmetry(pose: SkeletonPose, issues: MutableList<ValidationIssue>) {
        if (!config.checkBilateralSymmetry) return

        // Validate knees symmetry
        val kneeF = pose.getJoint(Joint.KNEE_F)
        val kneeB = pose.getJoint(Joint.KNEE_B)
        val hipF = pose.getJoint(Joint.HIP_F)
        val hipB = pose.getJoint(Joint.HIP_B)
        val ankleF = pose.getJoint(Joint.ANKLE_F)
        val ankleB = pose.getJoint(Joint.ANKLE_B)

        val devF = getSignedPerpendicularDeviation2D(hipF, ankleF, kneeF)
        val devB = getSignedPerpendicularDeviation2D(hipB, ankleB, kneeB)

        // Check if both knees exist and deviate
        if (kotlin.math.abs(devF) > 0.1f && kotlin.math.abs(devB) > 0.1f) {
            if (devF * devB < 0f) {
                issues.add(ValidationIssue(
                    ruleId = "BILATERAL_SYMMETRY",
                    message = "Knee bilateral symmetry violation: knees bend in opposite directions (devF=$devF, devB=$devB)",
                    severity = ValidationSeverity.ERROR,
                    joint = Joint.KNEE_B
                ))
            } else {
                val diff = kotlin.math.abs(kotlin.math.abs(devF) - kotlin.math.abs(devB))
                if (diff > 5f) { // 5 units of deviation tolerance
                    issues.add(ValidationIssue(
                        ruleId = "BILATERAL_SYMMETRY",
                        message = "Knee bilateral symmetry violation: knee deviations differ in magnitude beyond tolerance (devF=$devF, devB=$devB, diff=$diff)",
                        severity = ValidationSeverity.ERROR,
                        joint = Joint.KNEE_B
                    ))
                }
            }
        }

        // Validate elbows symmetry
        val elbowA = pose.getJoint(Joint.ELBOW_A)
        val elbowP = pose.getJoint(Joint.ELBOW_P)
        val shoulderA = pose.getJoint(Joint.SHOULDER_A)
        val shoulderP = pose.getJoint(Joint.SHOULDER_P)
        val handA = pose.getJoint(Joint.HAND_A)
        val handP = pose.getJoint(Joint.HAND_P)

        val devA = getSignedPerpendicularDeviation2D(shoulderA, handA, elbowA)
        val devP = getSignedPerpendicularDeviation2D(shoulderP, handP, elbowP)

        if (kotlin.math.abs(devA) > 0.1f && kotlin.math.abs(devP) > 0.1f) {
            if (devA * devP < 0f) {
                issues.add(ValidationIssue(
                    ruleId = "BILATERAL_SYMMETRY",
                    message = "Elbow bilateral symmetry violation: elbows bend in opposite directions (devA=$devA, devP=$devP)",
                    severity = ValidationSeverity.ERROR,
                    joint = Joint.ELBOW_P
                ))
            } else {
                val diff = kotlin.math.abs(kotlin.math.abs(devA) - kotlin.math.abs(devP))
                if (diff > 5f) {
                    issues.add(ValidationIssue(
                        ruleId = "BILATERAL_SYMMETRY",
                        message = "Elbow bilateral symmetry violation: elbow deviations differ in magnitude beyond tolerance (devA=$devA, devP=$devP, diff=$diff)",
                        severity = ValidationSeverity.ERROR,
                        joint = Joint.ELBOW_P
                    ))
                }
            }
        }
    }

    private fun validateHandShoulderAlignment(pose: SkeletonPose, issues: MutableList<ValidationIssue>) {
        if (!config.checkHandShoulderAlignment) return
        val shoulderA = pose.getJoint(Joint.SHOULDER_A)
        val handA = pose.getJoint(Joint.HAND_A)

        // Hands should not extend more than 5 units past shoulders in push-up position
        val handForwardOffset = shoulderA.x - handA.x  // Positive = hand past shoulder
        if (handForwardOffset < -5f || handForwardOffset > 15f) {
            issues.add(ValidationIssue(
                ruleId = "HAND_SHOULDER_ALIGNMENT",
                message = "Hands not properly aligned beneath shoulders. Hand offset: $handForwardOffset",
                severity = ValidationSeverity.ERROR,
                joint = Joint.HAND_A
            ))
        }
    }

    private fun validateIkTargetReachability(pose: SkeletonPose, issues: MutableList<ValidationIssue>) {
        if (!config.checkIkTargetReachability) return
        // If the pose's maxIkClampAmount exceeds a small tolerance (e.g., 0.1 units), flag it!
        if (pose.maxIkClampAmount > 0.1f) {
            issues.add(ValidationIssue(
                ruleId = "IK_TARGET_UNREACHABLE",
                message = "IK target is physically unreachable: requested distance was clamped by ${pose.maxIkClampAmount} units.",
                severity = ValidationSeverity.ERROR,
                joint = Joint.HAND_A
            ))
        }
    }

    private fun validateAngularJointLimits(pose: SkeletonPose, def: SkeletonDefinition, issues: MutableList<ValidationIssue>) {
        if (!config.checkAngularJointLimits) return
        // Mirror the solver's angular band on the middle joint (elbow/knee) interior angle.
        // Uses the shared, general limits carried by the skeleton definition — never
        // per-exercise magic numbers.
        validateAngularJointLimit(pose, Joint.SHOULDER_A, Joint.ELBOW_A, Joint.HAND_A, def.armAngularLimits, issues)
        validateAngularJointLimit(pose, Joint.SHOULDER_P, Joint.ELBOW_P, Joint.HAND_P, def.armAngularLimits, issues)
        validateAngularJointLimit(pose, Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F, def.legAngularLimits, issues)
        validateAngularJointLimit(pose, Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B, def.legAngularLimits, issues)
    }

    private fun validateAngularJointLimit(
        pose: SkeletonPose,
        startJoint: Joint,
        midJoint: Joint,
        endJoint: Joint,
        limits: AngularJointLimits,
        issues: MutableList<ValidationIssue>
    ) {
        val pStart = pose.getJoint(startJoint)
        val pMid = pose.getJoint(midJoint)
        val pEnd = pose.getJoint(endJoint)

        val v1x = pStart.x - pMid.x
        val v1y = pStart.y - pMid.y
        val v1z = pStart.z - pMid.z
        val v2x = pEnd.x - pMid.x
        val v2y = pEnd.y - pMid.y
        val v2z = pEnd.z - pMid.z

        val m1 = kotlin.math.sqrt(v1x * v1x + v1y * v1y + v1z * v1z)
        val m2 = kotlin.math.sqrt(v2x * v2x + v2y * v2y + v2z * v2z)
        if (m1 < 1e-6f || m2 < 1e-6f) return

        val dot = (v1x * v2x + v1y * v2y + v1z * v2z) / (m1 * m2)
        val theta = kotlin.math.acos(dot.coerceIn(-1f, 1f)) * 180f / kotlin.math.PI.toFloat()

        // 0.1° tolerance mirrors IK_CONSTRAINT_LIMIT so a limb sitting exactly on the band is
        // not flagged.
        if (theta < limits.minFlexionDegrees - 0.1f) {
            issues.add(ValidationIssue(
                ruleId = "ANGULAR_JOINT_LIMIT",
                message = "Middle joint ${midJoint.name} flexion angle $theta° is below the allowed minimum ${limits.minFlexionDegrees}°",
                severity = ValidationSeverity.ERROR,
                joint = midJoint
            ))
        } else if (theta > limits.maxFlexionDegrees + 0.1f) {
            issues.add(ValidationIssue(
                ruleId = "ANGULAR_JOINT_LIMIT",
                message = "Middle joint ${midJoint.name} flexion angle $theta° exceeds the allowed maximum ${limits.maxFlexionDegrees}°",
                severity = ValidationSeverity.ERROR,
                joint = midJoint
            ))
        }
    }
}
