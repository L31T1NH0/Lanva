use std::slice;

const GX: [f32; 16] = [
    1., -1., 1., -1., 1., -1., 1., -1., 0., 0., 0., 0., 1., 0., -1., 0.,
];
const GY: [f32; 16] = [
    1., 1., -1., -1., 0., 0., 0., 0., 1., -1., 1., -1., 1., -1., 1., -1.,
];
const GZ: [f32; 16] = [
    0., 0., 0., 0., 1., 1., -1., -1., 1., 1., -1., -1., 0., 1., 0., -1.,
];

struct Layer {
    perm: [u8; 256],
    offset: [f64; 3],
    fudge: f64,
    frequency: f64,
    amplitude: f32,
}
struct Stack {
    layers: Vec<Layer>,
    smeared: bool,
}

fn wrap(v: f64) -> f64 {
    if (-16777215.999999998..16777215.999999998).contains(&v) {
        v
    } else {
        v - (v / 33554432. + 0.5).floor() * 33554432.
    }
}
fn smooth(t: f32) -> f32 {
    t * t * t * (t * (t * 6. - 15.) + 10.)
}
fn lerp(t: f32, a: f32, b: f32) -> f32 {
    a + t * (b - a)
}
fn lerp3(t: [f32; 3], a: [f32; 8]) -> f32 {
    lerp(
        t[2],
        lerp(t[1], lerp(t[0], a[0], a[1]), lerp(t[0], a[2], a[3])),
        lerp(t[1], lerp(t[0], a[4], a[5]), lerp(t[0], a[6], a[7])),
    )
}
fn fudge(scale: f64, y: f64, fraction: f64) -> f64 {
    let v = if y >= 0. && y < fraction { y } else { fraction };
    ((v / scale + 1.0e-7_f32 as f64).floor() as i32) as f64 * scale
}
impl Layer {
    fn perm(&self, i: i32) -> i32 {
        self.perm[(i & 255) as usize] as i32
    }
    fn hashes(&self, x: i32, y: i32, z: i32) -> [usize; 8] {
        let a = self.perm(x);
        let b = self.perm(x + 1);
        let aa = self.perm(a + y);
        let ab = self.perm(a + y + 1);
        let ba = self.perm(b + y);
        let bb = self.perm(b + y + 1);
        [
            aa + z,
            ba + z,
            ab + z,
            bb + z,
            aa + z + 1,
            ba + z + 1,
            ab + z + 1,
            bb + z + 1,
        ]
        .map(|v| (self.perm(v) & 15) as usize)
    }
    fn point(&self, smeared: bool, x: f64, y: f64, z: f64) -> f32 {
        let xyz = [
            wrap(x) + self.offset[0],
            wrap(y) + self.offset[1],
            wrap(z) + self.offset[2],
        ];
        let cell = xyz.map(|v| v.floor() as i32);
        let fx = (xyz[0] - cell[0] as f64) as f32;
        let fyd = xyz[1] - cell[1] as f64;
        let fz = (xyz[2] - cell[2] as f64) as f32;
        let fy = if smeared {
            (fyd - fudge(self.fudge, y, fyd)) as f32
        } else {
            fyd as f32
        };
        let hashes = self.hashes(cell[0], cell[1], cell[2]);
        let mut values = [0.; 8];
        for i in 0..8 {
            let h = hashes[i];
            // Points and volumes use different addition orders in Minecraft; preserve both.
            values[i] = GX[h] * (fx - (i & 1) as f32)
                + GY[h] * (fy - ((i >> 1) & 1) as f32)
                + GZ[h] * (fz - ((i >> 2) & 1) as f32);
        }
        lerp3([smooth(fx), smooth(fyd as f32), smooth(fz)], values)
    }
    fn volume(&self, smeared: bool, out: &mut [f32], v: &Volume, xs: f64, ys: f64, amp: f32) {
        let mut i = 0;
        for z in 0..v.size[2] {
            let zd = wrap(v.coordinate(2, z) as f64 * xs) + self.offset[2];
            let zf = zd.floor() as i32;
            let fz = (zd - zf as f64) as f32;
            let sz = smooth(fz);
            for x in 0..v.size[0] {
                let xd = wrap(v.coordinate(0, x) as f64 * xs) + self.offset[0];
                let xf = xd.floor() as i32;
                let fx = (xd - xf as f64) as f32;
                let sx = smooth(fx);
                for y in 0..v.size[1] {
                    let yv = v.coordinate(1, y) as f64 * ys;
                    let yd = wrap(yv) + self.offset[1];
                    let yf = yd.floor() as i32;
                    let fyd = yd - yf as f64;
                    let fy = if smeared {
                        (fyd - fudge(self.fudge, yv, fyd)) as f32
                    } else {
                        fyd as f32
                    };
                    let hashes = self.hashes(xf, yf, zf);
                    let mut values = [0.; 8];
                    for j in 0..8 {
                        let h = hashes[j];
                        let dot =
                            GX[h] * (fx - (j & 1) as f32) + GZ[h] * (fz - ((j >> 2) & 1) as f32);
                        values[j] = dot + GY[h] * (fy - ((j >> 1) & 1) as f32);
                    }
                    out[i] = out[i] + amp * lerp3([sx, smooth(fyd as f32), sz], values);
                    i += 1;
                }
            }
        }
    }
}
impl Stack {
    fn point(&self, x: f64, y: f64, z: f64) -> f32 {
        let mut sum = 0.;
        for l in &self.layers {
            sum = sum
                + l.amplitude
                    * l.point(
                        self.smeared,
                        x * l.frequency,
                        y * l.frequency,
                        z * l.frequency,
                    );
        }
        sum
    }
    fn volume(&self, out: &mut [f32], v: &Volume, xz: f64, y: f64, amp: f32, set: bool) {
        if set {
            out.fill(0.);
        }
        for l in &self.layers {
            l.volume(
                self.smeared,
                out,
                v,
                xz * l.frequency,
                y * l.frequency,
                amp * l.amplitude,
            );
        }
    }
}
struct Volume {
    size: [i32; 3],
    origin: [i32; 3],
    step: [i32; 3],
}
impl Volume {
    fn len(&self) -> Option<usize> {
        self.size
            .iter()
            .try_fold(1usize, |n, &s| {
                if s < 0 {
                    None
                } else {
                    n.checked_mul(s as usize)
                }
            })
            .filter(|&n| n <= isize::MAX as usize / 4)
    }
    fn coordinate(&self, axis: usize, i: i32) -> i32 {
        self.origin[axis].wrapping_add(i.wrapping_mul(self.step[axis]))
    }
}
fn alpha(main: f32) -> f32 {
    let a = main + 0.5;
    if a < 0. {
        0.
    } else if a <= 1. {
        a
    } else {
        1.
    }
}

/// All raw pointers and nonzero handles must be valid for the declared lengths/lifetime.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mcn_stack_new(
    n: i32,
    smeared: i32,
    perms: *const u8,
    params: *const f64,
    amps: *const f32,
) -> i64 {
    if !(0..=256).contains(&n) || (n > 0 && (perms.is_null() || params.is_null() || amps.is_null()))
    {
        return 0;
    }
    let mut layers = Vec::new();
    if layers.try_reserve_exact(n as usize).is_err() {
        return 0;
    }
    for i in 0..n as usize {
        let mut perm = [0; 256];
        unsafe {
            perm.copy_from_slice(slice::from_raw_parts(perms.add(256 * i), 256));
        }
        let p = unsafe { slice::from_raw_parts(params.add(5 * i), 5) };
        if p.iter().any(|v| !v.is_finite()) {
            return 0;
        }
        layers.push(Layer {
            perm,
            offset: [p[0], p[1], p[2]],
            fudge: p[3],
            frequency: p[4],
            amplitude: unsafe { *amps.add(i) },
        });
    }
    Box::into_raw(Box::new(Stack {
        layers,
        smeared: smeared != 0,
    })) as i64
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mcn_stack_free(handle: i64) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Stack));
        }
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mcn_stack_point(handle: i64, x: f64, y: f64, z: f64) -> f32 {
    if handle == 0 {
        return f32::NAN;
    }
    unsafe { (&*(handle as *const Stack)).point(x, y, z) }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mcn_stack_points(
    handle: i64,
    xyz: *const f64,
    n: i32,
    out: *mut f32,
) -> i32 {
    if handle == 0 || n < 0 || (n > 0 && (xyz.is_null() || out.is_null())) {
        return -1;
    }
    for i in 0..n as usize {
        unsafe {
            *out.add(i) = mcn_stack_point(
                handle,
                *xyz.add(3 * i),
                *xyz.add(3 * i + 1),
                *xyz.add(3 * i + 2),
            );
        }
    }
    0
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mcn_stack_volume(
    handle: i64,
    out: *mut f32,
    sx: i32,
    sy: i32,
    sz: i32,
    x0: i32,
    y0: i32,
    z0: i32,
    dx: i32,
    dy: i32,
    dz: i32,
    xz: f64,
    y: f64,
    amp: f32,
    set: i32,
    _implementation: i32,
) -> i32 {
    let v = Volume {
        size: [sx, sy, sz],
        origin: [x0, y0, z0],
        step: [dx, dy, dz],
    };
    let Some(n) = v.len() else {
        return -1;
    };
    if handle == 0 || (n > 0 && out.is_null()) {
        return -1;
    }
    if n == 0 {
        return 0;
    }
    unsafe {
        (&*(handle as *const Stack)).volume(
            slice::from_raw_parts_mut(out, n),
            &v,
            xz,
            y,
            amp,
            set != 0,
        );
    }
    0
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mcn_blended_point(
    m: i64,
    lo: i64,
    hi: i64,
    x: i32,
    y: i32,
    z: i32,
    mxz: f64,
    my: f64,
    lxz: f64,
    ly: f64,
) -> f32 {
    if m == 0 || lo == 0 || hi == 0 {
        return f32::NAN;
    }
    unsafe {
        let t = alpha(mcn_stack_point(
            m,
            x as f64 * mxz,
            y as f64 * my,
            z as f64 * mxz,
        ));
        if t == 0. {
            return mcn_stack_point(lo, x as f64 * lxz, y as f64 * ly, z as f64 * lxz);
        }
        if t == 1. {
            return mcn_stack_point(hi, x as f64 * lxz, y as f64 * ly, z as f64 * lxz);
        }
        lerp(
            t,
            mcn_stack_point(lo, x as f64 * lxz, y as f64 * ly, z as f64 * lxz),
            mcn_stack_point(hi, x as f64 * lxz, y as f64 * ly, z as f64 * lxz),
        )
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn mcn_blended_volume(
    m: i64,
    lo: i64,
    hi: i64,
    out: *mut f32,
    sx: i32,
    sy: i32,
    sz: i32,
    x0: i32,
    y0: i32,
    z0: i32,
    dx: i32,
    dy: i32,
    dz: i32,
    mxz: f64,
    my: f64,
    lxz: f64,
    ly: f64,
    _implementation: i32,
) -> i32 {
    let v = Volume {
        size: [sx, sy, sz],
        origin: [x0, y0, z0],
        step: [dx, dy, dz],
    };
    let Some(n) = v.len() else {
        return -1;
    };
    if m == 0 || lo == 0 || hi == 0 || (n > 0 && out.is_null()) {
        return -1;
    }
    if n == 0 {
        return 0;
    }
    let mut a = Vec::new();
    let mut b = Vec::new();
    if a.try_reserve_exact(n).is_err() || b.try_reserve_exact(n).is_err() {
        return -1;
    }
    a.resize(n, 0.);
    b.resize(n, 0.);
    let out = unsafe { slice::from_raw_parts_mut(out, n) };
    unsafe {
        (&*(m as *const Stack)).volume(out, &v, mxz, my, 1., true);
        (&*(lo as *const Stack)).volume(&mut a, &v, lxz, ly, 1., true);
        (&*(hi as *const Stack)).volume(&mut b, &v, lxz, ly, 1., true);
    }
    for i in 0..n {
        let t = alpha(out[i]);
        out[i] = if t == 0. {
            a[i]
        } else if t == 1. {
            b[i]
        } else {
            lerp(t, a[i], b[i])
        };
    }
    0
}

// Oxide Mobile safe Rust adapter. Kernel calculations above remain unchanged.
pub struct Sampler(Stack);
impl Sampler {
    pub fn new(seed: u64) -> Self {
        let mut state = seed;
        let mut next = || {
            state = state.wrapping_add(0x9e3779b97f4a7c15);
            let mut value = state;
            value = (value ^ (value >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
            value = (value ^ (value >> 27)).wrapping_mul(0x94d049bb133111eb);
            value ^ (value >> 31)
        };
        let mut layers = Vec::new();
        for octave in 0..4 {
            let mut perm = std::array::from_fn(|index| index as u8);
            for i in (1..256).rev() { perm.swap(i, (next() % (i as u64 + 1)) as usize); }
            let offset = std::array::from_fn(|_| (next() % 65536) as f64 / 256.);
            layers.push(Layer { perm, offset, fudge: 0., frequency: 2f64.powi(octave), amplitude: 0.5f32.powi(octave) });
        }
        Self(Stack { layers, smeared: false })
    }
    pub fn heights(&self, chunk_x: i32, chunk_z: i32) -> [i16; 256] {
        std::array::from_fn(|index| {
            let x = chunk_x as f64 * 16. + (index % 16) as f64;
            let z = chunk_z as f64 * 16. + (index / 16) as f64;
            (-49 + (self.0.point(x / 64., 0.5, z / 64.) * 8.).round() as i16).clamp(-57, -41)
        })
    }
}
