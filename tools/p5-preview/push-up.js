// Port of the repository's push-up animation logic for browser testing in p5.js.
// Sources:
// - app/src/main/java/com/monkfitness/app/animation/SkeletonMath.kt
// - app/src/main/java/com/monkfitness/app/animation/SkeletonNode.kt
// - app/src/main/java/com/monkfitness/app/poses/PushUpPose.kt

const JOINT = Object.freeze({
  PELVIS: 'PELVIS',
  HIP_F: 'HIP_F',
  HIP_B: 'HIP_B',
  KNEE_F: 'KNEE_F',
  ANKLE_F: 'ANKLE_F',
  HEEL_F: 'HEEL_F',
  TOE_F: 'TOE_F',
  KNEE_B: 'KNEE_B',
  ANKLE_B: 'ANKLE_B',
  HEEL_B: 'HEEL_B',
  TOE_B: 'TOE_B',
  CHEST: 'CHEST',
  SHOULDER_A: 'SHOULDER_A',
  SHOULDER_P: 'SHOULDER_P',
  ELBOW_A: 'ELBOW_A',
  HAND_A: 'HAND_A',
  ELBOW_P: 'ELBOW_P',
  HAND_P: 'HAND_P',
  NECK_END: 'NECK_END',
  HEAD_POS: 'HEAD_POS'
});

const EPSILON = 1e-6;

class Vector3 {
  constructor(x = 0, y = 0, z = 0) {
    this.x = x;
    this.y = y;
    this.z = z;
  }

  add(v) { return new Vector3(this.x + v.x, this.y + v.y, this.z + v.z); }
  sub(v) { return new Vector3(this.x - v.x, this.y - v.y, this.z - v.z); }
  mul(s) { return new Vector3(this.x * s, this.y * s, this.z * s); }
  div(s) { return new Vector3(this.x / s, this.y / s, this.z / s); }

  mag() { return Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z); }

  normalize() {
    const m = this.mag();
    return m > EPSILON ? this.div(m) : new Vector3(0, 0, 0);
  }

  dot(v) { return this.x * v.x + this.y * v.y + this.z * v.z; }

  cross(v) {
    return new Vector3(
      this.y * v.z - this.z * v.y,
      this.z * v.x - this.x * v.z,
      this.x * v.y - this.y * v.x
    );
  }

  copy() { return new Vector3(this.x, this.y, this.z); }
}

const Z_AXIS = new Vector3(0, 0, 1);

class JointRotation {
  constructor(axis = new Vector3(0, 0, 1), angle = 0) {
    this.axis = axis;
    this.angle = angle;
  }

  set(axis, angle) {
    this.axis = axis;
    this.angle = angle;
  }
}

class SkeletonNode {
  constructor(joint, parent = null, localPosition = new Vector3(), localRotation = new JointRotation()) {
    this.joint = joint;
    this.parent = parent;
    this.localPosition = localPosition;
    this.localRotation = localRotation;
    this.children = [];
    this.worldPosition = new Vector3();
    this.worldRotation = new JointRotation();
  }

  addChild(node) {
    node.parent = this;
    this.children.push(node);
    return node;
  }

  updateWorldTransforms(parentWorldPos, parentWorldRotation) {
    const rotatedPos = parentWorldRotation.angle !== 0
      ? SkeletonMath.rotAround(this.localPosition, parentWorldRotation.axis, parentWorldRotation.angle)
      : this.localPosition;

    this.worldPosition = parentWorldPos.add(rotatedPos);
    this.worldRotation.axis = parentWorldRotation.axis;
    this.worldRotation.angle = parentWorldRotation.angle + this.localRotation.angle;

    for (const child of this.children) {
      child.updateWorldTransforms(this.worldPosition, this.worldRotation);
    }
  }

  flatten(target) {
    target[this.joint] = this.worldPosition.copy();
    for (const child of this.children) {
      child.flatten(target);
    }
  }
}

function computeWorldState(node) {
  let worldPosition = new Vector3(0, 0, 0);
  let worldRotation = new JointRotation();
  const chain = [];
  let current = node;

  while (current) {
    chain.push(current);
    current = current.parent;
  }

  chain.reverse();
  for (const item of chain) {
    const rotatedPos = worldRotation.angle !== 0
      ? SkeletonMath.rotAround(item.localPosition, worldRotation.axis, worldRotation.angle)
      : item.localPosition;
    worldPosition = worldPosition.add(rotatedPos);
    worldRotation = new JointRotation(worldRotation.axis, worldRotation.angle + item.localRotation.angle);
  }

  return { position: worldPosition, rotation: worldRotation };
}

function worldToLocalArmPosition(ikResult, shoulderWorld, chestRotation) {
  const jointLocal = SkeletonMath.rotAround(ikResult.joint.sub(shoulderWorld), Z_AXIS, -chestRotation.angle);
  const endLocal = SkeletonMath.rotAround(ikResult.end.sub(ikResult.joint), Z_AXIS, -chestRotation.angle);
  return { jointLocal, endLocal };
}

class IKConstraint {
  constructor(minimumFlexionAngle, maximumExtensionRatio) {
    this.minimumFlexionAngle = minimumFlexionAngle;
    this.maximumExtensionRatio = maximumExtensionRatio;
  }
}

const SkeletonMath = {
  lerp(a, b, t) { return a + (b - a) * t; },

  rotAround(v, axis, ang) {
    const k = axis.normalize();
    const c = Math.cos(ang);
    const s = Math.sin(ang);
    const dot = k.dot(v);
    const cross = k.cross(v);
    return v.mul(c).add(cross.mul(s)).add(k.mul(dot * (1 - c)));
  },

  solveIK(root, target, L1, L2, pole, constraint) {
    const d = target.sub(root);
    const dMag = d.mag();
    const maxDist = (L1 + L2) * constraint.maximumExtensionRatio;
    const minCos = Math.cos((constraint.minimumFlexionAngle * Math.PI) / 180.0);
    // Minimum reach distance occurs at maximum flexion angle, computed using the law of cosines for the two-segment chain.
    const minDist = Math.sqrt(L1 * L1 + L2 * L2 - 2 * L1 * L2 * minCos);
    const dist = Math.min(Math.max(dMag, minDist), maxDist);
    const dir = d.normalize();
    const end = root.add(dir.mul(dist));

    const a = (dist * dist + L1 * L1 - L2 * L2) / (2 * dist);
    const h = Math.sqrt(Math.max(L1 * L1 - a * a, 0));

    let p = pole.copy();
    p = p.sub(dir.mul(p.dot(dir)));

    // Fall back to a vertical pole vector when the input pole is nearly parallel to the limb direction.
    if (p.mag() < POLE_VECTOR_EPSILON) {
      p = new Vector3(0, 1, 0).sub(dir.mul(dir.y));
    }
    p = p.normalize();

    const joint = root.add(dir.mul(a)).add(p.mul(h));
    return { joint, end };
  }
};

class PushUpPoseBuilder {
  constructor() {
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
  }

  ensureHierarchy(def) {
    if (this.roots) return;

    this.ankleF = new SkeletonNode(JOINT.ANKLE_F);
    this.kneeF = this.ankleF.addChild(new SkeletonNode(JOINT.KNEE_F));
    this.hipF = this.kneeF.addChild(new SkeletonNode(JOINT.HIP_F));

    this.pelvis = this.hipF.addChild(new SkeletonNode(JOINT.PELVIS));
    this.chest = this.pelvis.addChild(new SkeletonNode(JOINT.CHEST));

    this.neck = this.chest.addChild(new SkeletonNode(JOINT.NECK_END));
    this.head = this.neck.addChild(new SkeletonNode(JOINT.HEAD_POS));

    this.shoulderA = this.chest.addChild(new SkeletonNode(JOINT.SHOULDER_A));
    this.elbowA = this.shoulderA.addChild(new SkeletonNode(JOINT.ELBOW_A));
    this.handA = this.elbowA.addChild(new SkeletonNode(JOINT.HAND_A));

    this.shoulderP = this.chest.addChild(new SkeletonNode(JOINT.SHOULDER_P));
    this.elbowP = this.shoulderP.addChild(new SkeletonNode(JOINT.ELBOW_P));
    this.handP = this.elbowP.addChild(new SkeletonNode(JOINT.HAND_P));

    this.hipB = this.pelvis.addChild(new SkeletonNode(JOINT.HIP_B));
    this.kneeB = this.hipB.addChild(new SkeletonNode(JOINT.KNEE_B));
    this.ankleB = this.kneeB.addChild(new SkeletonNode(JOINT.ANKLE_B));

    this.roots = [this.ankleF];
  }

  build(def, progress) {
    this.ensureHierarchy(def);

    const height = SkeletonMath.lerp(PUSHUP_START_HEIGHT, PUSHUP_END_HEIGHT, progress);
    const totalLegLen = def.shinLength + def.thighLength;
    const legRatio = height / totalLegLen;
    const theta = Math.asin(clamp(legRatio, -1, 1));
    const horizontalDist = totalLegLen * Math.cos(theta);
    const ankleX = ANKLE_BASE_X + horizontalDist;

    this.ankleF.localPosition = new Vector3(ankleX, 0, def.hipWidth);
    this.ankleF.localRotation.set(Z_AXIS, -theta);

    this.kneeF.localPosition = new Vector3(-def.shinLength, 0, 0);
    this.hipF.localPosition = new Vector3(-def.thighLength, 0, 0);

    this.pelvis.localPosition = new Vector3(0, 0, -def.hipWidth);
    this.chest.localPosition = new Vector3(-def.torsoLength, 0, 0);

    const headDir = new Vector3(-1, HEAD_TILT_FACTOR, 0).normalize();
    this.neck.localPosition = headDir.mul(def.neckLength);
    this.head.localPosition = headDir.mul(HEAD_OFFSET_LENGTH);

    this.hipB.localPosition = new Vector3(0, 0, -def.hipWidth);
    this.kneeB.localPosition = new Vector3(def.thighLength, 0, 0);
    this.ankleB.localPosition = new Vector3(def.shinLength, 0, 0);

    const chestWorldState = computeWorldState(this.chest);
    const chestW = chestWorldState.position;
    const chestRotation = chestWorldState.rotation;

    const shoulderOffsetA = new Vector3(0, 0, def.shoulderWidth);
    const shoulderOffsetP = new Vector3(0, 0, -def.shoulderWidth);
    const shoulderAW = chestW.add(SkeletonMath.rotAround(shoulderOffsetA, Z_AXIS, chestRotation.angle));
    const shoulderPW = chestW.add(SkeletonMath.rotAround(shoulderOffsetP, Z_AXIS, chestRotation.angle));

    const targetHandA = new Vector3(chestW.x, 0, def.shoulderWidth * HAND_TARGET_WIDTH_MULTIPLIER);
    const targetHandP = new Vector3(chestW.x, 0, -def.shoulderWidth * HAND_TARGET_WIDTH_MULTIPLIER);

    const armA = SkeletonMath.solveIK(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, new Vector3(1, 0, 1), def.armIKConstraint);
    const armP = SkeletonMath.solveIK(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, new Vector3(1, 0, -1), def.armIKConstraint);

    this.shoulderA.localPosition = new Vector3(0, 0, def.shoulderWidth);
    this.shoulderP.localPosition = new Vector3(0, 0, -def.shoulderWidth);

    const armLocalA = worldToLocalArmPosition(armA, shoulderAW, chestRotation);
    const armLocalP = worldToLocalArmPosition(armP, shoulderPW, chestRotation);
    this.elbowA.localPosition = armLocalA.jointLocal;
    this.handA.localPosition = armLocalA.endLocal;
    this.elbowP.localPosition = armLocalP.jointLocal;
    this.handP.localPosition = armLocalP.endLocal;

    const joints = {};
    this.roots.forEach((root) => root.updateWorldTransforms(new Vector3(0, 0, 0), new JointRotation()));
    this.roots.forEach((root) => root.flatten(joints));

    joints[JOINT.TOE_F] = new Vector3(ankleX + TOE_OFFSET_LENGTH, 0, def.hipWidth);
    joints[JOINT.TOE_B] = new Vector3(ankleX + TOE_OFFSET_LENGTH, 0, -def.hipWidth);
    return joints;
  }
}

const DEFAULT_DEFINITION = {
  torsoLength: 120,
  neckLength: 18,
  thighLength: 112,
  shinLength: 98,
  upperArmLength: 64,
  forearmLength: 82,
  shoulderWidth: 42,
  hipWidth: 22,
  armIKConstraint: new IKConstraint(30, 0.95)
};

const PUSHUP_START_HEIGHT = 60;
const PUSHUP_END_HEIGHT = 25;
const ANKLE_BASE_X = 60;
const HEAD_OFFSET_LENGTH = 18;
const HEAD_TILT_FACTOR = 0.2;
const HAND_TARGET_WIDTH_MULTIPLIER = 1.5;
const TOE_OFFSET_LENGTH = 10;
const DRAW_ROTATION_DEGREES = -8;
const ANIMATION_CYCLE_SECONDS = 2.4;
const POLE_VECTOR_EPSILON = 1e-4;
const PROJECTION_X_SCALE = 0.85;
const PROJECTION_Z_SKEW = 0.3;
const PROJECTION_Y_SCALE = 0.65;
const PROJECTION_Z_OFFSET = 0.05;

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

let poseBuilder;
let bgColor;
let lineColor;
let limbColor;
let accentColor;
let floorColor;

function setup() {
  createCanvas(900, 600);
  pixelDensity(1);
  strokeCap(ROUND);
  strokeJoin(ROUND);
  noFill();

  poseBuilder = new PushUpPoseBuilder();
  bgColor = color(8, 12, 18);
  lineColor = color(4, 8, 12);
  limbColor = color(86, 214, 255);
  accentColor = color(255, 162, 104);
  floorColor = color(40, 56, 70);
}

function draw() {
  background(bgColor);

  const time = millis() / 1000.0;
  const cycle = ANIMATION_CYCLE_SECONDS;
  const phase = (time / cycle) % 1.0;
  // Ping-pong the 0..1 cycle so the pose moves up and then back down.
  const t = phase < 0.5 ? phase * 2.0 : (1.0 - phase) * 2.0;
  const eased = smoothstepEasing(t);
  const pose = poseBuilder.build(DEFAULT_DEFINITION, eased);

  push();
  translate(width * 0.5, height * 0.72);
  scale(0.75);
  rotate(radians(DRAW_ROTATION_DEGREES));

  drawGround();
  drawSkeleton(pose);

  pop();

  drawLabel();
}

function drawSkeleton(pose) {
  const boneData = [
    [JOINT.HIP_B, JOINT.KNEE_B, 12, limbColor],
    [JOINT.KNEE_B, JOINT.ANKLE_B, 10, accentColor],
    [JOINT.SHOULDER_P, JOINT.ELBOW_P, 10, limbColor],
    [JOINT.ELBOW_P, JOINT.HAND_P, 8, accentColor],
    [JOINT.HIP_F, JOINT.KNEE_F, 12, limbColor],
    [JOINT.KNEE_F, JOINT.ANKLE_F, 10, accentColor],
    [JOINT.SHOULDER_A, JOINT.ELBOW_A, 10, limbColor],
    [JOINT.ELBOW_A, JOINT.HAND_A, 8, accentColor],
    [JOINT.PELVIS, JOINT.CHEST, 14, limbColor],
    [JOINT.CHEST, JOINT.NECK_END, 8, accentColor],
    [JOINT.NECK_END, JOINT.HEAD_POS, 6, accentColor]
  ];

  for (const [a, b, thickness, colorValue] of boneData) {
    drawBone(pose[a], pose[b], thickness, colorValue);
  }

  const joints = [
    [JOINT.PELVIS, 8, limbColor],
    [JOINT.CHEST, 9, accentColor],
    [JOINT.SHOULDER_A, 7, limbColor],
    [JOINT.SHOULDER_P, 7, limbColor],
    [JOINT.ELBOW_A, 7, accentColor],
    [JOINT.ELBOW_P, 7, accentColor],
    [JOINT.HAND_A, 8, accentColor],
    [JOINT.HAND_P, 8, accentColor],
    [JOINT.NECK_END, 7, accentColor],
    [JOINT.HEAD_POS, 9, accentColor],
    [JOINT.HIP_F, 7, limbColor],
    [JOINT.HIP_B, 7, limbColor],
    [JOINT.KNEE_F, 7, accentColor],
    [JOINT.KNEE_B, 7, accentColor],
    [JOINT.ANKLE_F, 7, accentColor],
    [JOINT.ANKLE_B, 7, accentColor],
    [JOINT.TOE_F, 7, accentColor],
    [JOINT.TOE_B, 7, accentColor]
  ];

  for (const [joint, radius, colorValue] of joints) {
    drawJoint(pose[joint], radius, colorValue);
  }
}

function drawBone(a, b, thickness, colorValue) {
  const pa = projectPoint(a);
  const pb = projectPoint(b);

  stroke(lineColor);
  strokeWeight(thickness + 3);
  line(pa.x, pa.y, pb.x, pb.y);

  stroke(colorValue);
  strokeWeight(thickness);
  line(pa.x, pa.y, pb.x, pb.y);
}

function drawJoint(point, radius, colorValue) {
  const p = projectPoint(point);
  stroke(lineColor);
  strokeWeight(radius + 2);
  point(p.x, p.y);

  stroke(colorValue);
  strokeWeight(radius);
  point(p.x, p.y);
}

function drawGround() {
  noStroke();
  fill(floorColor);
  rect(-320, 260, 640, 46, 8);

  stroke(255, 255, 255, 24);
  strokeWeight(2);
  line(-300, 284, 300, 284);

  noStroke();
}

function drawLabel() {
  noStroke();
  fill(255, 255, 255, 170);
  textSize(18);
  textAlign(RIGHT, TOP);
  text('repo-port push-up preview', width - 24, 20);
}

function projectPoint(point) {
  const x = point.x * PROJECTION_X_SCALE + point.z * PROJECTION_Z_SKEW;
  const y = -point.y * PROJECTION_Y_SCALE + point.z * PROJECTION_Z_OFFSET;
  return { x, y };
}

function smoothstepEasing(progress) {
  const clamped = constrain(progress, 0, 1);
  return clamped * clamped * (3 - 2 * clamped);
}
