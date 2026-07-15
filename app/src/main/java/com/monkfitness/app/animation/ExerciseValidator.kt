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
    val checkAngularJointLimits: Boolean = false,
    // UNI-2 / UNI-6 — authored-intent fidelity. Off by default (product validation only checks
    // physical invariants); the engineering reference config switches them on.
    val checkStraightLimbIntent: Boolean = false,
    val checkContactPreservation: Boolean = false,
    val checkPelvisIntent: Boolean = false,
    // UNI-3 — biomechanical hip ROM. Off by default; the engineering reference config switches it on.
    val checkHipRom: Boolean = false
) {
    companion object {
        /**
         * Config used when validating the engineering reference poses. Unlike normal product
         * validation, this turns on IK reachability detection so unreachable (clamped) targets
         * surface as [IK_TARGET_UNREACHABLE] instead of being silently ignored, enables the
         * angular joint-limit rule ([ANGULAR_JOINT_LIMIT]) so impossible joint angles surface,
         * and switches on the intended-shape / ROM cluster (UNI-2/UNI-3/UNI-6): straight-limb
         * intent, contact preservation, pelvis-intent, and over-range hip ROM.
         */
        val ENGINEERING_VALIDATION = ValidatorConfig(
            checkIkTargetReachability = true,
            checkAngularJointLimits = true,
            checkStraightLimbIntent = true,
            checkContactPreservation = true,
            checkPelvisIntent = true,
            checkHipRom = true
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

    // Scratch vectors for the UNI-2/UNI-3/UNI-6 intent + ROM rules (no hot-path allocation).
    private val scratchV1 = Vector3()
    private val scratchV2 = Vector3()
    private val scratchV3 = Vector3()
    // Additional scratch vectors for hip ROM decomposition (flex/ext, abd/add, int/ext rot)
    private val scratchV4 = Vector3()
    private val scratchV5 = Vector3()
    private val scratchV6 = Vector3()
    private val scratchV7 = Vector3()
    private val scratchV8 = Vector3()
    private val scratchV9 = Vector3()

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
            "ANGULAR_JOINT_LIMIT",
            "STRAIGHT_LIMB_INTENT",
            "CONTACT_PRESERVED",
            "PELVIS_INTENT",
            "HIP_ROM_LIMIT"
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

        // UNI-2 / UNI-6 — authored-intent fidelity cluster.
        // Rule 15: a limb authored straight=true must actually come out straight; otherwise the
        // solver silently dropped the intent (e.g. Middle Split's grounded-pelvis straight legs).
        validateStraightLimbIntent(pose, issues)
        // Rule 16: each registered contact's end-effector must land on its authored anchor.
        validateContactPreservation(pose, issues)
        // Rule 17: the global solver must not displace the root beyond the intended tolerance.
        validatePelvisIntent(pose, issues)

        // UNI-3 — biomechanical hip ROM: the acetabular joint must stay within human range.
        validateHipRom(pose, definition, issues)

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
        // The 0.98 ratio is a solver-specific safety cap for dynamic motion, not a physical
        // limit. The finalized pose is validated against the true anatomical maximum (1.0), so a
        // deliberately straight limb (PR-11 full extension) is not falsely flagged as over-reach.
        validateIKConstraint(pose, Joint.SHOULDER_A, Joint.ELBOW_A, Joint.HAND_A, def.upperArmLength, def.forearmLength, def.armIKConstraint, issues, overrideMaxRatio = 1.0f)
        validateIKConstraint(pose, Joint.SHOULDER_P, Joint.ELBOW_P, Joint.HAND_P, def.upperArmLength, def.forearmLength, def.armIKConstraint, issues, overrideMaxRatio = 1.0f)
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

    // ---- UNI-2 / UNI-6: authored-intent fidelity cluster ----

    // A limb authored straight must resolve within this many degrees of a perfectly straight
    // (180°) joint. Inside this band we treat the straight intent as honoured.
    private val STRAIGHT_LIMB_TOLERANCE_DEGREES = 175f
    // An end-effector may land this far (units) from its authored anchor before we flag it.
    private val CONTACT_PRESERVATION_TOLERANCE = 8f
    // The global solver may displace the root this far (units / degrees) before we flag it.
    private val PELVIS_TRANSLATION_TOLERANCE = 30f
    private val PELVIS_ROTATION_TOLERANCE = 20f

    /**
     * UNI-2 — straight-intent fidelity. A limb authored `straight=true` must actually come out
     * straight; otherwise the solver silently dropped the intent (e.g. Middle Split's
     * grounded-pelvis straight legs, which are geometrically impossible and resolve bent). The
     * intent rides on [ContactSpec.straight]; for every straight contact we measure the resolved
     * middle-joint interior angle and flag when it falls outside the straight band.
     */
    private fun validateStraightLimbIntent(pose: SkeletonPose, issues: MutableList<ValidationIssue>) {
        if (!config.checkStraightLimbIntent) return
        for (i in 0 until pose.contacts.size) {
            val spec = pose.contacts[i]
            if (!spec.straight) continue
            // Single-bone contacts (knee/elbow/head on one rigid segment) have no bend angle.
            if (spec.middleJoint == spec.endJoint) continue
            val theta = limbMiddleAngleDegrees(pose, spec.rootJoint, spec.middleJoint, spec.endJoint)
            if (theta < STRAIGHT_LIMB_TOLERANCE_DEGREES) {
                issues.add(ValidationIssue(
                    ruleId = "STRAIGHT_LIMB_INTENT",
                    message = "Limb ${spec.rootJoint.name}->${spec.middleJoint.name}->${spec.endJoint.name} was authored straight but resolved bent (joint angle ${"%.1f".format(theta)}° < ${STRAIGHT_LIMB_TOLERANCE_DEGREES.toInt()}°); straight intent silently dropped.",
                    severity = ValidationSeverity.ERROR,
                    joint = spec.middleJoint
                ))
            }
        }
    }

    /**
     * UNI-6 — contact preservation. Each registered fixed contact owns an authored anchor
     * ([ContactSpec.targetWorld]); the end-effector must land on it (within tolerance). A gross
     * miss means the global solver did not honour the contact it was handed.
     */
    private fun validateContactPreservation(pose: SkeletonPose, issues: MutableList<ValidationIssue>) {
        if (!config.checkContactPreservation) return
        for (i in 0 until pose.contacts.size) {
            val spec = pose.contacts[i]
            val end = pose.getJoint(spec.endJoint)
            val dx = end.x - spec.targetWorld.x
            val dy = end.y - spec.targetWorld.y
            val dz = end.z - spec.targetWorld.z
            val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (dist > CONTACT_PRESERVATION_TOLERANCE) {
                issues.add(ValidationIssue(
                    ruleId = "CONTACT_PRESERVED",
                    message = "Contact on ${spec.endJoint.name} landed ${"%.1f".format(dist)}u from its authored anchor (tolerance ${CONTACT_PRESERVATION_TOLERANCE.toInt()}u); end-effector did not preserve the intended contact.",
                    severity = ValidationSeverity.WARNING,
                    joint = spec.endJoint
                ))
            }
        }
    }

    /**
     * UNI-6 — pelvis intent. The global solver may translate/tilt the root to satisfy contacts,
     * but an unexpectedly large displacement means it fought the authored pose rather than
     * honouring it. Surfaces how far the solver moved the root from its authored transform.
     */
    private fun validatePelvisIntent(pose: SkeletonPose, issues: MutableList<ValidationIssue>) {
        if (!config.checkPelvisIntent) return
        if (pose.rootTranslationDelta > PELVIS_TRANSLATION_TOLERANCE) {
            issues.add(ValidationIssue(
                ruleId = "PELVIS_INTENT",
                message = "Global solver displaced the root/pelvis by ${"%.1f".format(pose.rootTranslationDelta)}u (tolerance ${PELVIS_TRANSLATION_TOLERANCE.toInt()}u) from its authored position.",
                severity = ValidationSeverity.WARNING,
                joint = Joint.PELVIS
            ))
        }
        if (pose.rootRotationDelta > PELVIS_ROTATION_TOLERANCE) {
            issues.add(ValidationIssue(
                ruleId = "PELVIS_INTENT",
                message = "Global solver rotated the root/pelvis by ${"%.1f".format(pose.rootRotationDelta)}° (tolerance ${PELVIS_ROTATION_TOLERANCE.toInt()}°) from its authored orientation.",
                severity = ValidationSeverity.WARNING,
                joint = Joint.PELVIS
            ))
        }
    }

    // ---- UNI-3: biomechanical hip ROM ----

    /**
     * UNI-3 — over-range hips. The acetabular ball-and-socket was previously unbounded (only a
     * shared 30° knee-flexion floor limited the chain), so a pose could flex / abduct the hip
     * beyond human ROM and still validate clean. We measure the femur direction in the pelvis
     * frame and decompose it into:
     * - Sagittal flexion/extension (about Z axis)
     * - Lateral abduction/adduction (about X axis)
     * - Axial internal/external rotation (about femur long axis, derived from knee position)
     *
     * Each limit is independently checked against [HipRomLimits]. If multiple limits are
     * exceeded, all violated limits are reported. A femur swung "through the torso"
     * (~180° from neutral) is caught by maxExcursionDegrees.
     */
    private fun validateHipRom(pose: SkeletonPose, def: SkeletonDefinition, issues: MutableList<ValidationIssue>) {
        if (!config.checkHipRom) return
        val limits = def.hipRomLimits
        val pelvisRot = pose.getJointRotation(Joint.PELVIS)
        // Neutral femur points straight down in the pelvis frame: (0,-1,0).
        for (i in 0 until 2) {
            val hip = if (i == 0) Joint.HIP_F else Joint.HIP_B
            val knee = if (i == 0) Joint.KNEE_F else Joint.KNEE_B
            val hipPos = pose.getJoint(hip)
            val kneePos = pose.getJoint(knee)
            scratchV1.set(kneePos.x - hipPos.x, kneePos.y - hipPos.y, kneePos.z - hipPos.z)
            if (scratchV1.mag() < 1e-5f) continue
            // Femur direction expressed in the pelvis' local frame (pelvis local == world at root).
            SkeletonMath.toLocalDirection(scratchV1, pelvisRot, scratchV2)
            // Total excursion from the neutral down direction. Axis-label-agnostic so a valid
            // extreme pose (deep squat / pike / full split, ~90-120°) passes while an over-range
            // hip (femur through the torso, ~180°) is caught.
            val excursion = SkeletonMath.angleBetweenDegrees(scratchV2, scratchV3.set(0f, -1f, 0f))
            if (excursion > limits.maxExcursionDegrees) {
                issues.add(ValidationIssue(
                    ruleId = "HIP_ROM_LIMIT",
                    message = "Hip ${hip.name} femur deviates ${"%.1f".format(excursion)}° from neutral (max anatomical excursion ${limits.maxExcursionDegrees.toInt()}°); hip range of motion exceeded.",
                    severity = ValidationSeverity.ERROR,
                    joint = hip
                ))
            }

            // ---- Individual anatomical limit checks ----
            // Flexion/extension: projected onto sagittal plane (X-Y), angle from -Y about Z axis.
            // Positive X = flexion, Negative X = extension.
            val flexExtAngle = signedAngleFromNeutral(scratchV2, 0f, -1f, 0f, 1f, 0f, 0f)
            if (flexExtAngle > limits.maxFlexionDegrees) {
                issues.add(ValidationIssue(
                    ruleId = "HIP_ROM_LIMIT",
                    message = "Hip ${hip.name} flexion ${"%.1f".format(flexExtAngle)}° exceeds max ${limits.maxFlexionDegrees.toInt()}°.",
                    severity = ValidationSeverity.ERROR,
                    joint = hip
                ))
            }
            if (flexExtAngle < -limits.maxExtensionDegrees) {
                issues.add(ValidationIssue(
                    ruleId = "HIP_ROM_LIMIT",
                    message = "Hip ${hip.name} extension ${"%.1f".format(-flexExtAngle)}° exceeds max ${limits.maxExtensionDegrees.toInt()}°.",
                    severity = ValidationSeverity.ERROR,
                    joint = hip
                ))
            }

            // Abduction/adduction: projected onto lateral plane (Y-Z), angle from -Y about X axis.
            // Positive Z = abduction, Negative Z = adduction.
            val abdAddAngle = signedAngleFromNeutral(scratchV2, 0f, -1f, 0f, 0f, 0f, 1f)
            if (abdAddAngle > limits.maxAbductionDegrees) {
                issues.add(ValidationIssue(
                    ruleId = "HIP_ROM_LIMIT",
                    message = "Hip ${hip.name} abduction ${"%.1f".format(abdAddAngle)}° exceeds max ${limits.maxAbductionDegrees.toInt()}°.",
                    severity = ValidationSeverity.ERROR,
                    joint = hip
                ))
            }
            if (abdAddAngle < -limits.maxAdductionDegrees) {
                issues.add(ValidationIssue(
                    ruleId = "HIP_ROM_LIMIT",
                    message = "Hip ${hip.name} adduction ${"%.1f".format(-abdAddAngle)}° exceeds max ${limits.maxAdductionDegrees.toInt()}°.",
                    severity = ValidationSeverity.ERROR,
                    joint = hip
                ))
            }

            // Internal/external rotation: twist about the femur's long axis, derived from
            // knee position deviation from the sagittal plane.
            val intExtRot = computeHipInternalExternalRotation(pose, hip, knee, pelvisRot)
            if (intExtRot > limits.maxInternalRotationDegrees) {
                issues.add(ValidationIssue(
                    ruleId = "HIP_ROM_LIMIT",
                    message = "Hip ${hip.name} internal rotation ${"%.1f".format(intExtRot)}° exceeds max ${limits.maxInternalRotationDegrees.toInt()}°.",
                    severity = ValidationSeverity.ERROR,
                    joint = hip
                ))
            }
            if (intExtRot < -limits.maxExternalRotationDegrees) {
                issues.add(ValidationIssue(
                    ruleId = "HIP_ROM_LIMIT",
                    message = "Hip ${hip.name} external rotation ${"%.1f".format(-intExtRot)}° exceeds max ${limits.maxExternalRotationDegrees.toInt()}°.",
                    severity = ValidationSeverity.ERROR,
                    joint = hip
                ))
            }
        }
    }

    /**
     * Computes the signed angle (degrees) of a direction vector in a specified rotation plane,
     * relative to a neutral direction. The sign indicates which side of the neutral direction
     * the vector lies.
     *
     * @param dir The normalized direction vector (femur direction in pelvis frame).
     * @param neutralX, neutralY, neutralZ Components of the neutral direction (e.g., 0,-1,0 for down).
     * @param axisX, axisY, axisZ Components of the rotation axis (e.g., 1,0,0 for Z-axis rotation).
     * @return Signed angle in degrees. Positive values are in the positive direction from neutral.
     */
    private fun signedAngleFromNeutral(
        dir: Vector3,
        neutralX: Float, neutralY: Float, neutralZ: Float,
        axisX: Float, axisY: Float, axisZ: Float
    ): Float {
        // Project dir onto the plane perpendicular to the rotation axis.
        val dotAxis = dir.x * axisX + dir.y * axisY + dir.z * axisZ
        scratchV4.set(dir.x - dotAxis * axisX, dir.y - dotAxis * axisY, dir.z - dotAxis * axisZ)
        val projLen = scratchV4.mag()
        if (projLen < 1e-6f) return 0f
        scratchV4.divide(projLen) // Normalize projected direction.

        // Project neutral onto same plane.
        val dotAxisNeutral = neutralX * axisX + neutralY * axisY + neutralZ * axisZ
        scratchV5.set(neutralX - dotAxisNeutral * axisX, neutralY - dotAxisNeutral * axisY, neutralZ - dotAxisNeutral * axisZ)
        val neutralLen = scratchV5.mag()
        if (neutralLen < 1e-6f) return 0f
        scratchV5.divide(neutralLen) // Normalize projected neutral.

        // Signed angle using cross product to determine direction.
        scratchV6.cross(scratchV4, scratchV5) // scratchV6 = cross(dir_proj, neutral_proj)
        val sign = if (scratchV6.x * axisX + scratchV6.y * axisY + scratchV6.z * axisZ >= 0) 1f else -1f
        val dot = scratchV4.dot(scratchV5)
        val angle = kotlin.math.acos(dot.coerceIn(-1f, 1f)) * 180f / kotlin.math.PI.toFloat()
        return sign * angle
    }

    /**
     * Computes the internal/external rotation angle (degrees) of the hip from knee position.
     * Positive = internal rotation (knee moved toward midline), Negative = external rotation
     * (knee moved away from midline). Derived by projecting the hip-knee vector onto the
     * plane perpendicular to the femur axis and measuring the deviation from the sagittal plane.
     */
    private fun computeHipInternalExternalRotation(
        pose: SkeletonPose,
        hip: Joint,
        knee: Joint,
        pelvisRot: JointRotation
    ): Float {
        val hipPos = pose.getJoint(hip)
        val kneePos = pose.getJoint(knee)

        // Get ankle position to compute tibia direction
        val ankle = if (knee == Joint.KNEE_F) Joint.ANKLE_F else Joint.ANKLE_B
        val anklePos = pose.getJoint(ankle)

        // Tibia direction in pelvis frame
        scratchV1.set(anklePos.x - kneePos.x, anklePos.y - kneePos.y, anklePos.z - kneePos.z)
        if (scratchV1.mag() < 1e-5f) return 0f
        SkeletonMath.toLocalDirection(scratchV1, pelvisRot, scratchV2)

        // Femur direction in pelvis frame (already computed above, reuse via scratchV3)
        scratchV3.set(kneePos.x - hipPos.x, kneePos.y - hipPos.y, kneePos.z - hipPos.z)
        if (scratchV3.mag() < 1e-5f) return 0f
        SkeletonMath.toLocalDirection(scratchV3, pelvisRot, scratchV4)

        // Project tibia onto plane perpendicular to femur
        val dotFemur = scratchV2.x * scratchV4.x + scratchV2.y * scratchV4.y + scratchV2.z * scratchV4.z
        scratchV5.set(
            scratchV2.x - dotFemur * scratchV4.x,
            scratchV2.y - dotFemur * scratchV4.y,
            scratchV2.z - dotFemur * scratchV4.z
        )
        val projLen = scratchV5.mag()
        if (projLen < 1e-6f) return 0f
        scratchV5.divide(projLen) // Normalized perpendicular tibia

        // Reference direction: perpendicular to femur in the sagittal plane (X-Z).
        // For neutral rotation, tibia should be in the sagittal plane (Z direction).
        scratchV6.set(0f, 0f, 1f) // Forward direction in pelvis frame

        // Measure signed angle between tibia projection and forward direction
        scratchV7.cross(scratchV5, scratchV6) // cross = direction of rotation
        val sign = if (scratchV7.y >= 0) 1f else -1f // Y component indicates rotation direction
        val dot = scratchV5.dot(scratchV6)
        val angle = kotlin.math.acos(dot.coerceIn(-1f, 1f)) * 180f / kotlin.math.PI.toFloat()
        return sign * angle
    }

    /** Interior angle (degrees) at [midJoint] of the chain start->mid->end, from world positions. */
    private fun limbMiddleAngleDegrees(pose: SkeletonPose, start: Joint, mid: Joint, end: Joint): Float {
        val pStart = pose.getJoint(start)
        val pMid = pose.getJoint(mid)
        val pEnd = pose.getJoint(end)
        val v1x = pStart.x - pMid.x; val v1y = pStart.y - pMid.y; val v1z = pStart.z - pMid.z
        val v2x = pEnd.x - pMid.x; val v2y = pEnd.y - pMid.y; val v2z = pEnd.z - pMid.z
        val m1 = kotlin.math.sqrt(v1x * v1x + v1y * v1y + v1z * v1z)
        val m2 = kotlin.math.sqrt(v2x * v2x + v2y * v2y + v2z * v2z)
        if (m1 < 1e-6f || m2 < 1e-6f) return 180f
        val dot = (v1x * v2x + v1y * v2y + v1z * v2z) / (m1 * m2)
        return kotlin.math.acos(dot.coerceIn(-1f, 1f)) * 180f / kotlin.math.PI.toFloat()
    }
}
