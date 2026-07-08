/**
 * sketch.js
 *
 * p5.js sketch that renders the push-up example animation using the
 * ported skeleton engine (`js/engine.js`) and pose builder
 * (`js/poses/pushup.js`). Mirrors the drawing logic of
 * `SkeletonRenderer.kt` closely enough for visual parity when testing
 * new exercise animations in the browser.
 *
 * Controls:
 *  - Drag horizontally: orbit camera (yaw)
 *  - Drag vertically: adjust camera pitch
 *  - Mouse wheel: zoom
 *  - Space: pause/resume the animation loop
 */

const {
  Vector3,
  Camera,
  PerspectiveCompensation,
  DEFAULT_ADULT,
  DEFAULT_STYLE,
  Joint,
  buildBones,
  SkeletonMath,
} = MonkEngine;

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
  const container = document.getElementById("canvas-holder");
  const canvas = createCanvas(container.clientWidth, container.clientHeight);
  canvas.parent("canvas-holder");
  lastMillis = millis();
}

function windowResized() {
  const container = document.getElementById("canvas-holder");
  resizeCanvas(container.clientWidth, container.clientHeight);
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
  const rawPose = MonkPoses.buildPushUpPose(context);
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
