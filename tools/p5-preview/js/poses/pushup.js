/**
 * poses/pushup.js
 *
 * JavaScript port of `PushUpPose.kt` (see
 * `app/src/main/java/com/monkfitness/app/poses/PushUpPose.kt`).
 *
 * Builds a standard push-up SkeletonPose (a plain object mapping Joint id ->
 * Vector3) for a given animation `progress` in [0, 1], where 0 is the "up"
 * (plank/top) position and 1 is the "down" (chest-to-floor) position.
 */
(function (global) {
  "use strict";

  const { Vector3, Joint, solveIK, IKConstraint, SkeletonMath } = global.MonkEngine;
  const { lerp } = SkeletonMath;

  /**
   * @param {{progress:number, definition:object}} context
   * @returns {Object<string, Vector3>} joints keyed by Joint id
   */
  function buildPushUpPose(context) {
    const { progress, definition } = context;

    // progress 0 (up) to 1 (down)
    const height = lerp(60, 25, progress);
    const pelvis = new Vector3(60, height, 0);
    const chest = pelvis.add(new Vector3(-definition.torsoLength, 0, 0));

    const hipF = pelvis.add(new Vector3(0, 0, definition.hipWidth));
    const hipB = pelvis.add(new Vector3(0, 0, -definition.hipWidth));

    const totalLegLen = definition.thighLength + definition.shinLength;
    const toeF = new Vector3(60 + totalLegLen, 0, definition.hipWidth);
    const toeB = new Vector3(60 + totalLegLen, 0, -definition.hipWidth);

    const legF = solveIK(hipF, toeF, definition.thighLength, definition.shinLength, new Vector3(0, 1, 0), IKConstraint.LegConstraint);
    const legB = solveIK(hipB, toeB, definition.thighLength, definition.shinLength, new Vector3(0, 1, 0), IKConstraint.LegConstraint);

    const shoulderA = chest.add(new Vector3(0, 0, definition.shoulderWidth));
    const shoulderP = chest.add(new Vector3(0, 0, -definition.shoulderWidth));

    const handA = chest.add(new Vector3(0, -height, definition.shoulderWidth * 1.5));
    const handP = chest.add(new Vector3(0, -height, -definition.shoulderWidth * 1.5));

    const armA = solveIK(shoulderA, handA, definition.upperArmLength, definition.forearmLength, new Vector3(1, 0, 1), IKConstraint.ArmConstraint);
    const armP = solveIK(shoulderP, handP, definition.upperArmLength, definition.forearmLength, new Vector3(1, 0, -1), IKConstraint.ArmConstraint);

    const headDir = new Vector3(-1, 0.2, 0).normalize();
    const neckEnd = chest.add(headDir.scale(definition.neckLength));
    const headPos = chest.add(headDir.scale(definition.neckLength + 18));

    return {
      [Joint.PELVIS]: pelvis,
      [Joint.HIP_F]: hipF,
      [Joint.HIP_B]: hipB,
      [Joint.KNEE_F]: legF.joint,
      [Joint.ANKLE_F]: legF.end,
      [Joint.TOE_F]: toeF,
      [Joint.KNEE_B]: legB.joint,
      [Joint.ANKLE_B]: legB.end,
      [Joint.TOE_B]: toeB,
      [Joint.CHEST]: chest,
      [Joint.SHOULDER_A]: shoulderA,
      [Joint.SHOULDER_P]: shoulderP,
      [Joint.ELBOW_A]: armA.joint,
      [Joint.HAND_A]: armA.end,
      [Joint.ELBOW_P]: armP.joint,
      [Joint.HAND_P]: armP.end,
      [Joint.NECK_END]: neckEnd,
      [Joint.HEAD_POS]: headPos,
    };
  }

  global.MonkPoses = global.MonkPoses || {};
  global.MonkPoses.buildPushUpPose = buildPushUpPose;
})(typeof window !== "undefined" ? window : globalThis);
