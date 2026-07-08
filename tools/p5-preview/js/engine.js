/**
 * engine.js
 *
 * A p5.js-compatible JavaScript port of the Kotlin skeleton animation engine
 * found in `app/src/main/java/com/monkfitness/app/animation`. It mirrors:
 *   - Vector3 (SkeletonMath.kt)
 *   - IKConstraint / solveIK (SkeletonMath.kt)
 *   - Easing helpers (SkeletonMath.kt)
 *   - Camera (Camera.kt)
 *   - FootDefinition (FootDefinition.kt)
 *   - HandDefinition (HandDefinition.kt)
 *   - SkeletonDefinition (SkeletonDefinition.kt)
 *   - SkeletonStyle (SkeletonStyle.kt)
 *   - Joint enum (Joint.kt)
 *   - Bone list (SkeletonEngine.kt)
 *   - PerspectiveCompensation (PerspectiveCompensation.kt)
 *
 * The goal is to let exercise animations be prototyped/tested in the browser
 * with p5.js before being ported into the native Kotlin PoseBuilder classes.
 */
(function (global) {
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

    // Standard Ease-In-Out Quintic
    easeIO(x) {
      const cx = Math.min(1, Math.max(0, x));
      return cx * cx * cx * (cx * (cx * 6 - 15) + 10);
    },

    // Reference Implementation Ease-In-Out (Quadric)
    easeInOut(x) {
      return x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2;
    },

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
  // IK Constraint + solver (Bone.kt / SkeletonMath.kt)
  // ---------------------------------------------------------------------
  const IKConstraint = {
    ArmConstraint: { minimumFlexionAngle: 30, maximumExtensionRatio: 0.95 },
    LegConstraint: { minimumFlexionAngle: 5, maximumExtensionRatio: 0.98 },
  };

  /**
   * Analytical 2-bone IK with biological clamps.
   * Mirrors SkeletonMath.solveIK from the Kotlin engine.
   */
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
  // Foot / Hand definitions (FootDefinition.kt / HandDefinition.kt)
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
  // SkeletonDefinition (SkeletonDefinition.kt)
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
  // SkeletonStyle (SkeletonStyle.kt)
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
  // Joint identifiers (Joint.kt)
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
  // Bone list builder (SkeletonEngine.kt)
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
  // Camera (Camera.kt)
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
  // PerspectiveCompensation (PerspectiveCompensation.kt)
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
  // Public API
  // ---------------------------------------------------------------------
  global.MonkEngine = {
    Vector3,
    SkeletonMath,
    IKConstraint,
    solveIK,
    FootDefinition,
    HandDefinition,
    createSkeletonDefinition,
    DEFAULT_ADULT,
    DEFAULT_STYLE,
    Joint,
    buildBones,
    Camera,
    PerspectiveCompensation,
  };
})(typeof window !== "undefined" ? window : globalThis);
