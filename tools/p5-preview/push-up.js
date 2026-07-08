// Self-contained p5.js sketch for a looping push-up animation.
// Paste this file directly into the p5.js editor as the sketch.

let cycleDuration = 2.4;
let backgroundColor;
let primaryColor;
let accentColor;
let outlineColor;
let floorColor;

function setup() {
  createCanvas(900, 600);
  pixelDensity(1);
  angleMode(DEGREES);
  strokeCap(ROUND);
  strokeJoin(ROUND);
  noFill();

  backgroundColor = color(10, 16, 24);
  primaryColor = color(86, 214, 255);
  accentColor = color(255, 146, 103);
  outlineColor = color(4, 8, 12);
  floorColor = color(42, 53, 65);
}

function draw() {
  background(backgroundColor);

  const time = millis() / 1000;
  const phase = (time / cycleDuration) % 1;
  const t = phase < 0.5 ? phase * 2 : (1 - phase) * 2;
  const eased = smoothstep(constrain(t, 0, 1));
  const pose = buildPushUpPose(eased);

  push();
  translate(width * 0.5, height * 0.55);
  scale(1.15);

  drawGround();
  drawSkeleton(pose);

  pop();

  drawTitle();
}

function buildPushUpPose(progress) {
  const up = {
    pelvis: { x: 0, y: 86 },
    chest: { x: 0, y: 8 },
    shoulderL: { x: -74, y: -2 },
    shoulderR: { x: 74, y: -2 },
    elbowL: { x: -136, y: 74 },
    elbowR: { x: 136, y: 74 },
    handL: { x: -172, y: 148 },
    handR: { x: 172, y: 148 },
    neck: { x: 0, y: -34 },
    head: { x: 0, y: -96 },
    hip: { x: 0, y: 126 },
    kneeL: { x: -16, y: 210 },
    kneeR: { x: 16, y: 210 },
    footL: { x: -32, y: 300 },
    footR: { x: 32, y: 300 }
  };

  const down = {
    pelvis: { x: 0, y: 118 },
    chest: { x: 0, y: 72 },
    shoulderL: { x: -54, y: 60 },
    shoulderR: { x: 54, y: 60 },
    elbowL: { x: -92, y: 134 },
    elbowR: { x: 92, y: 134 },
    handL: { x: -116, y: 206 },
    handR: { x: 116, y: 206 },
    neck: { x: 0, y: 20 },
    head: { x: 0, y: -34 },
    hip: { x: 0, y: 170 },
    kneeL: { x: -12, y: 250 },
    kneeR: { x: 12, y: 250 },
    footL: { x: -24, y: 340 },
    footR: { x: 24, y: 340 }
  };

  const pose = {};
  for (const [key, value] of Object.entries(up)) {
    pose[key] = lerpPoint(value, down[key], progress);
  }
  return pose;
}

function drawSkeleton(pose) {
  const boneThickness = 16;

  // Torso.
  drawBone(pose.pelvis, pose.chest, boneThickness * 1.1, primaryColor);
  drawBone(pose.chest, pose.neck, boneThickness * 0.72, primaryColor);
  drawBone(pose.neck, pose.head, boneThickness * 0.56, accentColor);

  // Arms.
  drawBone(pose.shoulderL, pose.elbowL, boneThickness * 0.9, primaryColor);
  drawBone(pose.elbowL, pose.handL, boneThickness * 0.72, accentColor);
  drawBone(pose.shoulderR, pose.elbowR, boneThickness * 0.9, primaryColor);
  drawBone(pose.elbowR, pose.handR, boneThickness * 0.72, accentColor);

  // Legs.
  drawBone(pose.hip, pose.kneeL, boneThickness * 0.9, primaryColor);
  drawBone(pose.kneeL, pose.footL, boneThickness * 0.72, accentColor);
  drawBone(pose.hip, pose.kneeR, boneThickness * 0.9, primaryColor);
  drawBone(pose.kneeR, pose.footR, boneThickness * 0.72, accentColor);

  // Joints.
  drawJoint(pose.pelvis, 8, primaryColor);
  drawJoint(pose.chest, 10, accentColor);
  drawJoint(pose.shoulderL, 7, primaryColor);
  drawJoint(pose.shoulderR, 7, primaryColor);
  drawJoint(pose.elbowL, 8, accentColor);
  drawJoint(pose.elbowR, 8, accentColor);
  drawJoint(pose.handL, 9, accentColor);
  drawJoint(pose.handR, 9, accentColor);
  drawJoint(pose.neck, 7, primaryColor);
  drawJoint(pose.head, 10, accentColor);
  drawJoint(pose.hip, 8, primaryColor);
  drawJoint(pose.kneeL, 7, accentColor);
  drawJoint(pose.kneeR, 7, accentColor);
  drawJoint(pose.footL, 8, accentColor);
  drawJoint(pose.footR, 8, accentColor);
}

function drawBone(a, b, thickness, colorValue) {
  stroke(outlineColor);
  strokeWeight(thickness + 3.5);
  line(a.x, a.y, b.x, b.y);

  stroke(colorValue);
  strokeWeight(thickness);
  line(a.x, a.y, b.x, b.y);
}

function drawJoint(point, radius, colorValue) {
  stroke(outlineColor);
  strokeWeight(radius + 2);
  point(point.x, point.y);

  stroke(colorValue);
  strokeWeight(radius);
  point(point.x, point.y);
}

function drawGround() {
  noStroke();
  fill(floorColor);
  rect(-320, 320, 640, 46, 8);

  stroke(255, 255, 255, 20);
  strokeWeight(2);
  line(-300, 344, 300, 344);

  noStroke();
}

function drawTitle() {
  noStroke();
  fill(255, 255, 255, 180);
  textSize(18);
  textAlign(RIGHT, TOP);
  text('p5.js push-up preview', width - 26, 20);
}

function lerpPoint(a, b, t) {
  return {
    x: lerp(a.x, b.x, t),
    y: lerp(a.y, b.y, t)
  };
}

function smoothstep(x) {
  const t = constrain(x, 0, 1);
  return t * t * (3 - 2 * t);
}
