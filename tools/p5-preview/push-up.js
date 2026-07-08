/**
 * push-up.js
 *
 * Single-file, self-contained p5.js sketch for testing the push-up
 * animation in the p5 web editor (https://editor.p5js.org/) — no local
 * setup, extra files, or build step required. Just paste this file's
 * contents into the editor's `sketch.js` and hit "Play".
 *
 * This is a single-file merge of the modular preview under
 * `tools/p5-preview/js/` (`engine.js` + `poses/pushup.js` + `sketch.js`),
 * which itself is a JavaScript port of the Kotlin skeleton animation
 * engine (`app/src/main/java/com/monkfitness/app/animation`) and the
 * `PushUpPose` builder (`app/src/main/java/com/monkfitness/app/poses/PushUpPose.kt`).
 *
 * Controls:
 *  - Drag horizontally: orbit camera (yaw)
 *  - Drag vertically: adjust camera pitch
 *  - Mouse wheel: zoom
 *  - Space: pause/resume the animation loop
 */

"use strict";

// ---------------------------------------------------------------------
// Vector3
// ---------------------------------------------------------------------
class Vector3 {
  constructor(x = 0, y = 0, z = 0) {
    this.x = x;
    this.y = y;
    this.z = z;
  }

  add(v) {
    return new Vector3(this.x + v.x, this.y + v.y, this.z + v.z);
  }

  sub(v) {
    return new Vector3(this.x - v.x, this.y - v.y, this.z - v.z);
  }

  scale(s) {
    return new Vector3(this.x * s, this.y * s, this.z * s);
  }

  div(s) {
    return new Vector3(this.x / s, this.y / s, this.z / s);
  }

  mag() {
    return Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
  }

  normalize() {
    const m = this.mag();
    return m > 1e-6 ? this.div(m) : new Vector3(0, 0, 0);
  }

  dot(v) {
    return this.x * v.x + this.y * v.y + this.z * v.z;
  }

  cross(v) {
    return new Vector3(
      this.y * v.z - this.z * v.y,
      this.z * v.x - this.x * v.z,
      this.x * v.y - this.y * v.x
    );
  }

  copy() {
    return new Vector3(this.x, this.y, this.z);
  }
}

// ---------------------------------------------------------------------
// Easing / interpolation helpers
// ---------------------------------------------------------------------
const SkeletonMath = {
  lerp(a, b, t) {
    if (a instanceof Vector3) return a.add(b.sub(a).scale(t));
    return a + (b - a) * t;
  },

  // Standard Ease-In-Out Quintic. Exposed as part of the ported easing
  // toolkit for pose authors to reuse; not used by the bundled push-up
  // example, which uses `easeInOut` instead.
  easeIO(x) {
    const cx = Math.min(1, Math.max(0, x));
    return cx * cx * cx * (cx * (cx * 6 - 15) + 10);
  },

  // Reference Implementation Ease-In-Out (Quadric)
  easeInOut(x) {
    return x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2;
  },

  // Standard smoothstep, exposed for pose authors who need a simple 0..1
  // blend curve (e.g. hold/breathing-style animations).
  smoothstep(x) {
    const t = Math.min(1, Math.max(0, x));
    return t * t * (3 - 2 * t);
  },

  // Rodrigues rotation: rotate v around unit axis k by angle (radians)
  rotAround(v, axis, ang) {
    const k = axis.normalize();
    const c = Math.cos(ang);
    const s = Math.sin(ang);
    const d = k.dot(v);
    const cr = k.cross(v);
    return v.scale(c).add(cr.scale(s)).add(k.scale(d * (1 - c)));
  },
};

// ---------------------------------------------------------------------
// IK Constraint + solver
// ---------------------------------------------------------------------
const IKConstraint = {
  ArmConstraint: { minimumFlexionAngle: 30, maximumExtensionRatio: 0.95 },
  LegConstraint: { minimumFlexionAngle: 5, maximumExtensionRatio: 0.98 },
};

/** Analytical 2-bone IK with biological clamps. */
function solveIK(root, target, L1, L2, pole, constraint) {
  const d = target.sub(root);
  const dMag = d.mag();

  const maxDist = (L1 + L2) * constraint.maximumExtensionRatio;

  const minCos = Math.cos((constraint.minimumFlexionAngle * Math.PI) / 180);
  const minDist = Math.sqrt(
    Math.max(L1 * L1 + L2 * L2 - 2 * L1 * L2 * minCos, 0)
  );

  const dist = Math.min(Math.max(dMag, minDist), maxDist);
  const dir = d.normalize();
  const end = root.add(dir.scale(dist));

  const a = (dist * dist + L1 * L1 - L2 * L2) / (2 * dist);
  const h = Math.sqrt(Math.max(L1 * L1 - a * a, 0));

  let p = pole.copy();
  p = p.sub(dir.scale(p.dot(dir)));

  if (p.mag() < 1e-4) {
    p = new Vector3(0, 1, 0).sub(dir.scale(dir.y));
  }
  p = p.normalize();

  const joint = root.add(dir.scale(a)).add(p.scale(h));
  return { joint, end };
}

// ---------------------------------------------------------------------
// Foot / Hand definitions
// ---------------------------------------------------------------------
class FootDefinition {
  constructor({
    footLength,
    heelRatio = 0.29,
    toeRatio = 0.71,
    minPitch = (-45 * Math.PI) / 180,
    maxPitch = (45 * Math.PI) / 180,
  }) {
    this.footLength = footLength;
    this.heelRatio = heelRatio;
    this.toeRatio = toeRatio;
    this.minPitch = minPitch;
    this.maxPitch = maxPitch;
  }

  computeHeelToe(ankle, forward) {
    const dir = forward.normalize();
    const heel = ankle.sub(dir.scale(this.footLength * this.heelRatio));
    const toe = ankle.add(dir.scale(this.footLength * this.toeRatio));
    return { heel, toe };
  }
}

class HandDefinition {
  constructor({ palmLength = 12, fingerLength = 10, handWidth = 10 } = {}) {
    this.palmLength = palmLength;
    this.fingerLength = fingerLength;
    this.handWidth = handWidth;
  }

  computeHandJoints(wrist, direction) {
    const dir = direction.normalize();
    return {
      wrist,
      palm: wrist.add(dir.scale(this.palmLength * 0.5)),
      knuckles: wrist.add(dir.scale(this.palmLength)),
      fingertips: wrist.add(dir.scale(this.palmLength + this.fingerLength)),
    };
  }
}

// ---------------------------------------------------------------------
// SkeletonDefinition
// ---------------------------------------------------------------------
function createSkeletonDefinition(overrides = {}) {
  const def = Object.assign(
    {
      torsoLength: 120,
      neckLength: 18,
      thighLength: 112,
      shinLength: 98,
      footLength: 35,
      upperArmLength: 64,
      forearmLength: 82,
      shoulderWidth: 42,
      hipWidth: 22,
    },
    overrides
  );
  def.foot = overrides.foot || new FootDefinition({ footLength: def.footLength });
  def.hand = overrides.hand || new HandDefinition();
  return def;
}

const DEFAULT_ADULT = createSkeletonDefinition();

// ---------------------------------------------------------------------
// SkeletonStyle
// ---------------------------------------------------------------------
const DEFAULT_STYLE = {
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
  primaryColor: [100, 240, 220],
  secondaryColor: [180, 200, 220],
  farColor: [25, 35, 55],
};

// ---------------------------------------------------------------------
// Joint identifiers
// ---------------------------------------------------------------------
const Joint = Object.freeze({
  PELVIS: "PELVIS",
  HIP_F: "HIP_F",
  HIP_B: "HIP_B",
  KNEE_F: "KNEE_F",
  ANKLE_F: "ANKLE_F",
  HEEL_F: "HEEL_F",
  TOE_F: "TOE_F",
  KNEE_B: "KNEE_B",
  ANKLE_B: "ANKLE_B",
  HEEL_B: "HEEL_B",
  TOE_B: "TOE_B",
  CHEST: "CHEST",
  SHOULDER_A: "SHOULDER_A",
  SHOULDER_P: "SHOULDER_P",
  ELBOW_A: "ELBOW_A",
  HAND_A: "HAND_A",
  WRIST_A: "WRIST_A",
  PALM_A: "PALM_A",
  KNUCKLES_A: "KNUCKLES_A",
  FINGERTIPS_A: "FINGERTIPS_A",
  ELBOW_P: "ELBOW_P",
  HAND_P: "HAND_P",
  WRIST_P: "WRIST_P",
  PALM_P: "PALM_P",
  KNUCKLES_P: "KNUCKLES_P",
  FINGERTIPS_P: "FINGERTIPS_P",
  NECK_END: "NECK_END",
  HEAD_POS: "HEAD_POS",
});

// ---------------------------------------------------------------------
// Bone list builder
// ---------------------------------------------------------------------
function buildBones(style) {
  return [
    // Background limbs (Right side)
    { a: Joint.HIP_B, b: Joint.KNEE_B, thickness: style.thighThickness * 0.8, color: 0.62 },
    { a: Joint.KNEE_B, b: Joint.ANKLE_B, thickness: style.shinThickness * 0.8, color: 0.62 },
    { a: Joint.HEEL_B, b: Joint.TOE_B, thickness: 10, color: 0.62 },

    { a: Joint.SHOULDER_P, b: Joint.ELBOW_P, thickness: style.upperArmThickness * 0.85, color: 0.8 },
    { a: Joint.ELBOW_P, b: Joint.WRIST_P, thickness: style.forearmThickness * 0.85, color: 0.8 },
    { a: Joint.WRIST_P, b: Joint.PALM_P, thickness: style.handThickness * 0.85, color: 0.8 },
    { a: Joint.PALM_P, b: Joint.FINGERTIPS_P, thickness: style.handThickness * 0.8 * 0.85, color: 0.8 },

    // Foreground limbs (Left side)
    { a: Joint.HIP_F, b: Joint.KNEE_F, thickness: style.thighThickness, color: 1.0 },
    { a: Joint.KNEE_F, b: Joint.ANKLE_F, thickness: style.shinThickness, color: 1.0 },
    { a: Joint.HEEL_F, b: Joint.TOE_F, thickness: 12, color: 1.0 },

    { a: Joint.SHOULDER_A, b: Joint.ELBOW_A, thickness: style.upperArmThickness, color: 1.05 },
    { a: Joint.ELBOW_A, b: Joint.WRIST_A, thickness: style.forearmThickness, color: 1.05 },
    { a: Joint.WRIST_A, b: Joint.PALM_A, thickness: style.handThickness, color: 1.05 },
    { a: Joint.PALM_A, b: Joint.FINGERTIPS_A, thickness: style.handThickness * 0.8, color: 1.05 },

    // Spine / neck
    { a: Joint.PELVIS, b: Joint.CHEST, thickness: 17, color: 0.95 },
    { a: Joint.CHEST, b: Joint.NECK_END, thickness: style.neckThickness, color: 0.95 },
  ];
}

// ---------------------------------------------------------------------
// Camera
// ---------------------------------------------------------------------
class Camera {
  constructor({
    yaw = 1.19,
    pitch = 0.22,
    zoom = 1.3,
    focalLength = 1000,
    centerX = 0.5,
    centerY = 0.7,
  } = {}) {
    this.yaw = yaw;
    this.pitch = pitch;
    this.zoom = zoom;
    this.focalLength = focalLength;
    this.centerX = centerX;
    this.centerY = centerY;
  }

  project(v, width, height) {
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
      x: width * this.centerX + xr * sc * this.zoom,
      y: height * this.centerY - y2 * sc * this.zoom,
      scale: sc,
      depth: z2,
    };
  }
}

// ---------------------------------------------------------------------
// PerspectiveCompensation
// ---------------------------------------------------------------------
class PerspectiveCompensation {
  constructor(definition) {
    this.definition = definition;
  }

  /** Stage 1: 3D pose correction (heel/toe + wrist/palm/fingertips). */
  preProcessPose(pose) {
    const joints = Object.assign({}, pose);

    this._adjustFootOrientation(joints, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F);
    this._adjustFootOrientation(joints, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B);

    this._adjustHandOrientation(joints, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A);
    this._adjustHandOrientation(joints, Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P);

    return joints;
  }

  _adjustHandOrientation(joints, elbowId, handId, wristId, palmId, knucklesId, fingertipsId) {
    const elbow = joints[elbowId];
    const hand = joints[handId];
    if (!elbow || !hand) return;

    const wrist = hand;
    joints[wristId] = wrist;

    const dir = wrist.sub(elbow).normalize();
    const handDef = this.definition.hand;
    const handJoints = handDef.computeHandJoints(wrist, dir);

    joints[palmId] = handJoints.palm;
    joints[knucklesId] = handJoints.knuckles;
    joints[fingertipsId] = handJoints.fingertips;
  }

  _adjustFootOrientation(joints, kneeId, ankleId, heelId, toeId) {
    const knee = joints[kneeId];
    const ankle = joints[ankleId];
    const providedToe = joints[toeId];
    if (!knee || !ankle) return;

    const shank = ankle.sub(knee).normalize();

    const forwardHint =
      providedToe && providedToe.sub(ankle).mag() > 1e-3
        ? providedToe.sub(ankle).normalize()
        : new Vector3(1, 0, 0);

    let footDir = forwardHint.sub(shank.scale(forwardHint.dot(shank)));

    if (footDir.mag() < 1e-3) {
      const down = new Vector3(0, -1, 0);
      footDir = down.sub(shank.scale(down.dot(shank)));
    }
    footDir = footDir.normalize();

    const pitch = Math.atan2(footDir.y, Math.sqrt(footDir.x * footDir.x + footDir.z * footDir.z));
    const clampedPitch = Math.min(Math.max(pitch, this.definition.foot.minPitch), this.definition.foot.maxPitch);

    if (Math.abs(pitch - clampedPitch) > 1e-3) {
      const horizontalDir = new Vector3(footDir.x, 0, footDir.z).normalize();
      footDir = horizontalDir.scale(Math.cos(clampedPitch)).add(new Vector3(0, 1, 0).scale(Math.sin(clampedPitch)));
    }

    const foot = this.definition.foot;
    const { heel, toe } = foot.computeHeelToe(ankle, footDir);
    joints[heelId] = heel;
    joints[toeId] = toe;
  }

  /** Stage 2: 2D projected point correction, preserving heel<->toe length. */
  compensateFootPerspective(ankleProj, heelProj, toeProj, camera) {
    const currentLen = Math.hypot(toeProj.x - heelProj.x, toeProj.y - heelProj.y);
    if (currentLen < 1e-3) return { heel: heelProj, toe: toeProj };

    const foot = this.definition.foot;
    const idealLen = foot.footLength * ankleProj.scale * camera.zoom;

    const ratio = idealLen / currentLen;
    const targetRatio = 1.0 + (ratio - 1.0) * 0.15; // 15% correction

    const ahdx = heelProj.x - ankleProj.x;
    const ahdy = heelProj.y - ankleProj.y;
    const atdx = toeProj.x - ankleProj.x;
    const atdy = toeProj.y - ankleProj.y;

    const newHeel = {
      x: ankleProj.x + ahdx * targetRatio,
      y: ankleProj.y + ahdy * targetRatio,
      scale: heelProj.scale,
      depth: heelProj.depth,
    };
    const newToe = {
      x: ankleProj.x + atdx * targetRatio,
      y: ankleProj.y + atdy * targetRatio,
      scale: toeProj.scale,
      depth: toeProj.depth,
    };

    return { heel: newHeel, toe: newToe };
  }

  /** Depth-based bone thickness compensation (+/-15%). */
  compensateThickness(thickness, depth) {
    const depthFactor = Math.min(Math.max(-(depth / 200), -1), 1);
    const adjustment = 1.0 + depthFactor * 0.15;
    return thickness * adjustment;
  }

  /** Prevents excessive shrinking of the head at distance. */
  compensateHeadScale(scale, depth) {
    const depthFactor = Math.max(depth / 200, 0);
    const boost = Math.min(1.0 + depthFactor * 0.1, 1.15);
    return scale * boost;
  }

  /** Maintains visual palm length regardless of camera depth. */
  compensateHandPerspective(wristProj, palmProj, fingertipsProj, camera) {
    const currentLen = Math.hypot(fingertipsProj.x - wristProj.x, fingertipsProj.y - wristProj.y);
    if (currentLen < 1e-3) return { palm: palmProj, fingertips: fingertipsProj };

    const handDef = this.definition.hand;
    const idealLen = (handDef.palmLength + handDef.fingerLength) * wristProj.scale * camera.zoom;

    const ratio = idealLen / currentLen;
    const targetRatio = 1.0 + (ratio - 1.0) * 0.12; // 12% correction

    const pdx = palmProj.x - wristProj.x;
    const pdy = palmProj.y - wristProj.y;
    const fdx = fingertipsProj.x - wristProj.x;
    const fdy = fingertipsProj.y - wristProj.y;

    const newPalm = {
      x: wristProj.x + pdx * targetRatio,
      y: wristProj.y + pdy * targetRatio,
      scale: palmProj.scale,
      depth: palmProj.depth,
    };
    const newFingertips = {
      x: wristProj.x + fdx * targetRatio,
      y: wristProj.y + fdy * targetRatio,
      scale: fingertipsProj.scale,
      depth: fingertipsProj.depth,
    };

    return { palm: newPalm, fingertips: newFingertips };
  }

  /** Prevents excessive shrinking of the hand indicator at distance. */
  compensateHandScale(scale, depth) {
    const depthFactor = Math.max(depth / 200, 0);
    const boost = Math.min(1.0 + depthFactor * 0.08, 1.12);
    return scale * boost;
  }
}

// ---------------------------------------------------------------------
// Push-up pose builder (port of PushUpPose.kt)
// ---------------------------------------------------------------------

/**
 * @param {{progress:number, definition:object}} context
 * @returns {Object<string, Vector3>} joints keyed by Joint id
 */
function buildPushUpPose(context) {
  const { progress, definition } = context;

  // progress 0 (up) to 1 (down)
  const height = SkeletonMath.lerp(60, 25, progress);
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

// ---------------------------------------------------------------------
// p5.js sketch (setup/draw) — mirrors SkeletonRenderer.kt closely enough
// for visual parity when testing new exercise animations in the browser.
// ---------------------------------------------------------------------

const definition = DEFAULT_ADULT;
const style = DEFAULT_STYLE;
const bones = buildBones(style);
const camera = new Camera();
const compensator = new PerspectiveCompensation(definition);

const CYCLE_DURATION_MS = 2600; // full down+up cycle, mirrors AnimationController LOOP mode
let paused = false;
let elapsed = 0;
let lastMillis = 0;

function setup() {
  createCanvas(windowWidth, windowHeight);
  lastMillis = millis();
}

function windowResized() {
  resizeCanvas(windowWidth, windowHeight);
}

function keyPressed() {
  if (key === " ") {
    paused = !paused;
  }
}

// Camera orbit via drag, zoom via wheel (purely for inspection convenience).
function mouseDragged() {
  if (mouseX < 0 || mouseY < 0 || mouseX > width || mouseY > height) return;
  camera.yaw += movedX * 0.01;
  camera.pitch = constrain(camera.pitch - movedY * 0.01, -1.4, 1.4);
}

function mouseWheel(event) {
  camera.zoom = constrain(camera.zoom - event.delta * 0.001, 0.6, 2.5);
  return false;
}

/** Progress oscillates 0 (up) -> 1 (down) -> 0 (up), like AnimationMode.LOOP. */
function computeProgress(timeMs) {
  const half = CYCLE_DURATION_MS / 2;
  const t = (timeMs % CYCLE_DURATION_MS) / half; // 0..2
  const local = t < 1 ? t : 2 - t; // 0..1..0 triangle wave
  return SkeletonMath.easeInOut(local);
}

function draw() {
  const now = millis();
  const dt = now - lastMillis;
  lastMillis = now;
  if (!paused) elapsed += dt;

  const progress = computeProgress(elapsed);

  const context = { progress, definition };
  const rawPose = buildPushUpPose(context);
  const pose = compensator.preProcessPose(rawPose);

  background(13, 17, 23);
  drawGround(pose);

  const items = [];

  for (const bone of bones) {
    let p1 = camera.project(pose[bone.a], width, height);
    let p2 = camera.project(pose[bone.b], width, height);

    // Foot perspective correction (heel -> toe bone)
    if (bone.b === Joint.TOE_F || bone.b === Joint.TOE_B) {
      const isF = bone.b === Joint.TOE_F;
      const ankleJoint = isF ? Joint.ANKLE_F : Joint.ANKLE_B;
      const pAnkle = camera.project(pose[ankleJoint], width, height);
      const pHeel = camera.project(pose[bone.a], width, height);
      const corrected = compensator.compensateFootPerspective(pAnkle, pHeel, p2, camera);
      p1 = corrected.heel;
      p2 = corrected.toe;
    }

    // Hand perspective correction (wrist->palm / palm->fingertips bones)
    if (
      bone.b === Joint.FINGERTIPS_A ||
      bone.b === Joint.FINGERTIPS_P ||
      bone.b === Joint.PALM_A ||
      bone.b === Joint.PALM_P
    ) {
      const isA = bone.b === Joint.FINGERTIPS_A || bone.b === Joint.PALM_A;
      const wristJoint = isA ? Joint.WRIST_A : Joint.WRIST_P;
      const palmJoint = isA ? Joint.PALM_A : Joint.PALM_P;
      const fingertipJoint = isA ? Joint.FINGERTIPS_A : Joint.FINGERTIPS_P;
      const pWrist = camera.project(pose[wristJoint], width, height);
      const pPalm = camera.project(pose[palmJoint], width, height);
      const pFingertips = camera.project(pose[fingertipJoint], width, height);
      const corrected = compensator.compensateHandPerspective(pWrist, pPalm, pFingertips, camera);

      if (bone.a === Joint.WRIST_A || bone.a === Joint.WRIST_P) {
        p2 = corrected.palm;
      } else if (bone.a === Joint.PALM_A || bone.a === Joint.PALM_P) {
        p1 = corrected.palm;
        p2 = corrected.fingertips;
      }
    }

    const avgDepth = (p1.depth + p2.depth) / 2;
    const thickness = compensator.compensateThickness(bone.thickness, avgDepth);
    const isForeground = bone.color >= 1.0;

    items.push({ type: "bone", p1, p2, thickness, color: bone.color, depth: avgDepth, isForeground });
  }

  // Head
  const headProj = camera.project(pose[Joint.HEAD_POS], width, height);
  const headScale = compensator.compensateHeadScale(headProj.scale, headProj.depth);
  items.push({
    type: "head",
    p: Object.assign({}, headProj, { scale: headScale }),
    radius: style.headRadius,
    depth: headProj.depth,
    isForeground: false,
  });

  // Active hand indicator circle
  const wristAProj = camera.project(pose[Joint.WRIST_A], width, height);
  const handScale = compensator.compensateHandScale(wristAProj.scale, wristAProj.depth);
  items.push({
    type: "hand",
    p: Object.assign({}, wristAProj, { scale: handScale }),
    radius: style.jointRadius,
    depth: wristAProj.depth,
    isForeground: true,
  });

  // Torso box faces
  const hipF = pose[Joint.HIP_F];
  const hipB = pose[Joint.HIP_B];
  const shoulderA = pose[Joint.SHOULDER_A];
  const shoulderP = pose[Joint.SHOULDER_P];
  const pelvis = pose[Joint.PELVIS];
  const chest = pose[Joint.CHEST];

  const lean = chest.sub(pelvis).normalize();
  const shVec = shoulderA.sub(shoulderP).normalize();
  const chestNorm = lean.cross(shVec).normalize();

  const offC = chestNorm.scale(style.torsoChestDepth);
  const offH = chestNorm.scale(style.torsoHipDepth);

  const pHLf = camera.project(hipF.add(offH), width, height);
  const pHLb = camera.project(hipF.sub(offH), width, height);
  const pHRf = camera.project(hipB.add(offH), width, height);
  const pHRb = camera.project(hipB.sub(offH), width, height);
  const pSLf = camera.project(shoulderA.add(offC), width, height);
  const pSLb = camera.project(shoulderA.sub(offC), width, height);
  const pSRf = camera.project(shoulderP.add(offC), width, height);
  const pSRb = camera.project(shoulderP.sub(offC), width, height);

  const addFace = (pts) => {
    const avgDepth = pts.reduce((s, p) => s + p.depth, 0) / pts.length;
    items.push({ type: "face", pts, depth: avgDepth });
  };

  addFace([pSLf, pSRf, pHRf, pHLf]); // front
  addFace([pSRb, pSLb, pHLb, pHRb]); // back
  addFace([pSLb, pSLf, pHLf, pHLb]); // left
  addFace([pSRf, pSRb, pHRb, pHRf]); // right
  addFace([pSLb, pSRb, pSRf, pSLf]); // top
  addFace([pHLf, pHRf, pHRb, pHLb]); // bottom

  // Back-to-front (painter's algorithm), matching sortByDescending { depth }
  items.sort((a, b) => b.depth - a.depth);

  for (const item of items) {
    if (item.type === "bone") {
      const col = getZColor(item.depth, item.isForeground);
      const sc = (item.p1.scale + item.p2.scale) / 2;
      const thick = item.thickness * sc * camera.zoom;

      stroke(10, 15, 20);
      strokeWeight(thick + 4);
      line(item.p1.x, item.p1.y, item.p2.x, item.p2.y);

      stroke(col[0], col[1], col[2]);
      strokeWeight(thick);
      line(item.p1.x, item.p1.y, item.p2.x, item.p2.y);
    } else if (item.type === "head" || item.type === "hand") {
      const col = getZColor(item.depth, item.isForeground);
      const rad = item.radius * item.p.scale * camera.zoom;

      noStroke();
      fill(10, 15, 20);
      circle(item.p.x, item.p.y, (rad + 2) * 2);
      fill(col[0], col[1], col[2]);
      circle(item.p.x, item.p.y, rad * 2);
    } else if (item.type === "face") {
      const col = getZColor(item.depth, false);
      const strokeCol = col.map((c) => c * 0.6);
      const fillCol = col.map((c) => c * 0.9);

      stroke(strokeCol[0], strokeCol[1], strokeCol[2]);
      strokeWeight(style.outlineWidth);
      fill(fillCol[0], fillCol[1], fillCol[2]);
      beginShape();
      for (const p of item.pts) vertex(p.x, p.y);
      endShape(CLOSE);
    }
  }

  drawHud(progress);
}

function getZColor(depth, isForeground) {
  const t = constrain((170 - depth) / 340, 0, 1);
  const farColor = DEFAULT_STYLE.farColor;
  const nearColor = DEFAULT_STYLE.secondaryColor;
  const base = [
    lerp(farColor[0], nearColor[0], t),
    lerp(farColor[1], nearColor[1], t),
    lerp(farColor[2], nearColor[2], t),
  ];
  if (isForeground) {
    const primary = DEFAULT_STYLE.primaryColor;
    return [
      lerp(base[0], primary[0], 0.3),
      lerp(base[1], primary[1], 0.3),
      lerp(base[2], primary[2], 0.3),
    ];
  }
  return base;
}

function drawGround(pose) {
  stroke(58, 68, 92, 90);
  strokeWeight(1);
  for (let x = -260; x <= 260; x += 65) {
    const a = camera.project(new Vector3(x, 0, -170), width, height);
    const b = camera.project(new Vector3(x, 0, 170), width, height);
    line(a.x, a.y, b.x, b.y);
  }
  for (let z = -170; z <= 170; z += 65) {
    const a = camera.project(new Vector3(-260, 0, z), width, height);
    const b = camera.project(new Vector3(260, 0, z), width, height);
    line(a.x, a.y, b.x, b.y);
  }

  noStroke();
  fill(5, 8, 12, 150);
  // Ground-contact points that cast a shadow. Mirrors SkeletonRenderer.kt's
  // `shadowPoints` list exactly: both feet (heel + toe) plus only the
  // passive/back hand (HAND_P) — the active/front hand (HAND_A) is close
  // enough to the body's own cast shadow that it's intentionally omitted.
  const groundContactJoints = [Joint.TOE_F, Joint.TOE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.HAND_P];
  for (const id of groundContactJoints) {
    const pt = pose[id];
    if (!pt) continue;
    const p = camera.project(new Vector3(pt.x, 0, pt.z), width, height);
    const rx = style.shadowRadiusX * p.scale * camera.zoom;
    const ry = style.shadowRadiusY * p.scale * camera.zoom;
    ellipse(p.x, p.y, rx * 2, ry * 2);
  }
}

function drawHud(progress) {
  noStroke();
  fill(230, 235, 240);
  textSize(14);
  textAlign(LEFT, TOP);
  text("Push-Up (standard) — p5.js engine preview", 12, 12);
  text(`progress: ${progress.toFixed(2)}  (0 = up, 1 = down)`, 12, 32);
  text(paused ? "PAUSED (space to resume)" : "space: pause | drag: orbit | wheel: zoom", 12, 52);
}
