/**
 * Monk Fitness — Push-Up animation preview for p5.js Web Editor.
 *
 * This file is a single-file JavaScript port of the app's animation engine
 * (see app/src/main/java/com/monkfitness/app/animation/*.kt) and the
 * push-up pose builder (app/src/main/java/com/monkfitness/app/poses/PushUpPose.kt),
 * merged with a small p5.js sketch so it can be pasted directly into
 * https://editor.p5js.org/ as sketch.js and run with zero setup.
 *
 * NOTE: p5.js reserves several global identifiers in global mode
 * (width, height, color, focus, frameCount, key, keyCode, mouseX/Y, ...).
 * Engine code below avoids those names (e.g. Camera.project takes
 * `canvasW`/`canvasH` instead of `width`/`height`, colors are built with
 * `makeColor()` instead of a variable named `color`, etc).
 */

'use strict';

// ---------------------------------------------------------------------------
// Vector3 (ported from SkeletonMath.kt)
// ---------------------------------------------------------------------------
class Vector3 {
  constructor(x = 0, y = 0, z = 0) {
    this.x = x;
    this.y = y;
    this.z = z;
  }

  set(x, y, z) {
    if (x instanceof Vector3) {
      this.x = x.x;
      this.y = x.y;
      this.z = x.z;
    } else {
      this.x = x;
      this.y = y;
      this.z = z;
    }
    return this;
  }

  add(v) {
    this.x += v.x;
    this.y += v.y;
    this.z += v.z;
    return this;
  }

  subtract(v) {
    this.x -= v.x;
    this.y -= v.y;
    this.z -= v.z;
    return this;
  }

  multiply(s) {
    this.x *= s;
    this.y *= s;
    this.z *= s;
    return this;
  }

  divide(s) {
    this.x /= s;
    this.y /= s;
    this.z /= s;
    return this;
  }

  mag() {
    return Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
  }

  normalize() {
    const m = this.mag();
    if (m > 1e-6) {
      return this.divide(m);
    }
    return this.set(0, 0, 0);
  }

  normalizedCopy() {
    return this.copy().normalize();
  }

  dot(v) {
    return this.x * v.x + this.y * v.y + this.z * v.z;
  }

  cross(v, result = new Vector3()) {
    const rx = this.y * v.z - this.z * v.y;
    const ry = this.z * v.x - this.x * v.z;
    const rz = this.x * v.y - this.y * v.x;
    return result.set(rx, ry, rz);
  }

  copy() {
    return new Vector3(this.x, this.y, this.z);
  }
}

function vSub(a, b, result = new Vector3()) {
  return result.set(a.x - b.x, a.y - b.y, a.z - b.z);
}

// ---------------------------------------------------------------------------
// IK constraints + math (ported from SkeletonMath.kt)
// ---------------------------------------------------------------------------
const IKConstraint = {
  ArmConstraint: { minimumFlexionAngle: 30, maximumExtensionRatio: 0.95 },
  LegConstraint: { minimumFlexionAngle: 5, maximumExtensionRatio: 0.98 }
};

function lerpNum(a, b, t) {
  return a + (b - a) * t;
}

// Rodrigues rotation: rotate v around unit axis by ang (radians)
function rotAround(v, axis, ang, result = new Vector3()) {
  let kx, ky, kz;
  const m = Math.sqrt(axis.x * axis.x + axis.y * axis.y + axis.z * axis.z);
  if (m > 1e-6) {
    kx = axis.x / m;
    ky = axis.y / m;
    kz = axis.z / m;
  } else {
    kx = 0;
    ky = 0;
    kz = 1;
  }

  const c = Math.cos(ang);
  const s = Math.sin(ang);
  const d = v.x * kx + v.y * ky + v.z * kz;

  const cx = ky * v.z - kz * v.y;
  const cy = kz * v.x - kx * v.z;
  const cz = kx * v.y - ky * v.x;

  const omc = 1 - c;
  return result.set(
    v.x * c + cx * s + kx * d * omc,
    v.y * c + cy * s + ky * d * omc,
    v.z * c + cz * s + kz * d * omc
  );
}

// Hard-locked 2-bone analytical IK solver with biological clamps
function solveIK(root, target, l1, l2, pole, constraint, result = { joint: new Vector3(), end: new Vector3() }) {
  const dx = target.x - root.x;
  const dy = target.y - root.y;
  const dz = target.z - root.z;
  const dMag = Math.sqrt(dx * dx + dy * dy + dz * dz);

  const maxDist = (l1 + l2) * constraint.maximumExtensionRatio;
  const minCos = Math.cos((constraint.minimumFlexionAngle * Math.PI) / 180);
  const minDist = Math.sqrt(l1 * l1 + l2 * l2 - 2 * l1 * l2 * minCos);

  const dist = Math.min(Math.max(dMag, minDist), maxDist);

  let dirX, dirY, dirZ;
  if (dMag > 1e-6) {
    dirX = dx / dMag;
    dirY = dy / dMag;
    dirZ = dz / dMag;
  } else {
    dirX = 1;
    dirY = 0;
    dirZ = 0;
  }

  result.end.set(root.x + dirX * dist, root.y + dirY * dist, root.z + dirZ * dist);

  const a = (dist * dist + l1 * l1 - l2 * l2) / (2 * dist);
  const h = Math.sqrt(Math.max(l1 * l1 - a * a, 0));

  const pDotDir = pole.x * dirX + pole.y * dirY + pole.z * dirZ;
  let px = pole.x - dirX * pDotDir;
  let py = pole.y - dirY * pDotDir;
  let pz = pole.z - dirZ * pDotDir;

  let pMag = Math.sqrt(px * px + py * py + pz * pz);
  if (pMag < 1e-4) {
    const wdDotDir = dirY;
    px = 0 - dirX * wdDotDir;
    py = 1 - dirY * wdDotDir;
    pz = 0 - dirZ * wdDotDir;
    const pMag2 = Math.sqrt(px * px + py * py + pz * pz);
    if (pMag2 > 1e-6) {
      px /= pMag2;
      py /= pMag2;
      pz /= pMag2;
    } else {
      px = 0;
      py = 0;
      pz = 1;
    }
  } else {
    px /= pMag;
    py /= pMag;
    pz /= pMag;
  }

  result.joint.set(root.x + dirX * a + px * h, root.y + dirY * a + py * h, root.z + dirZ * a + pz * h);

  return result;
}

// ---------------------------------------------------------------------------
// Joint map (ported from Joint.kt)
// ---------------------------------------------------------------------------
const JOINT_NAMES = [
  'PELVIS', 'HIP_F', 'HIP_B', 'KNEE_F', 'ANKLE_F', 'HEEL_F', 'TOE_F',
  'KNEE_B', 'ANKLE_B', 'HEEL_B', 'TOE_B', 'CHEST', 'SHOULDER_A', 'SHOULDER_P',
  'ELBOW_A', 'HAND_A', 'WRIST_A', 'PALM_A', 'KNUCKLES_A', 'FINGERTIPS_A',
  'ELBOW_P', 'HAND_P', 'WRIST_P', 'PALM_P', 'KNUCKLES_P', 'FINGERTIPS_P',
  'NECK_END', 'HEAD_POS'
];
const Joint = {};
JOINT_NAMES.forEach((name, index) => {
  Joint[name] = { name, index };
});

// ---------------------------------------------------------------------------
// SkeletonNode / JointRotation (ported from SkeletonNode.kt)
// ---------------------------------------------------------------------------
class JointRotation {
  constructor(axis = new Vector3(0, 0, 1), angle = 0) {
    this.axis = axis;
    this.angle = angle;
  }

  setRotation(axis, angle) {
    this.axis = axis;
    this.angle = angle;
  }
}

class SkeletonNode {
  constructor(joint) {
    this.joint = joint;
    this.parent = null;
    this.localPosition = new Vector3(0, 0, 0);
    this.localRotation = new JointRotation();
    this.children = [];
    this.worldPosition = new Vector3(0, 0, 0);
    this.worldRotation = new JointRotation();
  }

  addChild(node) {
    node.parent = this;
    this.children.push(node);
    return node;
  }

  updateWorldTransforms(parentWorldPos, parentWorldRotation) {
    const rotatedPos =
      parentWorldRotation.angle !== 0
        ? rotAround(this.localPosition, parentWorldRotation.axis, parentWorldRotation.angle)
        : this.localPosition;

    this.worldPosition = new Vector3(parentWorldPos.x, parentWorldPos.y, parentWorldPos.z).add(rotatedPos);

    this.worldRotation.axis = parentWorldRotation.axis;
    this.worldRotation.angle = parentWorldRotation.angle + this.localRotation.angle;

    for (const child of this.children) {
      child.updateWorldTransforms(this.worldPosition, this.worldRotation);
    }
  }

  flatten(target) {
    target.setJoint(this.joint, this.worldPosition);
    for (const child of this.children) {
      child.flatten(target);
    }
  }
}

// ---------------------------------------------------------------------------
// SkeletonPose (ported from PoseDefinition.kt)
// ---------------------------------------------------------------------------
const IDENTITY_ROTATION = new JointRotation();
const ZERO_VECTOR = new Vector3(0, 0, 0);

class SkeletonPose {
  constructor() {
    this.joints = JOINT_NAMES.map(() => new Vector3());
    this.roots = [];
  }

  getJoint(id) {
    return this.joints[id.index];
  }

  setJoint(id, v) {
    this.joints[id.index].set(v);
  }

  copyFrom(other) {
    for (let i = 0; i < this.joints.length; i++) {
      this.joints[i].set(other.joints[i]);
    }
    this.roots = other.roots;
  }

  static fromHierarchy(roots, targetPose) {
    for (const root of roots) {
      root.updateWorldTransforms(ZERO_VECTOR, IDENTITY_ROTATION);
      root.flatten(targetPose);
    }
    targetPose.roots = roots;
    return targetPose;
  }
}

// ---------------------------------------------------------------------------
// Foot / Hand definitions (ported from FootDefinition.kt / HandDefinition.kt)
// ---------------------------------------------------------------------------
class FootDefinition {
  constructor(footLength, heelRatio = 0.29, toeRatio = 0.71, ankleHeight = 15) {
    this.footLength = footLength;
    this.heelRatio = heelRatio;
    this.toeRatio = toeRatio;
    this.ankleHeight = ankleHeight;
    this.minPitch = (-45 * Math.PI) / 180;
    this.maxPitch = (45 * Math.PI) / 180;
  }

  computeHeelToe(ankle, forward, outHeel, outToe) {
    const dir = forward.normalizedCopy();
    outHeel.set(dir).multiply(-(this.footLength * this.heelRatio)).add(ankle);
    outToe.set(dir).multiply(this.footLength * this.toeRatio).add(ankle);
  }
}

class HandJoints {
  constructor() {
    this.wrist = new Vector3();
    this.palm = new Vector3();
    this.knuckles = new Vector3();
    this.fingertips = new Vector3();
  }
}

class HandDefinition {
  constructor(palmLength = 12, fingerLength = 10, handWidth = 10) {
    this.palmLength = palmLength;
    this.fingerLength = fingerLength;
    this.handWidth = handWidth;
  }

  computeHandJoints(wrist, direction, result) {
    const dir = direction.normalizedCopy();
    result.wrist.set(wrist);
    result.palm.set(dir).multiply(this.palmLength * 0.5).add(wrist);
    result.knuckles.set(dir).multiply(this.palmLength).add(wrist);
    result.fingertips.set(dir).multiply(this.palmLength + this.fingerLength).add(wrist);
  }
}

// ---------------------------------------------------------------------------
// SkeletonDefinition (ported from SkeletonDefinition.kt)
// ---------------------------------------------------------------------------
class HumanSkeletonDefinition {
  constructor() {
    this.torsoLength = 120;
    this.neckLength = 18;
    this.thighLength = 112;
    this.shinLength = 98;
    this.footLength = 35;
    this.foot = new FootDefinition(this.footLength);
    this.upperArmLength = 64;
    this.forearmLength = 82;
    this.hand = new HandDefinition();
    this.shoulderWidth = 42;
    this.hipWidth = 22;
    this.armIKConstraint = IKConstraint.ArmConstraint;
    this.legIKConstraint = IKConstraint.LegConstraint;
  }
}

// ---------------------------------------------------------------------------
// SkeletonStyle + Bone list (ported from SkeletonStyle.kt / SkeletonEngine.kt)
// ---------------------------------------------------------------------------
const SkeletonStyle = {
  headRadius: 18,
  jointRadius: 9,
  upperArmThickness: 16,
  forearmThickness: 13,
  thighThickness: 21,
  shinThickness: 16,
  handThickness: 12,
  neckThickness: 12,
  torsoChestDepth: 22,
  torsoHipDepth: 12,
  outlineWidth: 2,
  shadowRadiusX: 30,
  shadowRadiusY: 9,
  primaryColorHex: [0x64, 0xf0, 0xdc],
  secondaryColorHex: [0xb4, 0xc8, 0xdc],
  farColorHex: [0x19, 0x23, 0x37]
};

function buildBones(style) {
  return [
    // Background limbs (Right side)
    { a: Joint.HIP_B, b: Joint.KNEE_B, thickness: style.thighThickness * 0.8, colorM: 0.62 },
    { a: Joint.KNEE_B, b: Joint.ANKLE_B, thickness: style.shinThickness * 0.8, colorM: 0.62 },
    { a: Joint.ANKLE_B, b: Joint.HEEL_B, thickness: style.shinThickness * 0.7, colorM: 0.62 },
    { a: Joint.ANKLE_B, b: Joint.TOE_B, thickness: style.shinThickness * 0.7, colorM: 0.62 },
    { a: Joint.HEEL_B, b: Joint.TOE_B, thickness: 10, colorM: 0.62 },

    { a: Joint.SHOULDER_P, b: Joint.ELBOW_P, thickness: style.upperArmThickness * 0.85, colorM: 0.8 },
    { a: Joint.ELBOW_P, b: Joint.WRIST_P, thickness: style.forearmThickness * 0.85, colorM: 0.8 },
    { a: Joint.WRIST_P, b: Joint.PALM_P, thickness: style.handThickness * 0.85, colorM: 0.8 },
    { a: Joint.PALM_P, b: Joint.FINGERTIPS_P, thickness: style.handThickness * 0.8 * 0.85, colorM: 0.8 },

    // Foreground limbs (Left side)
    { a: Joint.HIP_F, b: Joint.KNEE_F, thickness: style.thighThickness, colorM: 1.0 },
    { a: Joint.KNEE_F, b: Joint.ANKLE_F, thickness: style.shinThickness, colorM: 1.0 },
    { a: Joint.ANKLE_F, b: Joint.HEEL_F, thickness: style.shinThickness * 0.8, colorM: 1.0 },
    { a: Joint.ANKLE_F, b: Joint.TOE_F, thickness: style.shinThickness * 0.8, colorM: 1.0 },
    { a: Joint.HEEL_F, b: Joint.TOE_F, thickness: 12, colorM: 1.0 },

    { a: Joint.SHOULDER_A, b: Joint.ELBOW_A, thickness: style.upperArmThickness, colorM: 1.05 },
    { a: Joint.ELBOW_A, b: Joint.WRIST_A, thickness: style.forearmThickness, colorM: 1.05 },
    { a: Joint.WRIST_A, b: Joint.PALM_A, thickness: style.handThickness, colorM: 1.05 },
    { a: Joint.PALM_A, b: Joint.FINGERTIPS_A, thickness: style.handThickness * 0.8, colorM: 1.05 },

    // Spine/Neck
    { a: Joint.PELVIS, b: Joint.CHEST, thickness: 17, colorM: 0.95 },
    { a: Joint.CHEST, b: Joint.NECK_END, thickness: style.neckThickness, colorM: 0.95 }
  ];
}

// ---------------------------------------------------------------------------
// SkeletonPoseFinalizer (ported from SkeletonPoseFinalizer.kt)
// ---------------------------------------------------------------------------
class SkeletonPoseFinalizer {
  constructor(definition) {
    this.definition = definition;
    this.outputPose = new SkeletonPose();
    this.handJointsBuffer = new HandJoints();
  }

  finalize(pose) {
    this.outputPose.copyFrom(pose);

    this.adjustFootOrientation(this.outputPose, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F);
    this.adjustFootOrientation(this.outputPose, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B);

    this.adjustHandOrientation(this.outputPose, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A);
    this.adjustHandOrientation(this.outputPose, Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P);

    return this.outputPose;
  }

  adjustHandOrientation(pose, elbowId, handId, wristId, palmId, knucklesId, fingertipsId) {
    const elbow = pose.getJoint(elbowId);
    const hand = pose.getJoint(handId);

    const wrist = pose.getJoint(wristId);
    wrist.set(hand);

    const dir = vSub(wrist, elbow).normalize();

    const handDef = this.definition.hand;
    handDef.computeHandJoints(wrist, dir, this.handJointsBuffer);

    pose.getJoint(palmId).set(this.handJointsBuffer.palm);
    pose.getJoint(knucklesId).set(this.handJointsBuffer.knuckles);
    pose.getJoint(fingertipsId).set(this.handJointsBuffer.fingertips);
  }

  adjustFootOrientation(pose, kneeId, ankleId, heelId, toeId) {
    const knee = pose.getJoint(kneeId);
    const ankle = pose.getJoint(ankleId);
    const providedToe = pose.getJoint(toeId);

    const shank = vSub(ankle, knee).normalize();

    let forwardHint;
    if (vSub(providedToe, ankle).mag() > 1e-3) {
      forwardHint = vSub(providedToe, ankle).normalize();
    } else {
      forwardHint = new Vector3(1, 0, 0);
    }

    let footDir = shank.copy().multiply(forwardHint.dot(shank));
    footDir = new Vector3(forwardHint.x - footDir.x, forwardHint.y - footDir.y, forwardHint.z - footDir.z);

    if (footDir.mag() < 1e-3) {
      const worldDown = new Vector3(0, -1, 0);
      footDir = shank.copy().multiply(worldDown.dot(shank));
      footDir = new Vector3(worldDown.x - footDir.x, worldDown.y - footDir.y, worldDown.z - footDir.z);
    }
    footDir.normalize();

    const pitch = Math.atan2(footDir.y, Math.sqrt(footDir.x * footDir.x + footDir.z * footDir.z));
    const clampedPitch = Math.min(Math.max(pitch, this.definition.foot.minPitch), this.definition.foot.maxPitch);

    if (Math.abs(pitch - clampedPitch) > 1e-3) {
      const horizontalDir = new Vector3(footDir.x, 0, footDir.z).normalize();
      footDir = new Vector3(
        horizontalDir.x * Math.cos(clampedPitch),
        Math.sin(clampedPitch),
        horizontalDir.z * Math.cos(clampedPitch)
      );
    }

    this.definition.foot.computeHeelToe(ankle, footDir, pose.getJoint(heelId), pose.getJoint(toeId));
  }
}

// ---------------------------------------------------------------------------
// PushUpPose (ported from poses/PushUpPose.kt)
// ---------------------------------------------------------------------------
class PushUpPose {
  constructor() {
    this.metadata = {
      camera: { defaultYaw: 1.19, defaultPitch: 0.22, defaultZoom: 1.3 },
      durationSeconds: 2.5,
      loopMode: 'LOOP'
    };

    this.roots = null;

    this.ankleF = null;
    this.kneeF = null;
    this.hipF = null;
    this.pelvis = null;
    this.chest = null;
    this.neck = null;
    this.head = null;
    this.shoulderA = null;
    this.elbowA = null;
    this.handA = null;
    this.shoulderP = null;
    this.elbowP = null;
    this.handP = null;
    this.hipB = null;
    this.kneeB = null;
    this.ankleB = null;

    this.jointsBuffer = new SkeletonPose();

    this.armAIK = { joint: new Vector3(), end: new Vector3() };
    this.armPIK = { joint: new Vector3(), end: new Vector3() };
  }

  ensureHierarchy() {
    if (this.roots !== null) return;

    this.ankleF = new SkeletonNode(Joint.ANKLE_F);
    this.kneeF = this.ankleF.addChild(new SkeletonNode(Joint.KNEE_F));
    this.hipF = this.kneeF.addChild(new SkeletonNode(Joint.HIP_F));

    this.pelvis = this.hipF.addChild(new SkeletonNode(Joint.PELVIS));
    this.chest = this.pelvis.addChild(new SkeletonNode(Joint.CHEST));

    this.neck = this.chest.addChild(new SkeletonNode(Joint.NECK_END));
    this.head = this.neck.addChild(new SkeletonNode(Joint.HEAD_POS));

    this.shoulderA = this.chest.addChild(new SkeletonNode(Joint.SHOULDER_A));
    this.elbowA = this.shoulderA.addChild(new SkeletonNode(Joint.ELBOW_A));
    this.handA = this.elbowA.addChild(new SkeletonNode(Joint.HAND_A));

    this.shoulderP = this.chest.addChild(new SkeletonNode(Joint.SHOULDER_P));
    this.elbowP = this.shoulderP.addChild(new SkeletonNode(Joint.ELBOW_P));
    this.handP = this.elbowP.addChild(new SkeletonNode(Joint.HAND_P));

    this.hipB = this.pelvis.addChild(new SkeletonNode(Joint.HIP_B));
    this.kneeB = this.hipB.addChild(new SkeletonNode(Joint.KNEE_B));
    this.ankleB = this.kneeB.addChild(new SkeletonNode(Joint.ANKLE_B));

    this.roots = [this.ankleF];
  }

  build(context) {
    const progress = context.progress;
    const def = context.definition;
    this.ensureHierarchy();

    // 1. Driving values
    const puHeight = lerpNum(60, 25, progress);
    const totalLegLen = def.shinLength + def.thighLength;
    const ankleHeight = def.foot.ankleHeight;
    const drivingHeight = Math.max(puHeight - ankleHeight, 0);
    const theta = Math.asin(Math.min(Math.max(drivingHeight / totalLegLen, -1), 1));
    const horizontalDist = totalLegLen * Math.cos(theta);
    const ankleX = 60 + horizontalDist;

    // 2. Local Transforms
    this.ankleF.localPosition = new Vector3(ankleX, ankleHeight, -def.hipWidth);
    this.ankleF.localRotation.setRotation(new Vector3(0, 0, 1), -theta);

    this.kneeF.localPosition = new Vector3(-def.shinLength, 0, 0);
    this.hipF.localPosition = new Vector3(-def.thighLength, 0, 0);

    this.pelvis.localPosition = new Vector3(0, 0, def.hipWidth);
    this.chest.localPosition = new Vector3(-def.torsoLength, 0, 0);

    const headDir = new Vector3(-1, 0.2, 0).normalize();
    this.neck.localPosition = headDir.copy().multiply(def.neckLength);
    this.head.localPosition = headDir.copy().multiply(18);

    this.hipB.localPosition = new Vector3(0, 0, def.hipWidth);
    this.kneeB.localPosition = new Vector3(def.thighLength, 0, 0);
    this.ankleB.localPosition = new Vector3(def.shinLength, 0, 0);

    // 3. Preliminary FK pass
    this.roots.forEach((root) => root.updateWorldTransforms(new Vector3(0, 0, 0), new JointRotation()));

    const chestW = this.chest.worldPosition;
    const shoulderAW = rotAround(new Vector3(0, 0, -def.shoulderWidth), new Vector3(0, 0, 1), this.chest.worldRotation.angle).add(chestW);
    const shoulderPW = rotAround(new Vector3(0, 0, def.shoulderWidth), new Vector3(0, 0, 1), this.chest.worldRotation.angle).add(chestW);

    // 4. IK
    const targetHandA = new Vector3(chestW.x, 0, -def.shoulderWidth * 1.5);
    const armA = solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, new Vector3(1, 0, -1), def.armIKConstraint, this.armAIK);

    const targetHandP = new Vector3(chestW.x, 0, def.shoulderWidth * 1.5);
    const armP = solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, new Vector3(1, 0, 1), def.armIKConstraint, this.armPIK);

    // 5. Hierarchy Update (Transform IK to local space)
    this.shoulderA.localPosition.set(0, 0, -def.shoulderWidth);
    rotAround(vSub(armA.joint, shoulderAW), new Vector3(0, 0, 1), -this.chest.worldRotation.angle, this.elbowA.localPosition);
    rotAround(vSub(armA.end, armA.joint), new Vector3(0, 0, 1), -this.chest.worldRotation.angle, this.handA.localPosition);

    this.shoulderP.localPosition.set(0, 0, def.shoulderWidth);
    rotAround(vSub(armP.joint, shoulderPW), new Vector3(0, 0, 1), -this.chest.worldRotation.angle, this.elbowP.localPosition);
    rotAround(vSub(armP.end, armP.joint), new Vector3(0, 0, 1), -this.chest.worldRotation.angle, this.handP.localPosition);

    // 6. Final Pass
    SkeletonPose.fromHierarchy(this.roots, this.jointsBuffer);

    // 7. Stable anchors (Toes)
    this.jointsBuffer.getJoint(Joint.TOE_F).set(ankleX + 10, 0, -def.hipWidth);
    this.jointsBuffer.getJoint(Joint.TOE_B).set(ankleX + 10, 0, def.hipWidth);

    return this.jointsBuffer;
  }
}

// ---------------------------------------------------------------------------
// Camera projection (ported from Camera.kt)
// ---------------------------------------------------------------------------
class SkeletonCamera {
  constructor(yaw = 1.19, pitch = 0.22, zoom = 1.3, focalLength = 1000, centerX = 0.5, centerY = 0.7) {
    this.yaw = yaw;
    this.pitch = pitch;
    this.zoom = zoom;
    this.focalLength = focalLength;
    this.centerX = centerX;
    this.centerY = centerY;
  }

  project(v, canvasW, canvasH) {
    const cy = Math.cos(this.yaw);
    const sy = Math.sin(this.yaw);
    const xr = v.x * cy + v.z * sy;
    const zr = -v.x * sy + v.z * cy;

    const cp = Math.cos(this.pitch);
    const sp = Math.sin(this.pitch);
    const y2 = v.y * cp + zr * sp;
    const z2 = zr * cp - v.y * sp;

    const sc = this.focalLength / (this.focalLength + z2);

    return {
      x: canvasW * this.centerX + xr * sc * this.zoom,
      y: canvasH * this.centerY - y2 * sc * this.zoom,
      depth: z2,
      perspectiveScale: sc
    };
  }
}

// ---------------------------------------------------------------------------
// p5.js sketch
// ---------------------------------------------------------------------------
const skeletonDefinition = new HumanSkeletonDefinition();
const pushUpPose = new PushUpPose();
const finalizer = new SkeletonPoseFinalizer(skeletonDefinition);
const skeletonCamera = new SkeletonCamera(
  pushUpPose.metadata.camera.defaultYaw,
  pushUpPose.metadata.camera.defaultPitch,
  pushUpPose.metadata.camera.defaultZoom
);
const bones = buildBones(SkeletonStyle);

// Mirrors AnimationController's LOOP (non-alternating) behaviour:
// progress bounces 0 -> 1 -> 0 using an ease-in-out curve.
function easeInOut(t) {
  const c = Math.min(Math.max(t, 0), 1);
  return c < 0.5 ? 2 * c * c : 1 - Math.pow(-2 * c + 2, 2) / 2;
}

function makeColor(rgb, alpha = 255) {
  return color(rgb[0], rgb[1], rgb[2], alpha);
}

function lerpColor3(c1, c2, t) {
  return [lerpNum(c1[0], c2[0], t), lerpNum(c1[1], c2[1], t), lerpNum(c1[2], c2[2], t)];
}

function getZColor(depth, isForeground) {
  const t = Math.min(Math.max((170 - depth) / 340, 0), 1);
  const baseC = lerpColor3(SkeletonStyle.farColorHex, SkeletonStyle.secondaryColorHex, t);
  if (isForeground) {
    return lerpColor3(baseC, SkeletonStyle.primaryColorHex, 0.3);
  }
  return baseC;
}

function setup() {
  createCanvas(720, 540);
  colorMode(RGB, 255);
}

function draw() {
  background(10, 12, 18);

  const canvasW = width;
  const canvasH = height;

  // Drive the same 0 -> 1 -> 0 loop the app uses (durationSeconds * 1000 ms cycle)
  const cycleMs = pushUpPose.metadata.durationSeconds * 1000;
  const t = (millis() % cycleMs) / cycleMs; // 0..1 over one full cycle
  const half = t < 0.5 ? t * 2 : (1 - t) * 2; // 0..1..0 across the cycle
  const progress = easeInOut(half);

  const context = {
    progress,
    definition: skeletonDefinition
  };

  const rawPose = pushUpPose.build(context);
  const pose = finalizer.finalize(rawPose);

  // Project all joints to screen space
  const projected = {};
  JOINT_NAMES.forEach((name) => {
    projected[name] = skeletonCamera.project(pose.getJoint(Joint[name]), canvasW, canvasH);
  });

  // Ground grid (simple reference plane)
  stroke(58, 68, 92, 90);
  strokeWeight(1);
  for (let gx = -260; gx <= 260; gx += 65) {
    const p1 = skeletonCamera.project(new Vector3(gx, 0, -170), canvasW, canvasH);
    const p2 = skeletonCamera.project(new Vector3(gx, 0, 170), canvasW, canvasH);
    line(p1.x, p1.y, p2.x, p2.y);
  }
  for (let gz = -170; gz <= 170; gz += 65) {
    const p1 = skeletonCamera.project(new Vector3(-260, 0, gz), canvasW, canvasH);
    const p2 = skeletonCamera.project(new Vector3(260, 0, gz), canvasW, canvasH);
    line(p1.x, p1.y, p2.x, p2.y);
  }

  // Ground shadows under contact joints
  noStroke();
  fill(5, 8, 12, 150);
  // Matches SkeletonProjector.kt's shadowJoints list exactly (only the
  // passive/back hand casts a shadow there too, by original design).
  const shadowJointNames = ['TOE_F', 'TOE_B', 'HEEL_F', 'HEEL_B', 'HAND_P'];
  shadowJointNames.forEach((name) => {
    const p = pose.getJoint(Joint[name]);
    const ground = skeletonCamera.project(new Vector3(p.x, 0, p.z), canvasW, canvasH);
    const sx = SkeletonStyle.shadowRadiusX * ground.perspectiveScale * skeletonCamera.zoom;
    const sy = SkeletonStyle.shadowRadiusY * ground.perspectiveScale * skeletonCamera.zoom;
    ellipse(ground.x, ground.y, sx * 2, sy * 2);
  });

  // Build render list of bones + joints sorted back-to-front by depth
  const renderItems = [];

  bones.forEach((bone) => {
    const p1 = projected[bone.a.name];
    const p2 = projected[bone.b.name];
    renderItems.push({
      type: 'bone',
      p1,
      p2,
      thickness: bone.thickness * skeletonCamera.zoom,
      isForeground: bone.colorM >= 1.0,
      depth: (p1.depth + p2.depth) / 2
    });
  });

  const headP = projected['HEAD_POS'];
  renderItems.push({ type: 'joint', p: headP, radius: SkeletonStyle.headRadius * skeletonCamera.zoom, isIndicator: false, depth: headP.depth });

  const wristAP = projected['WRIST_A'];
  renderItems.push({ type: 'joint', p: wristAP, radius: SkeletonStyle.jointRadius * skeletonCamera.zoom, isIndicator: true, depth: wristAP.depth });

  // Torso faces (front/back/left/right/top/bottom quads)
  const hipF = pose.getJoint(Joint.HIP_F);
  const hipB = pose.getJoint(Joint.HIP_B);
  const shoulderA = pose.getJoint(Joint.SHOULDER_A);
  const shoulderP = pose.getJoint(Joint.SHOULDER_P);
  const pelvis = pose.getJoint(Joint.PELVIS);
  const chest = pose.getJoint(Joint.CHEST);

  const lean = vSub(chest, pelvis).normalize();
  const shVec = vSub(shoulderA, shoulderP).normalize();
  const chestNorm = lean.cross(shVec).normalize();

  const offC = chestNorm.copy().multiply(SkeletonStyle.torsoChestDepth);
  const offH = chestNorm.copy().multiply(SkeletonStyle.torsoHipDepth);

  const hipF1 = skeletonCamera.project(hipF.copy().add(offH), canvasW, canvasH);
  const hipF2 = skeletonCamera.project(hipF.copy().subtract(offH), canvasW, canvasH);
  const hipB1 = skeletonCamera.project(hipB.copy().add(offH), canvasW, canvasH);
  const hipB2 = skeletonCamera.project(hipB.copy().subtract(offH), canvasW, canvasH);
  const shA1 = skeletonCamera.project(shoulderA.copy().add(offC), canvasW, canvasH);
  const shA2 = skeletonCamera.project(shoulderA.copy().subtract(offC), canvasW, canvasH);
  const shP1 = skeletonCamera.project(shoulderP.copy().add(offC), canvasW, canvasH);
  const shP2 = skeletonCamera.project(shoulderP.copy().subtract(offC), canvasW, canvasH);

  const faceGroups = [
    [shA1, shP1, hipB1, hipF1],
    [shP2, shA2, hipF2, hipB2],
    [shA2, shA1, hipF1, hipF2],
    [shP1, shP2, hipB2, hipB1],
    [shA2, shP2, shP1, shA1],
    [hipF1, hipB1, hipB2, hipF2]
  ];
  faceGroups.forEach((pts) => {
    const avgDepth = (pts[0].depth + pts[1].depth + pts[2].depth + pts[3].depth) / 4;
    renderItems.push({ type: 'face', pts, depth: avgDepth });
  });

  // Painter's algorithm: farthest first
  renderItems.sort((a, b) => b.depth - a.depth);

  renderItems.forEach((item) => {
    if (item.type === 'bone') {
      const rgb = getZColor(item.depth, item.isForeground);
      stroke(10, 15, 20);
      strokeWeight(item.thickness + SkeletonStyle.outlineWidth * 2);
      strokeCap(ROUND);
      line(item.p1.x, item.p1.y, item.p2.x, item.p2.y);
      stroke(rgb[0], rgb[1], rgb[2]);
      strokeWeight(item.thickness);
      line(item.p1.x, item.p1.y, item.p2.x, item.p2.y);
    } else if (item.type === 'joint') {
      const rgb = getZColor(item.depth, item.isIndicator);
      noStroke();
      fill(10, 15, 20);
      ellipse(item.p.x, item.p.y, (item.radius + 2) * 2);
      fill(rgb[0], rgb[1], rgb[2]);
      ellipse(item.p.x, item.p.y, item.radius * 2);
    } else if (item.type === 'face') {
      const rgb = getZColor(item.depth, false);
      const strokeRgb = rgb.map((c) => c * 0.6);
      const fillRgb = rgb.map((c) => c * 0.9);
      stroke(strokeRgb[0], strokeRgb[1], strokeRgb[2]);
      strokeWeight(SkeletonStyle.outlineWidth);
      fill(fillRgb[0], fillRgb[1], fillRgb[2]);
      beginShape();
      item.pts.forEach((p) => vertex(p.x, p.y));
      endShape(CLOSE);
    }
  });

  noStroke();
  fill(200, 220, 235);
  textSize(14);
  text('Monk Fitness - Push-Up preview (p5.js port)', 16, 24);
  text('progress: ' + progress.toFixed(2), 16, 44);
}
