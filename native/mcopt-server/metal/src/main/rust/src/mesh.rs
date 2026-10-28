use std::slice;

const TILE: i32 = 64;
const REGION: usize = 66;
const DEEP: i32 = -512;
const NEG_INF: i32 = -100000;
const MAX_IV: usize = 5;
const CROWN: u32 = 0x4000;
const VALID: u32 = 0x2000;
const CLEAR: u32 = 1 << 24;

#[repr(C)]
pub struct LmIn {
    geom: *const u32,
    crown: *const u32,
    runs: *const u32,
    plant_a: *const u32,
    plant_b: *const u32,
    log_n: i32,
    level: i32,
    tx: i32,
    tz: i32,
    neighbors: i32,
    block: i32,
}
struct Input<'a> {
    geom: &'a [u32],
    crown: Option<&'a [u32]>,
    runs: Option<&'a [u32]>,
    plant_a: Option<&'a [u32]>,
    plant_b: Option<&'a [u32]>,
    log_n: i32,
    level: i32,
    tx: i32,
    tz: i32,
    neighbors: i32,
    block: i32,
}
#[derive(Clone, Copy)]
struct Col {
    n: i32,
    lo: [i32; MAX_IV],
    hi: [i32; MAX_IV],
    kind: [i32; MAX_IV],
    clear: bool,
    bot: i32,
}
impl Default for Col {
    fn default() -> Self {
        Self {
            n: -1,
            lo: [0; MAX_IV],
            hi: [0; MAX_IV],
            kind: [0; MAX_IV],
            clear: false,
            bot: NEG_INF,
        }
    }
}
impl Col {
    fn add(&mut self, lo: i32, hi: i32, kind: i32) {
        if hi <= lo {
            return;
        }
        if self.n > 0 && lo <= self.hi[self.n as usize - 1] {
            let k = self.n as usize - 1;
            self.hi[k] = self.hi[k].max(hi);
            return;
        }
        if self.n as usize == MAX_IV {
            return;
        }
        let k = self.n as usize;
        self.lo[k] = lo;
        self.hi[k] = hi;
        self.kind[k] = kind;
        self.n += 1;
    }
}
impl Input<'_> {
    fn index(&self, x: i32, z: i32) -> usize {
        let mask = (1 << self.log_n) - 1;
        (((z & mask) as usize) << self.log_n) | (x & mask) as usize
    }
    fn column(&self, x: i32, z: i32) -> Col {
        let i = self.index(x, z);
        let g = self.geom[i];
        let mut c = Col {
            clear: g & CLEAR != 0,
            ..Col::default()
        };
        if g & VALID == 0 {
            return c;
        }
        c.n = 0;
        let top = (g & 0xfff) as i32 - 512;
        if g & CROWN != 0 && self.crown.is_some() {
            let ground = (self.crown.unwrap()[i] & 0xfff) as i32 - 512;
            let mut y = top - ((g >> 15) & 63) as i32;
            c.add(NEG_INF, ground, 2);
            let runs = self.runs.map_or(0, |r| r[i]);
            if runs == 0 {
                c.add(y, top, 1);
            } else {
                for k in 0..4 {
                    let run = (runs >> (8 * k)) & 255;
                    let len = (run >> 3) as i32;
                    if len == 0 {
                        break;
                    }
                    y += (run & 7) as i32;
                    c.add(y, y + len, 1);
                    y += len;
                }
            }
        } else {
            c.add(NEG_INF, top, 0);
        }
        let depth = ((g >> 25) & 127) as i32;
        c.bot = if depth > 0 && c.n > 0 {
            c.hi[0] - depth
        } else {
            NEG_INF
        };
        c
    }
}
fn at(cols: &[Col], x: i32, z: i32) -> &Col {
    &cols[(z + 1) as usize * REGION + (x + 1) as usize]
}

#[derive(Clone, Copy, Default)]
struct Pieces {
    n: usize,
    lo: [i32; 10],
    hi: [i32; 10],
    kind: [i32; 10],
    used: [bool; 10],
}
fn difference(a: &Col, b: &Col) -> Pieces {
    let mut out = Pieces::default();
    for k in 0..a.n.max(0) as usize {
        let mut pl = [0; 12];
        let mut ph = [0; 12];
        let mut pn = 1;
        pl[0] = a.lo[k];
        ph[0] = a.hi[k];
        for j in 0..b.n.max(0) as usize {
            let bl = b.lo[j];
            let bh = b.hi[j];
            let mut ql = [0; 12];
            let mut qh = [0; 12];
            let mut qn = 0;
            for p in 0..pn {
                if bh <= pl[p] || bl >= ph[p] {
                    ql[qn] = pl[p];
                    qh[qn] = ph[p];
                    qn += 1;
                    continue;
                }
                if bl > pl[p] {
                    ql[qn] = pl[p];
                    qh[qn] = bl;
                    qn += 1;
                }
                if ph[p] > bh {
                    ql[qn] = bh;
                    qh[qn] = ph[p];
                    qn += 1;
                }
            }
            pl = ql;
            ph = qh;
            pn = qn;
        }
        for p in 0..pn {
            if out.n >= 10 {
                break;
            }
            let lo = pl[p].max(DEEP);
            let hi = ph[p];
            if hi <= lo {
                continue;
            }
            out.lo[out.n] = lo;
            out.hi[out.n] = hi;
            out.kind[out.n] = a.kind[k];
            out.n += 1;
        }
    }
    out
}
struct Out<'a> {
    quads: &'a mut [u32],
    count: usize,
    overflow: bool,
    min_y: i32,
    max_y: i32,
}
impl Out<'_> {
    fn emit(
        &mut self,
        x: i32,
        z: i32,
        e1: i32,
        e2: i32,
        face: i32,
        kind: i32,
        y0: i32,
        y1: i32,
        extra: i32,
        flags: i32,
    ) {
        if self.count >= self.quads.len() / 2 {
            self.overflow = true;
            return;
        }
        let y0 = y0.clamp(DEEP, 3583);
        let y1 = y1.clamp(DEEP, 3583);
        self.quads[self.count * 2] = x as u32
            | (z as u32) << 7
            | ((e1 - 1) as u32) << 14
            | ((e2 - 1) as u32) << 20
            | (face as u32) << 26
            | (kind as u32) << 29
            | ((flags & 1) as u32) << 31;
        self.quads[self.count * 2 + 1] =
            (y0 + 512) as u32 | ((y1 + 512) as u32) << 12 | ((extra & 255) as u32) << 24;
        self.count += 1;
    }
    fn range(&mut self, lo: i32, hi: i32) {
        self.min_y = self.min_y.min(lo);
        self.max_y = self.max_y.max(hi);
    }
    fn rects(
        &mut self,
        rows: &mut [u32; 16],
        b: i32,
        x0: i32,
        z0: i32,
        face: i32,
        kind: i32,
        y: i32,
    ) {
        for z in 0..b as usize {
            while rows[z] != 0 {
                let x = rows[z].trailing_zeros() as i32;
                let mut w = 0;
                while x + w < b && rows[z] >> (x + w) & 1 != 0 {
                    w += 1;
                }
                let mask = ((1u32 << w) - 1) << x;
                let mut h = 1;
                while z + h < b as usize && rows[z + h] & mask == mask {
                    h += 1;
                }
                for k in 0..h {
                    rows[z + k] &= !mask;
                }
                self.emit(x0 + x, z0 + z as i32, w, h as i32, face, kind, y, y, 0, 0);
            }
        }
    }
    fn flats(&mut self, cols: &[Col], b: i32, x0: i32, z0: i32, bottoms: bool) {
        let mut items = [(0i32, 0usize, 0usize); 16 * 16 * MAX_IV];
        let mut n = 0;
        for z in 0..b {
            for x in 0..b {
                let c = at(cols, x0 + x, z0 + z);
                for k in 0..c.n.max(0) as usize {
                    if bottoms && c.lo[k] == NEG_INF {
                        continue;
                    }
                    let y = if bottoms { c.lo[k] } else { c.hi[k] };
                    items[n] = ((c.kind[k] << 16) | (y + 512), x as usize, z as usize);
                    n += 1;
                }
            }
        }
        items[..n].sort_unstable_by_key(|item| item.0);
        let mut i = 0;
        while i < n {
            let key = items[i].0;
            let mut rows = [0u32; 16];
            while i < n && items[i].0 == key {
                rows[items[i].2] |= 1 << items[i].1;
                i += 1;
            }
            let y = (key & 0xffff) - 512;
            self.range(y, y);
            self.rects(
                &mut rows,
                b,
                x0,
                z0,
                if bottoms { 5 } else { 0 },
                key >> 16,
                y,
            );
        }
    }
    fn walls(
        &mut self,
        cols: &[Col],
        b: i32,
        x0: i32,
        z0: i32,
        face: i32,
        first: i32,
        last: i32,
        skirt: bool,
    ) {
        let dx = if face == 1 {
            1
        } else if face == 2 {
            -1
        } else {
            0
        };
        let dz = if face == 3 {
            1
        } else if face == 4 {
            -1
        } else {
            0
        };
        let along_x = dx == 0;
        for i in first..=last {
            let mut pieces = [Pieces::default(); 16];
            for r in 0..b {
                let (x, z) = if along_x { (r, i) } else { (i, r) };
                let a = at(cols, x0 + x, z0 + z);
                let mut floor = Col {
                    n: 1,
                    ..Col::default()
                };
                floor.lo[0] = NEG_INF;
                if skirt {
                    floor.hi[0] = skirt_floor(cols, x0 + x, z0 + z, dx, dz);
                }
                let neighbor = if skirt {
                    &floor
                } else {
                    at(cols, x0 + x + dx, z0 + z + dz)
                };
                if a.n > 0 {
                    pieces[r as usize] = difference(a, neighbor);
                }
            }
            let plane = (if along_x { z0 } else { x0 }) + i + if dx > 0 || dz > 0 { 1 } else { 0 };
            for r in 0..b as usize {
                for k in 0..pieces[r].n {
                    if pieces[r].used[k] {
                        continue;
                    }
                    let lo = pieces[r].lo[k];
                    let hi = pieces[r].hi[k];
                    let kind = pieces[r].kind[k];
                    let dynamic = skirt && k == 0 && kind != 1;
                    let mut len = 1;
                    if !dynamic {
                        for s in r + 1..b as usize {
                            let hit = (0..pieces[s].n).find(|&j| {
                                !pieces[s].used[j]
                                    && pieces[s].lo[j] == lo
                                    && pieces[s].hi[j] == hi
                                    && pieces[s].kind[j] == kind
                            });
                            let Some(hit) = hit else {
                                break;
                            };
                            pieces[s].used[hit] = true;
                            len += 1;
                        }
                        if !skirt {
                            self.range(lo, hi);
                        }
                    }
                    let (x, z) = if along_x {
                        (x0 + r as i32, plane)
                    } else {
                        (plane, z0 + r as i32)
                    };
                    self.emit(x, z, len, 1, face, kind, lo, hi, 0, dynamic as i32);
                }
            }
        }
    }
    fn plants(&mut self, input: &Input, b: i32, x0: i32, z0: i32) {
        if input.level != 0 {
            return;
        }
        let (Some(pa), Some(pb)) = (input.plant_a, input.plant_b) else {
            return;
        };
        for z in 0..b {
            for x in 0..b {
                let i = input.index(
                    input.tx.wrapping_mul(TILE).wrapping_add(x0 + x),
                    input.tz.wrapping_mul(TILE).wrapping_add(z0 + z),
                );
                let g = input.geom[i];
                let blocks = ((g >> 22) & 3) as i32;
                if g & VALID == 0 || g & CROWN != 0 || blocks == 0 || pa[i] & 1023 == 0 {
                    continue;
                }
                let y = (g & 0xfff) as i32 - 512;
                let ox = ((pb[i] >> 16) & 15) as i32;
                let oz = ((pb[i] >> 20) & 15) as i32;
                let oy = ((pb[i] >> 24) & 15) as i32;
                self.range(y - 1, y + blocks);
                for q in 0..2 {
                    self.emit(
                        x0 + x,
                        z0 + z,
                        (q | ((blocks - 1) << 1)) + 1,
                        ox + 1,
                        6,
                        0,
                        y,
                        y + blocks,
                        oz | (oy << 4),
                        0,
                    );
                }
            }
        }
    }
}
fn skirt_floor(cols: &[Col], cx: i32, cz: i32, dx: i32, dz: i32) -> i32 {
    let mut lo = at(cols, cx, cz).hi[0];
    let mut hi = lo;
    let ax = (dz != 0) as i32;
    let az = (dx != 0) as i32;
    for k in -1..=1 {
        let nx = cx + dx + k * ax;
        let nz = cz + dz + k * az;
        if nx < -1 || nz < -1 || nx > TILE || nz > TILE {
            continue;
        }
        let n = at(cols, nx, nz);
        if n.n <= 0 {
            if k == 0 {
                return DEEP;
            }
            continue;
        }
        lo = lo.min(n.hi[0]);
        hi = hi.max(n.hi[0]);
    }
    lo - (hi - lo).max(4)
}
fn mesh(input: &Input, header: &mut [u32], quads: &mut [u32]) -> i32 {
    let mut cols = Vec::new();
    if cols.try_reserve_exact(REGION * REGION).is_err() {
        return -1;
    }
    cols.resize(REGION * REGION, Col::default());
    for z in -1..=TILE {
        for x in -1..=TILE {
            let out_x = if x < 0 {
                2
            } else if x >= TILE {
                1
            } else {
                0
            };
            let out_z = if z < 0 {
                8
            } else if z >= TILE {
                4
            } else {
                0
            };
            if out_x != 0 && out_z != 0 {
                continue;
            }
            let need = out_x | out_z;
            if need != 0 && input.neighbors & need == 0 {
                continue;
            }
            cols[(z + 1) as usize * REGION + (x + 1) as usize] = input.column(
                input.tx.wrapping_mul(TILE).wrapping_add(x),
                input.tz.wrapping_mul(TILE).wrapping_add(z),
            );
        }
    }
    let b = input.block;
    let nb = TILE / b;
    let mut out = Out {
        quads,
        count: 0,
        overflow: false,
        min_y: 0,
        max_y: 0,
    };
    for bz in 0..nb {
        for bx in 0..nb {
            let h = &mut header[(bz * nb + bx) as usize * 32..][..32];
            let x0 = bx * b;
            let z0 = bz * b;
            out.min_y = 1 << 20;
            out.max_y = -(1 << 20);
            let mut group_max = [0u32; 16];
            for g in 0..15 {
                let start = out.count;
                match g {
                    0 => out.flats(&cols, b, x0, z0, false),
                    1 => out.flats(&cols, b, x0, z0, true),
                    2..=5 => {
                        let face = g - 1;
                        let first = if face == 2 || face == 4 { 1 } else { 0 };
                        let last = if first == 1 { b - 1 } else { b - 2 };
                        out.walls(&cols, b, x0, z0, face as i32, first, last, false);
                    }
                    6..=13 => {
                        let face = (g - 6) % 4 + 1;
                        let i = if face == 1 || face == 3 { b - 1 } else { 0 };
                        out.walls(&cols, b, x0, z0, face as i32, i, i, g >= 10);
                    }
                    14 => out.plants(input, b, x0, z0),
                    _ => unreachable!(),
                }
                let count = out.count - start;
                if count > 4095 {
                    out.overflow = true;
                }
                h[g] = start as u32 | (count as u32) << 20;
                let mut max = DEEP;
                if !out.overflow {
                    for k in start..out.count {
                        let w = out.quads[k * 2 + 1];
                        max = max
                            .max((w & 0xfff) as i32 - 512)
                            .max(((w >> 12) & 0xfff) as i32 - 512);
                    }
                }
                group_max[g] = (max + 512) as u32;
            }
            for k in 0..8 {
                h[24 + k] = group_max[2 * k] | group_max[2 * k + 1] << 12;
            }
            if out.max_y < out.min_y {
                out.min_y = 0;
                out.max_y = 0;
            }
            h[15] = (out.min_y.max(DEEP) + 512) as u32 | ((out.max_y.min(3583) + 512) as u32) << 16;
            let mut qm = [3583i32; 16];
            let mut qb = [NEG_INF; 16];
            for z in 0..b {
                for x in 0..b {
                    let c = at(&cols, x0 + x, z0 + z);
                    let k = ((z * 4 / b) * 4 + x * 4 / b) as usize;
                    qm[k] = qm[k].min(if c.n > 0 && !c.clear { c.hi[0] } else { DEEP });
                    if c.n > 0 && !c.clear {
                        qb[k] = qb[k].max(c.bot);
                    }
                }
            }
            for k in 0..16 {
                qm[k] = qm[k].max(DEEP) + 512;
                let v = qb[k] + 66;
                qb[k] = if qb[k] == NEG_INF {
                    0
                } else if v <= 2 {
                    1
                } else {
                    ((v + 1) / 2).min(255)
                };
            }
            for k in 0..8 {
                h[16 + k] = qm[2 * k] as u32
                    | (qm[2 * k + 1] as u32) << 12
                    | ((qb[2 * k] & 15) as u32) << 24
                    | ((qb[2 * k + 1] & 15) as u32) << 28;
                h[24 + k] |= ((qb[2 * k] >> 4) as u32) << 24 | ((qb[2 * k + 1] >> 4) as u32) << 28;
            }
        }
    }
    if out.overflow || out.count >= 1 << 20 {
        -1
    } else {
        out.count as i32
    }
}

/// Header has (64/block)^2 * 32 words; quads has cap * 2 words; input grids have (1<<log_n)^2 words.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn lm_mesh(
    input: *const LmIn,
    header: *mut u32,
    quads: *mut u32,
    cap: i32,
) -> i32 {
    if input.is_null() || header.is_null() || cap < 0 || (cap > 0 && quads.is_null()) {
        return -1;
    }
    let input = unsafe { &*input };
    if input.geom.is_null()
        || !(1..=15).contains(&input.log_n)
        || ![8, 16].contains(&input.block)
        || (input.plant_a.is_null() != input.plant_b.is_null())
    {
        return -1;
    }
    let len = 1usize << (2 * input.log_n);
    let optional = |ptr: *const u32| {
        if ptr.is_null() {
            None
        } else {
            Some(unsafe { slice::from_raw_parts(ptr, len) })
        }
    };
    let data = Input {
        geom: unsafe { slice::from_raw_parts(input.geom, len) },
        crown: optional(input.crown),
        runs: optional(input.runs),
        plant_a: optional(input.plant_a),
        plant_b: optional(input.plant_b),
        log_n: input.log_n,
        level: input.level,
        tx: input.tx,
        tz: input.tz,
        neighbors: input.neighbors,
        block: input.block,
    };
    let headers =
        unsafe { slice::from_raw_parts_mut(header, ((TILE / input.block).pow(2) * 32) as usize) };
    let quads = if cap == 0 {
        &mut []
    } else {
        unsafe { slice::from_raw_parts_mut(quads, cap as usize * 2) }
    };
    mesh(&data, headers, quads)
}
