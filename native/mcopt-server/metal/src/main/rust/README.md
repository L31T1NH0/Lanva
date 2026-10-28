# Portable CPU kernels

Rust implementations of the original `lodmesh.c` and scalar `lodnoise.c` algorithms by Noah Dunnagan. Apache-2.0; keep the repository LICENSE and NOTICE.
No dependencies, Apple frameworks, ARM intrinsics or fast floating-point math.

`cargo build --release --offline --locked` makes `libmcopt_native.so` on Linux. The Java mod bundles the host architecture's library.
Run `python3 tools/check-rust.py` from the repository root after the Gradle build. The fixture hashes were recorded from the original C kernels with floating-point contraction disabled, before the C sources were removed. The checks compare full mesh headers and packed quads, plus point, volume and blended-noise float bytes.

## C ABI

All buffers are caller-owned, properly aligned, non-overlapping and valid for the declared sizes. Opaque stack handles must come from `mcn_stack_new`; never use a handle after `mcn_stack_free`, or free it twice. These pointer/lifetime requirements cannot be inferred by the library. No Rust panic may unwind into Java.

- `mcn_stack_new(n, smeared, perms, params, amps) -> i64`: creates up to 256 layers. Buffers: `n*256` bytes, `n*5` doubles (offset X/Y/Z, fudge scale, frequency), `n` floats (amplitudes). Returns 0 on rejected input/allocation failure. `smeared` is a boolean integer.
- `mcn_stack_free(handle)`: frees a handle; 0 is a no-op.
- `mcn_stack_point(handle, x, y, z) -> f32`: three double coordinates.
- `mcn_stack_points(handle, xyz, n, out) -> i32`: `n*3` doubles in XYZ order and `n` output floats.
- `mcn_stack_volume(handle, out, sx, sy, sz, x0, y0, z0, dx, dy, dz, xzScale, yScale, amplitude, set, implementation) -> i32`: `sx*sy*sz` output floats; Y varies fastest, then X, then Z. `set` clears the output before accumulating. `implementation` is reserved and ignored; this is the portable scalar kernel.
- `mcn_blended_point(main, low, high, x, y, z, mainXz, mainY, limitXz, limitY) -> f32`: integer coordinates, double scales.
- `mcn_blended_volume(main, low, high, out, sx, sy, sz, x0, y0, z0, dx, dy, dz, mainXz, mainY, limitXz, limitY, implementation) -> i32`.

Volume/point-batch status is 0 on success, -1 on rejected dimensions, null required buffers or temporary allocation failure. Java checks volume status. Coordinate stepping uses wrapping i32 arithmetic, matching Java. Floating-point operations preserve the original order; points and volumes deliberately have different gradient addition orders.

`lm_mesh(input, header, quads, cap) -> i32` returns the quad count, or -1 for rejected input or insufficient capacity. The `#[repr(C)]` input has five u32 grid pointers (geometry, optional crowns, runs, plant A/B), followed by six i32 values (`log_n`, level, tile X/Z, neighbor bits, block size). Each present grid has `(1 << log_n)^2` words; `log_n` is 1..15; plants must be supplied as a pair. Block size is 8 or 16. Header capacity is `(64/block)^2 * 32` words; quad capacity is `cap*2` words. On failure, discard partial output. Packed geometry and header formats are preserved from the original C implementation.

The mesher builds geometry only. It does not allocate GPU resources or draw terrain; that requires a separate renderer.

## Packed mesh format

Tiles are 64 by 64 cells, read from toroidal input grids. Neighbor bits are 1 (+X), 2 (-X), 4 (+Z), 8 (-Z).
Each quad uses two u32 words:

- Word 0: X in bits 0..6, Z in 7..13, extent 1 minus one in 14..19, extent 2 minus one in 20..25, face in 26..28, material kind in 29..30, dynamic skirt flag in 31.
- Word 1: Y0 plus 512 in bits 0..11, Y1 plus 512 in 12..23, plant offset data in 24..31. Heights clamp to -512..3583.

Faces: 0 top, 1 +X, 2 -X, 3 +Z, 4 -Z, 5 underside, 6 crossed plant. Material kinds: 0 ground, 1 crown, 2 ground under crown.
Top/underside extents span X/Z; wall extent 1 runs along its plane and heights span Y0..Y1.

Per-block header words 0..14 store group start (20 bits) and count (12 bits). Groups are tops, undersides, four interior walls, four border walls, four skirts, plants.
Word 15 stores the block's minimum/maximum height plus 512 in the low/high 16 bits. Words 16..23 contain the minimum solid tops of the block's 4-by-4 parts (two 12-bit heights per word); words 24..31 contain the maximum height of each group (two per word).
The upper eight bits of words 16..31 encode the 16 parts' solid-run bottoms: low nibbles in 16..23, high nibbles in 24..31. Zero means no bottom; otherwise the height is `2*value-66`, rounded upward during encoding.
