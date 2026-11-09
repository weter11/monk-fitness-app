#!/usr/bin/env python3
"""Generate the spike's skinned humanoid GLB (rig + mesh + five morph targets).

The asset exists to test the runtime, not to look good: tubes for limbs, a sphere for the head,
one PBR material, no textures. What it does have is exactly what the spike needs to prove:

  * 33 joints whose names match the engine's resolver (Mixamo-style names),
  * inverse bind matrices derived from the same rest pose the mesh is built in,
  * real skin weights that blend across every joint,
  * five morph targets (shoulder width, chest volume, waist, limb thickness, head scale) that the
    runtime can combine into the body-shape presets the architecture proposes.

Run from the repository root:  python3 spike-humanoid/tools/generate_humanoid_glb.py
Output: spike-humanoid/src/main/assets/humanoid_spike.glb
"""

import json
import math
import os
import struct

OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src", "main", "assets", "humanoid_spike.glb",
)

RADIAL = 24
LENGTH = 24

# ---------------------------------------------------------------- skeleton (T-pose, metres, +Z forward)

JOINTS = [
    # name, parent, x, y, z
    ("Hips", None, 0.00, 0.95, 0.00),
    ("Spine", "Hips", 0.00, 1.06, 0.00),
    ("Chest", "Spine", 0.00, 1.26, 0.00),
    ("Neck", "Chest", 0.00, 1.50, 0.00),
    ("Head", "Neck", 0.00, 1.60, 0.00),
    ("LeftShoulder", "Chest", 0.06, 1.44, 0.00),
    ("LeftArm", "LeftShoulder", 0.175, 1.415, 0.00),
    ("LeftForeArm", "LeftArm", 0.205, 1.145, 0.015),
    ("LeftHand", "LeftForeArm", 0.220, 0.885, 0.030),
    ("LeftHandThumb", "LeftHand", 0.245, 0.815, 0.070),
    ("LeftHandIndex", "LeftHand", 0.235, 0.790, 0.045),
    ("LeftHandMiddle", "LeftHand", 0.232, 0.780, 0.025),
    ("LeftHandRing", "LeftHand", 0.230, 0.785, 0.005),
    ("LeftHandPinky", "LeftHand", 0.225, 0.800, -0.015),
    ("RightShoulder", "Chest", -0.06, 1.44, 0.00),
    ("RightArm", "RightShoulder", -0.175, 1.415, 0.00),
    ("RightForeArm", "RightArm", -0.205, 1.145, 0.015),
    ("RightHand", "RightForeArm", -0.220, 0.885, 0.030),
    ("RightHandThumb", "RightHand", -0.245, 0.815, 0.070),
    ("RightHandIndex", "RightHand", -0.235, 0.790, 0.045),
    ("RightHandMiddle", "RightHand", -0.232, 0.780, 0.025),
    ("RightHandRing", "RightHand", -0.230, 0.785, 0.005),
    ("RightHandPinky", "RightHand", -0.225, 0.800, -0.015),
    ("LeftUpLeg", "Hips", 0.10, 0.92, 0.00),
    ("LeftLeg", "LeftUpLeg", 0.10, 0.50, 0.00),
    ("LeftFoot", "LeftLeg", 0.10, 0.08, 0.00),
    ("LeftToeBase", "LeftFoot", 0.10, 0.03, 0.12),
    ("RightUpLeg", "Hips", -0.10, 0.92, 0.00),
    ("RightLeg", "RightUpLeg", -0.10, 0.50, 0.00),
    ("RightFoot", "RightLeg", -0.10, 0.08, 0.00),
    ("RightToeBase", "RightFoot", -0.10, 0.03, 0.12),
    ("HeadTop_End", "Head", 0.00, 1.78, 0.00),
]

JOINT_NAMES = [j[0] for j in JOINTS]
JOINT_INDEX = {name: i for i, name in enumerate(JOINT_NAMES)}
JOINT_POS = [(j[2], j[3], j[4]) for j in JOINTS]
JOINT_PARENT = [(-1 if j[1] is None else JOINT_INDEX[j[1]]) for j in JOINTS]

# ---------------------------------------------------------------- geometry

# name, joint a, joint b, radius a, radius b, elliptical x-scale, z-scale, weight b at t=1
SEGMENTS = [
    ("Hips", "Spine", 0.130, 0.120, 1.25, 0.80),
    ("Spine", "Chest", 0.120, 0.145, 1.35, 0.80),
    ("Chest", "Neck", 0.145, 0.060, 1.10, 0.85),
    ("Neck", "Head", 0.055, 0.050, 1.00, 1.00),
    ("LeftShoulder", "LeftArm", 0.070, 0.065, 1.00, 1.00),
    ("LeftArm", "LeftForeArm", 0.065, 0.050, 1.00, 1.00),
    ("LeftForeArm", "LeftHand", 0.050, 0.035, 1.00, 1.00),
    ("LeftHand", "LeftHandIndex", 0.022, 0.012, 1.00, 1.00),
    ("LeftHand", "LeftHandMiddle", 0.022, 0.012, 1.00, 1.00),
    ("LeftHand", "LeftHandRing", 0.020, 0.011, 1.00, 1.00),
    ("LeftHand", "LeftHandPinky", 0.018, 0.010, 1.00, 1.00),
    ("LeftHand", "LeftHandThumb", 0.020, 0.011, 1.00, 1.00),
    ("RightShoulder", "RightArm", 0.070, 0.065, 1.00, 1.00),
    ("RightArm", "RightForeArm", 0.065, 0.050, 1.00, 1.00),
    ("RightForeArm", "RightHand", 0.050, 0.035, 1.00, 1.00),
    ("RightHand", "RightHandIndex", 0.022, 0.012, 1.00, 1.00),
    ("RightHand", "RightHandMiddle", 0.022, 0.012, 1.00, 1.00),
    ("RightHand", "RightHandRing", 0.020, 0.011, 1.00, 1.00),
    ("RightHand", "RightHandPinky", 0.018, 0.010, 1.00, 1.00),
    ("RightHand", "RightHandThumb", 0.020, 0.011, 1.00, 1.00),
    ("LeftUpLeg", "LeftLeg", 0.095, 0.070, 1.00, 1.00),
    ("LeftLeg", "LeftFoot", 0.070, 0.048, 1.00, 1.00),
    ("LeftFoot", "LeftToeBase", 0.048, 0.030, 1.10, 1.00),
    ("RightUpLeg", "RightLeg", 0.095, 0.070, 1.00, 1.00),
    ("RightLeg", "RightFoot", 0.070, 0.048, 1.00, 1.00),
    ("RightFoot", "RightToeBase", 0.048, 0.030, 1.10, 1.00),
]

HEAD_CENTER = (0.0, 1.70, 0.0)
HEAD_RADIUS = 0.115


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
    t = max(0.0, min(1.0, (x - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)


def segment_frame(a, b):
    axis = normalize(sub(b, a))
    helper = (0.0, 1.0, 0.0) if abs(axis[1]) < 0.9 else (1.0, 0.0, 0.0)
    u = normalize(cross(axis, helper))
    v = normalize(cross(axis, u))
    return axis, u, v


POSITIONS = []
NORMALS = []
UVS = []
JOINTS = []
WEIGHTS = []
INDICES = []


def emit_vertex(p, n, uv, j0, j1, w1):
    POSITIONS.append(p)
    NORMALS.append(n)
    UVS.append(uv)
    JOINTS.append((j0, j1, 0, 0))
    WEIGHTS.append((1.0 - w1, w1, 0.0, 0.0))
    return len(POSITIONS) - 1


def emit_tube(joint_a, joint_b, ra, rb, sx, sz):
    a = JOINT_POS[JOINT_INDEX[joint_a]]
    b = JOINT_POS[JOINT_INDEX[joint_b]]
    axis, u, v = segment_frame(a, b)
    base = len(POSITIONS)
    ja, jb = JOINT_INDEX[joint_a], JOINT_INDEX[joint_b]
    for i in range(LENGTH + 1):
        t = i / LENGTH
        center = add(a, mul(sub(b, a), t))
        radius = ra + (rb - ra) * t
        for k in range(RADIAL + 1):
            ang = 2.0 * math.pi * k / RADIAL
            cos_a, sin_a = math.cos(ang), math.sin(ang)
            normal = normalize(add(mul(u, cos_a / sx), mul(v, sin_a / sz)))
            p = add(center, add(mul(u, cos_a * radius * sx), mul(v, sin_a * radius * sz)))
            w = smoothstep(0.25, 0.95, t)
            emit_vertex(p, normal, (k / RADIAL, t), ja, jb, w)
    stride = RADIAL + 1
    for i in range(LENGTH):
        for k in range(RADIAL):
            p0 = base + i * stride + k
            p1 = p0 + 1
            p2 = p0 + stride
            p3 = p2 + 1
            INDICES.extend([p0, p2, p1, p1, p2, p3])


def emit_sphere(center, radius, rings=16, segments=24):
    base = len(POSITIONS)
    head_joint = JOINT_INDEX["Head"]
    for r in range(rings + 1):
        phi = math.pi * r / rings
        for s in range(segments + 1):
            theta = 2.0 * math.pi * s / segments
            n = (math.sin(phi) * math.cos(theta), math.cos(phi), math.sin(phi) * math.sin(theta))
            p = add(center, mul(n, radius))
            emit_vertex(p, n, (s / segments, r / rings), head_joint, head_joint, 0.0)
    stride = segments + 1
    for r in range(rings):
        for s in range(segments):
            p0 = base + r * stride + s
            p1 = p0 + 1
            p2 = p0 + stride
            p3 = p2 + 1
            INDICES.extend([p0, p2, p1, p1, p2, p3])


for seg in SEGMENTS:
    emit_tube(seg[0], seg[1], seg[2], seg[3], seg[4], seg[5])
emit_sphere(HEAD_CENTER, HEAD_RADIUS)

TRIANGLES = len(INDICES) // 3

# ---------------------------------------------------------------- morph targets

MORPHS = ["ShoulderWidth", "ChestVolume", "WaistWidth", "LimbThickness", "HeadScale"]


def morph_deltas(p):
    x, y, z = p
    radial = normalize((x, 0.0, z))
    out = []

    # Shoulder width: pull the shoulder girdle and upper arms outwards.
    w = smoothstep(0.10, 0.24, abs(x)) * smoothstep(1.20, 1.40, y) * (1.0 - smoothstep(1.46, 1.56, y))
    out.append((math.copysign(0.055 * w, x) if abs(x) > 1e-6 else 0.0, 0.0, 0.0))

    # Chest volume: radial expansion of the rib cage.
    w = smoothstep(1.18, 1.30, y) * (1.0 - smoothstep(1.44, 1.52, y)) * smoothstep(0.03, 0.12, abs(x) + abs(z))
    out.append((radial[0] * 0.050 * w, radial[1] * 0.0, radial[2] * 0.050 * w))

    # Waist: radial expansion around the lumbar band.
    w = smoothstep(0.92, 1.02, y) * (1.0 - smoothstep(1.14, 1.24, y))
    out.append((radial[0] * 0.055 * w, 0.0, radial[2] * 0.055 * w))

    # Limb thickness: arms and legs thicken around their own axis.
    arm = smoothstep(0.20, 0.30, abs(x)) * smoothstep(1.30, 1.40, y)
    leg = (1.0 - smoothstep(0.80, 0.92, y)) * smoothstep(0.02, 0.06, abs(x))
    w = max(arm, leg)
    out.append((radial[0] * 0.022 * w, 0.0, radial[2] * 0.022 * w))

    # Head scale about its own centre.
    d = sub(p, HEAD_CENTER)
    w = 1.0 - smoothstep(HEAD_RADIUS * 0.9, HEAD_RADIUS * 1.15, length(d))
    out.append(mul(d, 0.10 * w))

    return out


MORPH_DELTAS = [morph_deltas(p) for p in POSITIONS]

# ---------------------------------------------------------------- binary buffer

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


index_view = add_view(pack("H", [i for i in INDICES]), 34963)
index_accessor = add_accessor(index_view, 5123, len(INDICES), "SCALAR")

position_view = add_view(pack("f", [c for p in POSITIONS for c in p]), 34962)
pos_min = [min(p[i] for p in POSITIONS) for i in range(3)]
pos_max = [max(p[i] for p in POSITIONS) for i in range(3)]
position_accessor = add_accessor(position_view, 5126, len(POSITIONS), "VEC3", pos_min, pos_max)

normal_view = add_view(pack("f", [c for n in NORMALS for c in n]), 34962)
normal_accessor = add_accessor(normal_view, 5126, len(NORMALS), "VEC3")

uv_view = add_view(pack("f", [c for uv in UVS for c in uv]), 34962)
uv_accessor = add_accessor(uv_view, 5126, len(UVS), "VEC2")

joints_view = add_view(bytes(b for j in JOINTS for b in j), 34962)
joints_accessor = add_accessor(joints_view, 5121, len(JOINTS), "VEC4")

weights_view = add_view(pack("f", [w for ws in WEIGHTS for w in ws]), 34962)
weights_accessor = add_accessor(weights_view, 5126, len(WEIGHTS), "VEC4")

morph_accessors = []
for m in range(len(MORPHS)):
    flat = []
    for deltas in MORPH_DELTAS:
        flat.extend(deltas[m])
    view = add_view(pack("f", flat), 34962)
    dmin = [min(deltas[m][i] for deltas in MORPH_DELTAS) for i in range(3)]
    dmax = [max(deltas[m][i] for deltas in MORPH_DELTAS) for i in range(3)]
    morph_accessors.append(add_accessor(view, 5126, len(POSITIONS), "VEC3", dmin, dmax))

while len(buffer) % 4:
    buffer.append(0)

# ---------------------------------------------------------------- ground (shadow receiver)

GROUND_HALF = 8.0
ground_positions = [
    (-GROUND_HALF, 0.0, -GROUND_HALF),
    (GROUND_HALF, 0.0, -GROUND_HALF),
    (GROUND_HALF, 0.0, GROUND_HALF),
    (-GROUND_HALF, 0.0, GROUND_HALF),
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

# ---------------------------------------------------------------- nodes, skin, scene

nodes = []
node_index = {}


def joint_node(i):
    parent = JOINT_PARENT[i]
    # glTF translations are relative to the parent joint, not scene-space positions.
    x, y, z = JOINT_POS[i]
    if parent >= 0:
        px, py, pz = JOINT_POS[parent]
        translation = [x - px, y - py, z - pz]
    else:
        translation = [x, y, z]
    node = {"name": JOINT_NAMES[i], "translation": translation}
    child_indices = [joint_node(c) for c in range(len(JOINT_NAMES)) if JOINT_PARENT[c] == i]
    if child_indices:
        node["children"] = child_indices
    nodes.append(node)
    index = len(nodes) - 1
    node_index[JOINT_NAMES[i]] = index
    return index


armature_index = len(nodes)
nodes.append({"name": "Armature"})
hips_node = joint_node(0)
nodes[armature_index]["children"] = [hips_node]

mesh_node = len(nodes)
nodes.append({"name": "HumanoidMesh", "mesh": 0, "skin": 0})

ground_node = len(nodes)
nodes.append({"name": "Ground", "mesh": 1})

# Inverse bind = inverse of the joint's rest world transform. The rest pose has no rotations, so
# the inverse is a pure translation.
inverse_binds = []
for (x, y, z) in JOINT_POS:
    inverse_binds.extend([
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        -x, -y, -z, 1.0,
    ])
ibm_view = add_view(pack("f", inverse_binds))
ibm_accessor = add_accessor(ibm_view, 5126, len(JOINT_NAMES), "MAT4")

gltf = {
    "asset": {
        "version": "2.0",
        "generator": "monk-fitness spike humanoid generator",
        "copyright": "Generated for the Monk Fitness Filament spike; no third-party art.",
    },
    "scene": 0,
    "scenes": [{"name": "Spike", "nodes": [armature_index, mesh_node, ground_node]}],
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
        "joints": [node_index[n] for n in JOINT_NAMES],
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

json_chunk = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
while len(json_chunk) % 4:
    json_chunk += b" "

total = 12 + 8 + len(json_chunk) + 8 + len(buffer)
glb = bytearray()
glb.extend(struct.pack("<III", 0x46546C67, 2, total))
glb.extend(struct.pack("<II", len(json_chunk), 0x4E4F534A))
glb.extend(json_chunk)
glb.extend(struct.pack("<II", len(buffer), 0x004E4942))
glb.extend(buffer)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "wb") as f:
    f.write(glb)

print(f"wrote {OUT}")
print(f"  vertices   {len(POSITIONS)}")
print(f"  triangles  {TRIANGLES}")
print(f"  joints     {len(JOINT_NAMES)}")
print(f"  morphs     {len(MORPHS)} {MORPHS}")
print(f"  bytes      {total} ({total / 1024 / 1024:.2f} MB)")
