#!/usr/bin/env python3
"""Generate `humanoid_v2.glb`: the rig that passes A1 by construction.

The stance written here is the **X1 canonical A-pose** — arms down, every limb straightened, computed
from `DEFINITION` below, which carries the same numbers as the engine's `HumanSkeletonDefinition`.
It is not a T-pose and not an engine pose.

What the asset has to prove, in the order the rig contract lists it:

  * 23 mapped bone heads on the X1 engine joints (`position / s`, `s = pelvisY / 0.95`), so
    `RigLandmarkAlignmentTest` passes without a correction anywhere;
  * identity rest rotations on all 34 skin joints, because the engine's rest orientation is identity
    and A1 compares against it (`B⁻¹ · I · B = I`, never `B⁻¹ · F_root(bind) · B`);
  * 11 visual-only joints (crown + ten fingers) with real inverse binds, riding their parents;
  * no bone segment between the coincident girdle pivots (`Shoulder`/`Scapula` sit on `Chest`);
  * five morph targets, same names and order, re-banded to the new segment lengths;
  * a torso narrow enough in the coronal plane that the upper arms clear it in bind pose.

Run from anywhere:  python3 scripts/character/generate_humanoid_v2.py [--check]
Output: app/src/main/assets/characters/humanoid_v2.glb
"""

import argparse
import hashlib
import json
import math
import os
import struct
import sys

# --------------------------------------------------------------------- the one source of proportions
# JSON-shaped on purpose: GeneratorDefinitionAgreementTest parses this block and compares it with
# HumanSkeletonDefinition's defaults, so the generator cannot drift from the engine.
DEFINITION = {
    "torsoLength": 120.0,
    "neckLength": 18.0,
    "thighLength": 112.0,
    "shinLength": 98.0,
    "footLength": 35.0,
    "upperArmLength": 80.0,
    "forearmLength": 66.0,
    "shoulderWidth": 46.0,
    "hipWidth": 22.0,
    "palmLength": 12.0,
    "fingerLength": 10.0,
    "handWidth": 10.0,
    "heelRatio": 0.29,
    "toeRatio": 0.71,
    "pelvisLift": 25.0,
    "pelvisHeightMetres": 0.95,
    "headRadius": 18.0,
}

D = DEFINITION
PELVIS_Y = D["shinLength"] + D["thighLength"] + D["pelvisLift"]      # 235 engine units
UNITS_PER_METRE = PELVIS_Y / D["pelvisHeightMetres"]                # 247.3684 u/m
HEAD_Y = PELVIS_Y + D["torsoLength"] + 2.0 * D["neckLength"]        # 391
TAU_BIND_MM = 0.5


# --------------------------------------------------------------------- X1, derived not transcribed
def x1_engine():
    """The X1 A-pose in engine units: `+Y` up, `+X` forward, `+Z` lateral, side A on −Z."""
    pelvis = PELVIS_Y
    chest = pelvis + D["torsoLength"]
    neck = chest + D["neckLength"]
    head = neck + D["neckLength"]
    joints = {
        "PELVIS": (0.0, pelvis, 0.0),
        "LUMBAR": (0.0, pelvis, 0.0),
        "CHEST": (0.0, chest, 0.0),
        "NECK_END": (0.0, neck, 0.0),
        "HEAD_POS": (0.0, head, 0.0),
    }
    for side, lateral in (("A", -1.0), ("P", 1.0)):
        z = lateral * D["shoulderWidth"]
        elbow = chest - D["upperArmLength"]
        hand = elbow - D["forearmLength"]
        knuckles = hand - D["palmLength"]
        joints.update({
            "CLAVICLE_" + side: (0.0, chest, 0.0),
            "SCAPULA_" + side: (0.0, chest, 0.0),
            "SHOULDER_" + side: (0.0, chest, z),
            "ELBOW_" + side: (0.0, elbow, z),
            "HAND_" + side: (0.0, hand, z),
            "WRIST_" + side: (0.0, hand, z),
            "PALM_" + side: (0.0, hand - D["palmLength"] * 0.5, z),
            "KNUCKLES_" + side: (0.0, knuckles, z),
            "FINGERTIPS_" + side: (0.0, knuckles - D["fingerLength"], z),
        })
    for side, lateral in (("F", -1.0), ("B", 1.0)):
        z = lateral * D["hipWidth"]
        knee = pelvis - D["thighLength"]
        ankle = knee - D["shinLength"]
        joints.update({
            "HIP_" + side: (0.0, pelvis, z),
            "KNEE_" + side: (0.0, knee, z),
            "ANKLE_" + side: (0.0, ankle, z),
            # Both heels sit posterior to the ankle (`−foot · heelRatio`) and only the lateral axis
            # mirrors between sides; the toe mirrors the same way, anterior and level with the ankle.
            "HEEL_" + side: (-D["footLength"] * D["heelRatio"], ankle, z),
            "TOE_" + side: (D["footLength"] * D["toeRatio"], ankle, z),
        })
    return joints


X1 = x1_engine()


def to_asset(p):
    """`B⁻¹ · p / s` with `B = Ry(+90°)`: asset `+Z` forward, `+X` left."""
    x, y, z = p
    return (-z / UNITS_PER_METRE, y / UNITS_PER_METRE, x / UNITS_PER_METRE)


def x1_metres(joint):
    return to_asset(X1[joint])


# --------------------------------------------------------------------- nodes
# The 23 mapped bones keep the engine's chain: the girdle is CHEST → CLAVICLE → SCAPULA → SHOULDER.
MAPPED = [
    ("Hips", None, "PELVIS"),
    ("Spine", "Hips", "LUMBAR"),
    ("Chest", "Spine", "CHEST"),
    ("Neck", "Chest", "NECK_END"),
    ("Head", "Neck", "HEAD_POS"),
    ("LeftShoulder", "Chest", "CLAVICLE_A"),
    ("LeftScapula", "LeftShoulder", "SCAPULA_A"),
    ("LeftArm", "LeftScapula", "SHOULDER_A"),
    ("LeftForeArm", "LeftArm", "ELBOW_A"),
    ("LeftHand", "LeftForeArm", "HAND_A"),
    ("RightShoulder", "Chest", "CLAVICLE_P"),
    ("RightScapula", "RightShoulder", "SCAPULA_P"),
    ("RightArm", "RightScapula", "SHOULDER_P"),
    ("RightForeArm", "RightArm", "ELBOW_P"),
    ("RightHand", "RightForeArm", "HAND_P"),
    ("LeftUpLeg", "Hips", "HIP_F"),
    ("LeftLeg", "LeftUpLeg", "KNEE_F"),
    ("LeftFoot", "LeftLeg", "ANKLE_F"),
    ("LeftToeBase", "LeftFoot", "TOE_F"),
    ("RightUpLeg", "Hips", "HIP_B"),
    ("RightLeg", "RightUpLeg", "KNEE_B"),
    ("RightFoot", "RightLeg", "ANKLE_B"),
    ("RightToeBase", "RightFoot", "TOE_B"),
]

# The fingers are visual-only: single-segment, laid down the X1 hand direction from the hand head,
# spread across the palm width so the bind silhouette reads as an open hand.
# name, lateral offset (units), drop below the hand head (units), tube length (units), forward offset
FINGERS = [
    ("Thumb", 5.0, 4.0, 8.0, 6.0),
    ("Index", 3.0, 12.0, 10.0, 0.0),
    ("Middle", 1.0, 12.5, 11.0, 0.0),
    ("Ring", -1.0, 12.0, 10.0, 0.0),
    ("Pinky", -3.0, 11.0, 8.5, 0.0),
]

NODES = []          # (name, parent, position in asset metres)
MAPPED_BONES = []
for name, parent, joint in MAPPED:
    NODES.append((name, parent, x1_metres(joint)))
    MAPPED_BONES.append(name)

# The crown is not a joint: the engine's last joint is HEAD_POS and `SkeletonStyle.headRadius` puts
# the crown one radius above it.
NODES.append(("HeadTop_End", "Head",
              (0.0, (HEAD_Y + D["headRadius"]) / UNITS_PER_METRE, 0.0)))

VISUAL_ONLY = ["HeadTop_End"]
HAND_POS = {name: pos for name, _, pos in NODES if name in ("LeftHand", "RightHand")}
for side in ("Left", "Right"):
    hand = HAND_POS[side + "Hand"]
    for finger, lateral, drop, _, forward in FINGERS:
        name = side + "Hand" + finger
        NODES.append((
            name,
            side + "Hand",
            (hand[0] + lateral / UNITS_PER_METRE,
             hand[1] - drop / UNITS_PER_METRE,
             hand[2] + forward / UNITS_PER_METRE),
        ))
        VISUAL_ONLY.append(name)

NODE_NAMES = [n[0] for n in NODES]
NODE_INDEX = {name: i for i, name in enumerate(NODE_NAMES)}
NODE_PARENT = [(-1 if parent is None else NODE_INDEX[parent]) for _, parent, _ in NODES]
NODE_POS = [p for _, _, p in NODES]
POS_BY_NAME = {name: pos for name, _, pos in NODES}
assert len(NODES) == 34, len(NODES)
assert NODE_NAMES == MAPPED_BONES + VISUAL_ONLY, "node order must be 23 mapped then 11 visual-only"

# --------------------------------------------------------------------- geometry
RADIAL = 24
LENGTH = 24
HEAD_CENTER = x1_metres("HEAD_POS")
HEAD_RADIUS = D["headRadius"] / UNITS_PER_METRE


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def mul(a, s):
    return (a[0] * s, a[1] * s, a[2] * s)


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def length(a):
    return math.sqrt(dot(a, a))


def normalize(a):
    n = length(a)
    return (0.0, 0.0, 1.0) if n < 1e-9 else (a[0] / n, a[1] / n, a[2] / n)


def smoothstep(edge0, edge1, x):
    if edge1 == edge0:
        return 0.0
    t = max(0.0, min(1.0, (x - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)


def lerp(a, b, t):
    return a + (b - a) * t


def segment_frame(a, b):
    axis = normalize(sub(b, a))
    helper = (0.0, 1.0, 0.0) if abs(axis[1]) < 0.9 else (1.0, 0.0, 0.0)
    u = normalize(cross(axis, helper))
    v = normalize(cross(axis, u))
    return axis, u, v


class Mesh:
    def __init__(self):
        self.positions = []
        self.normals = []
        self.uvs = []
        self.joints = []
        self.weights = []
        self.indices = []
        self.groups = []  # (label, first vertex, last vertex)

    def vertex(self, p, n, uv, j0, j1, w1):
        self.positions.append(p)
        self.normals.append(n)
        self.uvs.append(uv)
        self.joints.append((j0, j1, 0, 0))
        self.weights.append((1.0 - w1, w1, 0.0, 0.0))
        return len(self.positions) - 1

    def tube(self, a, b, j0, j1, ra, rb, sx_a, sx_b, sz_a, sz_b, label=""):
        """Tapered elliptical tube from point `a` to point `b`; weights blend j0→j1 along it.

        `sx` scales the first cross-section axis and `sz` the second; for a vertical tube they are
        front-to-back depth and side-to-side width respectively.
        """
        axis, u, v = segment_frame(a, b)
        base = len(self.positions)
        for i in range(LENGTH + 1):
            t = i / LENGTH
            center = add(a, mul(sub(b, a), t))
            radius = lerp(ra, rb, t)
            sx = lerp(sx_a, sx_b, t)
            sz = lerp(sz_a, sz_b, t)
            for k in range(RADIAL + 1):
                ang = 2.0 * math.pi * k / RADIAL
                cos_a, sin_a = math.cos(ang), math.sin(ang)
                normal = normalize(add(mul(u, cos_a / sx), mul(v, sin_a / sz)))
                p = add(center, add(mul(u, cos_a * radius * sx), mul(v, sin_a * radius * sz)))
                w = smoothstep(0.25, 0.95, t)
                self.vertex(p, normal, (k / RADIAL, t), j0, j1, w)
        stride = RADIAL + 1
        for i in range(LENGTH):
            for k in range(RADIAL):
                p0 = base + i * stride + k
                p1 = p0 + 1
                p2 = p0 + stride
                p3 = p2 + 1
                self.indices.extend([p0, p2, p1, p1, p2, p3])
        self.groups.append((label, base, len(self.positions) - 1))

    def tube_between(self, name_a, name_b, ra, rb, sx_a, sx_b, sz_a, sz_b, label=""):
        self.tube(POS_BY_NAME[name_a], POS_BY_NAME[name_b],
                  NODE_INDEX[name_a], NODE_INDEX[name_b], ra, rb, sx_a, sx_b, sz_a, sz_b, label)

    def limb(self, name, offset_down, ra, rb, sx_a=1.0, sx_b=1.0, sz_a=1.0, sz_b=1.0, label=""):
        """Tube from a node down its own `−Y` by `offset_down` units, weighted to that node alone."""
        head = POS_BY_NAME[name]
        tail = (head[0], head[1] - offset_down / UNITS_PER_METRE, head[2])
        self.tube(head, tail, NODE_INDEX[name], NODE_INDEX[name],
                  ra, rb, sx_a, sx_b, sz_a, sz_b, label)

    def sphere(self, center, radius, joint, rings=16, segments=24):
        base = len(self.positions)
        j = NODE_INDEX[joint]
        for r in range(rings + 1):
            phi = math.pi * r / rings
            for s in range(segments + 1):
                theta = 2.0 * math.pi * s / segments
                n = (math.sin(phi) * math.cos(theta), math.cos(phi), math.sin(phi) * math.sin(theta))
                self.vertex(add(center, mul(n, radius)), n, (s / segments, r / rings), j, j, 0.0)
        stride = segments + 1
        for r in range(rings):
            for s in range(segments):
                p0 = base + r * stride + s
                p1 = p0 + 1
                p2 = p0 + stride
                p3 = p2 + 1
                self.indices.extend([p0, p2, p1, p1, p2, p3])
        self.groups.append(("head", base, len(self.positions) - 1))


M = Mesh()

# Torso: depth (sx) tapers 0.143 m → 0.105 m, width (sz) 0.111 m → 0.110 m. The chest end is kept
# near 0.110 m half-width because the upper arms sit at x = ±0.1860 m with a 0.058 m radius.
M.tube_between("Spine", "Chest", 0.130, 0.100, 1.10, 1.05, 0.85, 1.10, "torso")
M.tube_between("Chest", "Neck", 0.100, 0.055, 1.05, 0.95, 1.10, 0.85, "torso")
M.tube_between("Neck", "Head", 0.050, 0.046, 1.00, 1.00, 1.00, 1.00, "neck")

for side in ("Left", "Right"):
    # Girdle: no segment between the coincident Chest/Shoulder/Scapula pivots; one from the scapula
    # out to the arm, 0.1860 m, so the shoulder mass exists.
    M.tube_between(side + "Scapula", side + "Arm", 0.062, 0.058, 1.00, 1.00, 1.00, 1.00, "girdle")
    M.tube_between(side + "Arm", side + "ForeArm", 0.058, 0.045, 1.00, 1.00, 1.00, 1.00, "arm:" + side)
    M.tube_between(side + "ForeArm", side + "Hand", 0.045, 0.028, 1.00, 1.00, 1.00, 1.00, "arm:" + side)
    # Palm from the wrist down the hand direction to the knuckle line.
    M.limb(side + "Hand", D["palmLength"], 0.028, 0.026, 1.30, 1.30, 1.00, 1.00, "palm")
    for finger, _, drop, tube_units, _ in FINGERS:
        M.limb(side + "Hand" + finger, tube_units, 0.011, 0.008, label="finger")
    M.tube_between(side + "UpLeg", side + "Leg", 0.092, 0.068, 1.00, 1.00, 1.00, 1.00, "leg")
    M.tube_between(side + "Leg", side + "Foot", 0.068, 0.046, 1.00, 1.00, 1.00, 1.00, "leg")
    M.tube_between(side + "Foot", side + "ToeBase", 0.046, 0.030, 1.10, 1.00, 1.00, 1.00, "foot")

M.sphere(HEAD_CENTER, HEAD_RADIUS, "Head")

TRIANGLES = len(M.indices) // 3

# --------------------------------------------------------------------- morph targets
# Same five names, same order; every band and amplitude is a fraction of a definition length rather
# than a coordinate carried over from v1's proportions.
MORPHS = ["ShoulderWidth", "ChestVolume", "WaistWidth", "LimbThickness", "HeadScale"]

CHEST_Y_M = x1_metres("CHEST")[1]
NECK_Y_M = x1_metres("NECK_END")[1]
PELVIS_Y_M = x1_metres("PELVIS")[1]
ELBOW_Y_M = x1_metres("ELBOW_A")[1]

SHOULDER_DELTA = 0.30 * D["shoulderWidth"] / UNITS_PER_METRE
CHEST_DELTA = 0.10 * D["torsoLength"] / UNITS_PER_METRE
WAIST_DELTA = 0.11 * D["torsoLength"] / UNITS_PER_METRE
LIMB_DELTA = 0.07 * D["upperArmLength"] / UNITS_PER_METRE
HEAD_DELTA = 0.10


def morph_deltas(p):
    x, y, z = p
    radial = normalize((x, 0.0, z))
    out = []

    # Shoulder width: the girdle and upper arms move outwards across the shoulder band.
    w = (smoothstep(0.10, 0.24, abs(x))
         * smoothstep(CHEST_Y_M - 0.055, CHEST_Y_M - 0.010, y)
         * (1.0 - smoothstep(CHEST_Y_M + 0.010, CHEST_Y_M + 0.045, y)))
    out.append((math.copysign(SHOULDER_DELTA * w, x) if abs(x) > 1e-6 else 0.0, 0.0, 0.0))

    # Chest volume: radial expansion of the rib cage between the lumbar top and the neck.
    w = (smoothstep(PELVIS_Y_M + 0.28 * (CHEST_Y_M - PELVIS_Y_M), CHEST_Y_M, y)
         * (1.0 - smoothstep(CHEST_Y_M, NECK_Y_M, y))
         * smoothstep(0.03, 0.12, abs(x) + abs(z)))
    out.append((radial[0] * CHEST_DELTA * w, 0.0, radial[2] * CHEST_DELTA * w))

    # Waist: radial expansion around the lumbar band, below the chest mask.
    w = (smoothstep(PELVIS_Y_M, PELVIS_Y_M + 0.15 * (CHEST_Y_M - PELVIS_Y_M), y)
         * (1.0 - smoothstep(PELVIS_Y_M + 0.40 * (CHEST_Y_M - PELVIS_Y_M), CHEST_Y_M, y)))
    out.append((radial[0] * WAIST_DELTA * w, 0.0, radial[2] * WAIST_DELTA * w))

    # Limb thickness: arms and legs thicken around their own axis.
    arm = smoothstep(0.20, 0.30, abs(x)) * smoothstep(ELBOW_Y_M, CHEST_Y_M, y)
    leg = (1.0 - smoothstep(PELVIS_Y_M - 0.55 * D["thighLength"] / UNITS_PER_METRE,
                            PELVIS_Y_M - 0.30 * D["thighLength"] / UNITS_PER_METRE, y)) \
        * smoothstep(0.02, 0.06, abs(x))
    w = max(arm, leg)
    out.append((radial[0] * LIMB_DELTA * w, 0.0, radial[2] * LIMB_DELTA * w))

    # Head scale about its own centre.
    d = sub(p, HEAD_CENTER)
    w = 1.0 - smoothstep(HEAD_RADIUS * 0.9, HEAD_RADIUS * 1.15, length(d))
    out.append(mul(d, HEAD_DELTA * w))

    return out


MORPH_DELTAS = [morph_deltas(p) for p in M.positions]

# --------------------------------------------------------------------- self-checks
def torso_arm_clearance():
    """Minimum coronal gap between the torso surface and an arm's inner surface, in metres.

    Binned by height over the shoulder region only: below it the arms have left the torso, above it
    the chest has tapered to the neck. A negative result means the bind pose interpenetrates.
    """
    torso_x = {}
    arm_x = {"Left": {}, "Right": {}}
    bin_m = 0.004
    for label, lo, hi in M.groups:
        if label == "torso":
            target = torso_x
        elif label.startswith("arm:"):
            target = arm_x[label.split(":", 1)[1]]
        else:
            continue
        for p in M.positions[lo:hi + 1]:
            k = int(p[1] / bin_m)
            if target is torso_x:
                target[k] = max(target.get(k, 0.0), abs(p[0]))
            else:
                target[k] = min(target.get(k, 1e9), abs(p[0]))
    lo = int((CHEST_Y_M - 0.03) / bin_m)
    hi = int((CHEST_Y_M + 0.02) / bin_m)
    worst = None
    for side in ("Left", "Right"):
        for k in range(lo, hi + 1):
            if k in torso_x and k in arm_x[side]:
                gap = arm_x[side][k] - torso_x[k]
                worst = gap if worst is None else min(worst, gap)
    return worst


def bind_alignment():
    """A1's position row, recomputed here: every mapped head within `TAU_BIND_MM` of its X1 joint."""
    worst = 0.0
    for name, _, joint in MAPPED:
        worst = max(worst, length(sub(POS_BY_NAME[name], x1_metres(joint))))
    return worst


CLEARANCE = torso_arm_clearance()
BIND_RESIDUAL = bind_alignment()
BIND_TOLERANCE_M = TAU_BIND_MM / 1000.0


# --------------------------------------------------------------------- binary buffer
buffer = bytearray()
views = []
accessors = []


def align():
    while len(buffer) % 4:
        buffer.append(0)


def add_view(data, target=None):
    align()
    offset = len(buffer)
    buffer.extend(data)
    view = {"buffer": 0, "byteOffset": offset, "byteLength": len(data)}
    if target is not None:
        view["target"] = target
    views.append(view)
    return len(views) - 1


def add_accessor(view, component_type, count, type_, min_=None, max_=None):
    acc = {"bufferView": view, "componentType": component_type, "count": count, "type": type_}
    if min_ is not None:
        acc["min"] = min_
    if max_ is not None:
        acc["max"] = max_
    accessors.append(acc)
    return len(accessors) - 1


def pack(fmt, values):
    return struct.pack("<" + fmt * len(values), *values)


index_view = add_view(pack("H", M.indices), 34963)
index_accessor = add_accessor(index_view, 5123, len(M.indices), "SCALAR")

position_view = add_view(pack("f", [c for p in M.positions for c in p]), 34962)
position_accessor = add_accessor(
    position_view, 5126, len(M.positions), "VEC3",
    [min(p[i] for p in M.positions) for i in range(3)],
    [max(p[i] for p in M.positions) for i in range(3)],
)

normal_view = add_view(pack("f", [c for n in M.normals for c in n]), 34962)
normal_accessor = add_accessor(normal_view, 5126, len(M.normals), "VEC3")

uv_view = add_view(pack("f", [c for uv in M.uvs for c in uv]), 34962)
uv_accessor = add_accessor(uv_view, 5126, len(M.uvs), "VEC2")

joints_view = add_view(bytes(b for j in M.joints for b in j), 34962)
joints_accessor = add_accessor(joints_view, 5121, len(M.joints), "VEC4")

weights_view = add_view(pack("f", [w for ws in M.weights for w in ws]), 34962)
weights_accessor = add_accessor(weights_view, 5126, len(M.weights), "VEC4")

morph_accessors = []
for m in range(len(MORPHS)):
    flat = []
    for deltas in MORPH_DELTAS:
        flat.extend(deltas[m])
    view = add_view(pack("f", flat), 34962)
    dmin = [min(deltas[m][i] for deltas in MORPH_DELTAS) for i in range(3)]
    dmax = [max(deltas[m][i] for deltas in MORPH_DELTAS) for i in range(3)]
    morph_accessors.append(add_accessor(view, 5126, len(M.positions), "VEC3", dmin, dmax))

# --------------------------------------------------------------------- ground (shadow receiver)
# The engine's ankle sits 25 units above its declared ground, so the sole is not at y = 0: the plane
# is placed on the mesh's own lowest vertex instead of leaving the figure floating over it.
GROUND_Y = min(p[1] for p in M.positions)
GROUND_HALF = 8.0
ground_positions = [
    (-GROUND_HALF, GROUND_Y, -GROUND_HALF),
    (GROUND_HALF, GROUND_Y, -GROUND_HALF),
    (GROUND_HALF, GROUND_Y, GROUND_HALF),
    (-GROUND_HALF, GROUND_Y, GROUND_HALF),
]
ground_normals = [(0.0, 1.0, 0.0)] * 4
ground_uvs = [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]
ground_indices = [0, 2, 1, 0, 3, 2]

ground_index_view = add_view(pack("H", ground_indices), 34963)
ground_index_accessor = add_accessor(ground_index_view, 5123, len(ground_indices), "SCALAR")
ground_position_view = add_view(pack("f", [c for p in ground_positions for c in p]), 34962)
ground_position_accessor = add_accessor(
    ground_position_view, 5126, len(ground_positions), "VEC3",
    [min(p[i] for p in ground_positions) for i in range(3)],
    [max(p[i] for p in ground_positions) for i in range(3)],
)
ground_normal_view = add_view(pack("f", [c for n in ground_normals for c in n]), 34962)
ground_normal_accessor = add_accessor(ground_normal_view, 5126, len(ground_normals), "VEC3")
ground_uv_view = add_view(pack("f", [c for uv in ground_uvs for c in uv]), 34962)
ground_uv_accessor = add_accessor(ground_uv_view, 5126, len(ground_uvs), "VEC2")

while len(buffer) % 4:
    buffer.append(0)

# --------------------------------------------------------------------- nodes, skin, scene
nodes = []
node_index = {}


def joint_node(i):
    parent = NODE_PARENT[i]
    # Translation-only TRS: every rest rotation stays identity, which is what the engine's rest
    # orientation is and therefore what A1's orientation row compares against.
    x, y, z = NODE_POS[i]
    if parent >= 0:
        px, py, pz = NODE_POS[parent]
        translation = [x - px, y - py, z - pz]
    else:
        translation = [x, y, z]
    node = {"name": NODE_NAMES[i], "translation": translation}
    child_indices = [joint_node(c) for c in range(len(NODE_NAMES)) if NODE_PARENT[c] == i]
    if child_indices:
        node["children"] = child_indices
    nodes.append(node)
    index = len(nodes) - 1
    node_index[NODE_NAMES[i]] = index
    return index


armature_index = len(nodes)
nodes.append({"name": "Armature"})
hips_node = joint_node(0)
nodes[armature_index]["children"] = [hips_node]

mesh_node = len(nodes)
nodes.append({"name": "HumanoidMesh", "mesh": 0, "skin": 0})

ground_node = len(nodes)
nodes.append({"name": "Ground", "mesh": 1})

# Inverse bind = inverse of the joint's rest world transform; with identity rest rotations and unit
# scale that is a pure translation for all 34 joints, coincident girdle pivots included.
inverse_binds = []
for (x, y, z) in NODE_POS:
    inverse_binds.extend([
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        -x, -y, -z, 1.0,
    ])
ibm_view = add_view(pack("f", inverse_binds))
ibm_accessor = add_accessor(ibm_view, 5126, len(NODE_NAMES), "MAT4")

gltf = {
    "asset": {
        "version": "2.0",
        "generator": "monk-fitness humanoid_v2 generator (X1 A-pose from SkeletonDefinition)",
        "copyright": "Generated for the Monk Fitness character runtime; no third-party art.",
    },
    "scene": 0,
    "scenes": [{"name": "Humanoid", "nodes": [armature_index, mesh_node, ground_node]}],
    "nodes": nodes,
    "meshes": [{
        "name": "HumanoidBody",
        "weights": [0.0] * len(MORPHS),
        "extras": {"targetNames": MORPHS},
        "primitives": [{
            "attributes": {
                "POSITION": position_accessor,
                "NORMAL": normal_accessor,
                "TEXCOORD_0": uv_accessor,
                "JOINTS_0": joints_accessor,
                "WEIGHTS_0": weights_accessor,
            },
            "indices": index_accessor,
            "material": 0,
            "mode": 4,
            "targets": [{"POSITION": a} for a in morph_accessors],
        }],
    }, {
        "name": "Ground",
        "primitives": [{
            "attributes": {
                "POSITION": ground_position_accessor,
                "NORMAL": ground_normal_accessor,
                "TEXCOORD_0": ground_uv_accessor,
            },
            "indices": ground_index_accessor,
            "material": 1,
            "mode": 4,
        }],
    }],
    "skins": [{
        "name": "HumanoidRig",
        "joints": [node_index[n] for n in NODE_NAMES],
        "inverseBindMatrices": ibm_accessor,
        "skeleton": node_index["Hips"],
    }],
    "materials": [{
        "name": "Skin",
        "pbrMetallicRoughness": {
            "baseColorFactor": [0.78, 0.66, 0.58, 1.0],
            "metallicFactor": 0.0,
            "roughnessFactor": 0.55,
        },
    }, {
        "name": "Ground",
        "doubleSided": True,
        "pbrMetallicRoughness": {
            "baseColorFactor": [0.16, 0.17, 0.19, 1.0],
            "metallicFactor": 0.0,
            "roughnessFactor": 0.95,
        },
    }],
    "accessors": accessors,
    "bufferViews": views,
    "buffers": [{"byteLength": len(buffer)}],
}

def emitted_ibm_residual():
    """`IBM · rest_world = I` on the built scene graph, accumulated through the node chain."""
    world = {}

    def walk(index, parent_xyz):
        node = nodes[index]
        t = node.get("translation", [0.0, 0.0, 0.0])
        here = (parent_xyz[0] + t[0], parent_xyz[1] + t[1], parent_xyz[2] + t[2])
        world[index] = here
        for child in node.get("children", []):
            walk(child, here)

    for root in gltf["scenes"][0]["nodes"]:
        walk(root, (0.0, 0.0, 0.0))

    worst = 0.0
    for j, node_index_value in enumerate(gltf["skins"][0]["joints"]):
        wx, wy, wz = world[node_index_value]
        ibm = inverse_binds[j * 16:(j + 1) * 16]
        # Translation column of IBM (column-major, last four floats) applied as −world.
        worst = max(worst, abs(ibm[12] + wx), abs(ibm[13] + wy), abs(ibm[14] + wz))
        # Rotation block must be the identity, which is what A1 compares the rest orientation to.
        for k in range(11):
            expected = 1.0 if k in (0, 5, 10) else 0.0
            worst = max(worst, abs(ibm[k] - expected))
    return worst


EMITTED_IBM_RESIDUAL = emitted_ibm_residual()

CHECKS = []
if not 30_000 <= TRIANGLES <= 40_000:
    CHECKS.append("triangle count %d outside 30000..40000" % TRIANGLES)
if BIND_RESIDUAL > BIND_TOLERANCE_M:
    CHECKS.append("A1 position residual %.6f m exceeds %.6f m" % (BIND_RESIDUAL, BIND_TOLERANCE_M))
if CLEARANCE is None or CLEARANCE < 0.010:
    CHECKS.append("torso/arm clearance %s below 10 mm" % CLEARANCE)
if EMITTED_IBM_RESIDUAL > 1e-6:
    CHECKS.append("inverse bind is not the inverse rest world (%.3e)" % EMITTED_IBM_RESIDUAL)
if NODE_NAMES != MAPPED_BONES + VISUAL_ONLY:
    CHECKS.append("node order is not 23 mapped + 11 visual-only")
if [n.get("rotation") for n in nodes if n.get("rotation")]:
    CHECKS.append("a node carries a rest rotation")

json_chunk = json.dumps(gltf, separators=(",", ":"), sort_keys=False).encode("utf-8")
while len(json_chunk) % 4:
    json_chunk += b" "

total = 12 + 8 + len(json_chunk) + 8 + len(buffer)
glb = bytearray()
glb.extend(struct.pack("<III", 0x46546C67, 2, total))
glb.extend(struct.pack("<II", len(json_chunk), 0x4E4F534A))
glb.extend(json_chunk)
glb.extend(struct.pack("<II", len(buffer), 0x004E4942))
glb.extend(buffer)

# --------------------------------------------------------------------- write
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "characters", "humanoid_v2.glb")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true",
                        help="regenerate in memory and require a byte-identical sha256 against the committed asset")
    args = parser.parse_args(argv)

    digest = hashlib.sha256(glb).hexdigest()
    print("humanoid_v2  joints %d (23 mapped + %d visual-only)  triangles %d  vertices %d  morphs %d"
          % (len(NODES), len(VISUAL_ONLY), TRIANGLES, len(M.positions), len(MORPHS)))
    print("  unitScale            %.6f m/u   (s = %.4f u/m)" % (1.0 / UNITS_PER_METRE, UNITS_PER_METRE))
    print("  torso/arm clearance  %s"
          % ("n/a" if CLEARANCE is None else "%.2f mm" % (CLEARANCE * 1000.0)))
    print("  A1 worst position    %.6f m  (tolerance %.6f m)"
          % (BIND_RESIDUAL, BIND_TOLERANCE_M))
    print("  sha256               %s" % digest)

    if CHECKS:
        print("FAIL: " + "; ".join(CHECKS))
        return 1

    if args.check:
        if not os.path.exists(OUT):
            print("FAIL: %s is missing" % OUT)
            return 1
        with open(OUT, "rb") as f:
            on_disk = hashlib.sha256(f.read()).hexdigest()
        if on_disk != digest:
            print("FAIL: %s is stale (on disk %s, generator writes %s)" % (OUT, on_disk, digest))
            return 1
        print("OK: %s matches the generator byte for byte" % os.path.basename(OUT))
        return 0

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(glb)
    print("wrote %s (%d bytes, %.2f MB)" % (OUT, total, total / 1024.0 / 1024.0))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
