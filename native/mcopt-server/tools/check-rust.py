#!/usr/bin/env python3
"""Compare portable Rust kernels with the original C results recorded before its removal."""
import argparse
import ctypes as c
import hashlib
import json
from pathlib import Path
import random

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--library', type=Path, default=root / 'metal/build/rust/release/libmcopt_native.so')
parser.add_argument('--reference', type=Path, help='Optional original C library for a live differential comparison')
parser.add_argument('--record', action='store_true', help='Record fixture hashes using --reference')
args = parser.parse_args()
fixtures = root / 'tools/rust-kernel-fixtures.json'

class MeshInput(c.Structure):
    _fields_ = [(name, c.POINTER(c.c_uint32)) for name in ['geom', 'crown', 'runs', 'plant_a', 'plant_b']]
    _fields_ += [(name, c.c_int) for name in ['log_n', 'level', 'tx', 'tz', 'neighbors', 'block']]

def load(path):
    lib = c.CDLL(str(path.resolve()))
    lib.mcn_stack_new.argtypes = [c.c_int, c.c_int, c.POINTER(c.c_uint8), c.POINTER(c.c_double), c.POINTER(c.c_float)]
    lib.mcn_stack_new.restype = c.c_int64
    lib.mcn_stack_free.argtypes = [c.c_int64]
    lib.mcn_stack_point.argtypes = [c.c_int64, c.c_double, c.c_double, c.c_double]
    lib.mcn_stack_point.restype = c.c_float
    lib.mcn_stack_points.argtypes = [c.c_int64, c.POINTER(c.c_double), c.c_int, c.POINTER(c.c_float)]
    lib.mcn_stack_volume.argtypes = [c.c_int64, c.POINTER(c.c_float)] + [c.c_int] * 9 + [c.c_double] * 2 + [c.c_float, c.c_int, c.c_int]
    lib.mcn_blended_volume.argtypes = [c.c_int64] * 3 + [c.POINTER(c.c_float)] + [c.c_int] * 9 + [c.c_double] * 4 + [c.c_int]
    lib.mcn_blended_point.argtypes = [c.c_int64] * 3 + [c.c_int] * 3 + [c.c_double] * 4
    lib.mcn_blended_point.restype = c.c_float
    lib.lm_mesh.argtypes = [c.POINTER(MeshInput), c.POINTER(c.c_uint32), c.POINTER(c.c_uint32), c.c_int]
    return lib

def digest(*buffers):
    return hashlib.sha256(b''.join(bytes(buffer) for buffer in buffers)).hexdigest()

def exercise(lib):
    results = {}
    for seed in range(32):
        randomizer = random.Random(seed)
        handles = []
        try:
            for side in range(3):
                n = seed % 5
                perms = []
                for _ in range(n):
                    perm = list(range(256)); randomizer.shuffle(perm); perms += perm
                params = [v for i in range(n) for v in [randomizer.uniform(-256, 256), randomizer.uniform(-256, 256),
                          randomizer.uniform(-256, 256), 0.125, 2.0 ** -i]]
                amps = [randomizer.uniform(-1, 1) for _ in range(n)]
                handle = lib.mcn_stack_new(n, seed % 2, (c.c_uint8 * len(perms))(*perms),
                                          (c.c_double * len(params))(*params), (c.c_float * n)(*amps))
                assert handle, 'stack allocation failed'
                handles.append(handle)
            xyz = [v for _ in range(24) for v in [randomizer.uniform(-4e7, 4e7), randomizer.uniform(-1024, 1024), randomizer.uniform(-4e7, 4e7)]]
            points = (c.c_float * 24)()
            lib.mcn_stack_points(handles[0], (c.c_double * len(xyz))(*xyz), 24, points)
            scalars = (c.c_float * 24)(*[lib.mcn_stack_point(handles[0], *xyz[i:i+3]) for i in range(0, len(xyz), 3)])
            assert bytes(points) == bytes(scalars), 'batch point order'
            sx, sy, sz = 3, 1 + seed % 17, 2
            volume = (c.c_float * (sx * sy * sz))(*[0.25] * (sx * sy * sz))
            dimensions = [sx, sy, sz, -128, -64, 100, 4, 8, 4]
            lib.mcn_stack_volume(handles[0], volume, *dimensions, 0.125, 0.25, 0.75, seed % 2, 0)
            blended = (c.c_float * len(volume))()
            lib.mcn_blended_volume(*handles, blended, *dimensions, 0.25, 0.125, 0.5, 0.25, 0)
            blended_points = (c.c_float * 16)(*[lib.mcn_blended_point(*handles, i * 4, i - 8, -i * 4, 0.25, 0.125, 0.5, 0.25) for i in range(16)])
            results[f'noise-{seed}'] = digest(points, volume, blended, blended_points)
        finally:
            for handle in handles: lib.mcn_stack_free(handle)
    for seed in range(32):
        randomizer = random.Random(1000 + seed)
        n = 128 * 128
        geom, crowns, runs, pa, pb = [(c.c_uint32 * n)() for _ in range(5)]
        for i in range(n):
            height = 64 if seed < 2 else randomizer.randrange(-32, 160)
            geom[i] = height + 512 | 0x2000
            if seed > 3 and randomizer.randrange(8) == 0: geom[i] = 0
            if seed > 7 and randomizer.randrange(5) == 0:
                geom[i] = height + 512 | 0x2000 | 0x4000 | (12 << 15)
                crowns[i] = height - 20 + 512
                runs[i] = 4 << 3 | (5 << 3 | 2) << 8
            elif seed > 10:
                geom[i] |= randomizer.randrange(4) << 22
                pa[i] = 1
                pb[i] = randomizer.randrange(1 << 28)
            if seed > 15:
                geom[i] |= randomizer.randrange(32) << 25
                if randomizer.randrange(16) == 0: geom[i] |= 1 << 24
        block = 8 if seed % 2 == 0 else 16
        input_data = MeshInput(geom, crowns if seed > 7 else None, runs if seed > 7 else None,
                               pa if seed > 10 else None, pb if seed > 10 else None, 7, seed % 2,
                               seed - 16, 8 - seed, seed % 16, block)
        header = (c.c_uint32 * ((64 // block) ** 2 * 32))()
        cap = 250000
        quads = (c.c_uint32 * (cap * 2))()
        count = lib.lm_mesh(c.byref(input_data), header, quads, cap)
        assert count >= 0, f'mesh failed: {seed}'
        results[f'mesh-{seed}'] = {'count': count, 'sha256': digest(header, bytes(quads)[:count * 8])}
        assert lib.lm_mesh(c.byref(input_data), header, quads, 0) == -1, 'mesh capacity not enforced'
    return results

if args.record:
    if args.reference is None: parser.error('--record requires --reference')
    fixtures.write_text(json.dumps(exercise(load(args.reference)), indent=2) + '\n')
else:
    actual = exercise(load(args.library))
    expected = exercise(load(args.reference)) if args.reference else json.loads(fixtures.read_text())
    for case in expected:
        assert actual[case] == expected[case], f'Result differs from original C: {case}: {actual[case]} != {expected[case]}'
    lib = load(args.library)
    assert lib.mcn_stack_new(-1, 0, None, None, None) == 0
    assert lib.mcn_stack_volume(0, None, -1, 1, 1, 0, 0, 0, 1, 1, 1, 1., 1., 1., 1, 0) == -1
    assert lib.lm_mesh(None, None, None, -1) == -1
    print('PASS: 32 noise cases and 32 mesh cases match original C; invalid inputs and mesh capacity rejected')
