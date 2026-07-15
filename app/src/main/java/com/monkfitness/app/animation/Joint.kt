package com.monkfitness.app.animation

enum class Joint(val index: Int) {
    PELVIS(0),
    HIP_F(1),
    HIP_B(2),
    KNEE_F(3),
    ANKLE_F(4),
    HEEL_F(5),
    TOE_F(6),
    KNEE_B(7),
    ANKLE_B(8),
    HEEL_B(9),
    TOE_B(10),
    CHEST(11),
    CLAVICLE_A(28),
    SCAPULA_A(29),
    SHOULDER_A(12),
    CLAVICLE_P(30),
    SCAPULA_P(31),
    SHOULDER_P(13),
    ELBOW_A(14),
    HAND_A(15),
    WRIST_A(16),
    PALM_A(17),
    KNUCKLES_A(18),
    FINGERTIPS_A(19),
    ELBOW_P(20),
    HAND_P(21),
    WRIST_P(22),
    PALM_P(23),
    KNUCKLES_P(24),
    FINGERTIPS_P(25),
    NECK_END(26),
    HEAD_POS(27),
    // Lower-spine segment between PELVIS and CHEST. The spine is modelled as two real
    // segments (PELVIS -> LUMBAR -> CHEST) so lumbar/pelvis-tilt and thoracic motion can
    // differ (hip hinge, good morning, cat-cow, thoracic opener). Defaults to a
    // pass-through (coincident with the pelvis, identity rotation) so existing single-bend
    // poses are byte-for-byte unchanged (Issue E).
    LUMBAR(32)
}
