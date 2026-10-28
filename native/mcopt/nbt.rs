//! Write NBT into one caller-owned buffer instead of allocating a Vec for every tag.
use crate::types::{NbtListTag, NbtTag};

pub fn disk(input: NbtTag) -> Vec<u8> {
    let mut output = Vec::new();
    disk_into(input, &mut output);
    output
}

pub fn disk_into(input: NbtTag, output: &mut Vec<u8>) {
    output.clear();
    root(input, output, true);
}

pub fn network(input: NbtTag) -> Vec<u8> {
    let mut output = Vec::new();
    root(input, &mut output, false);
    output
}

fn root(input: NbtTag, output: &mut Vec<u8>, named: bool) {
    let NbtTag::Root(children) = input else { panic!("root node must be a root tag") };
    output.push(10);
    if named { output.extend_from_slice(&[0, 0]); }
    compound(children, output);
}

fn string(value: &str, output: &mut Vec<u8>) {
    // Preserve the pinned Oxide's UTF-8 representation and u16 length encoding.
    output.extend_from_slice(&(value.len() as u16).to_be_bytes());
    output.extend_from_slice(value.as_bytes());
}

fn header(kind: u8, name: &str, output: &mut Vec<u8>) {
    output.push(kind);
    string(name, output);
}

fn compound(children: Vec<NbtTag>, output: &mut Vec<u8>) {
    for tag in children {
        match tag {
            NbtTag::Byte(n, v) => { header(1, &n, output); output.push(v); }
            NbtTag::Short(n, v) => { header(2, &n, output); output.extend_from_slice(&v.to_be_bytes()); }
            NbtTag::Int(n, v) => { header(3, &n, output); output.extend_from_slice(&v.to_be_bytes()); }
            NbtTag::Long(n, v) => { header(4, &n, output); output.extend_from_slice(&v.to_be_bytes()); }
            NbtTag::Float(n, v) => { header(5, &n, output); output.extend_from_slice(&v.to_be_bytes()); }
            NbtTag::Double(n, v) => { header(6, &n, output); output.extend_from_slice(&v.to_be_bytes()); }
            NbtTag::ByteArray(n, v) => { header(7, &n, output); bytes(v, output); }
            NbtTag::String(n, v) => { header(8, &n, output); string(&v, output); }
            NbtTag::List(n, v) => { header(9, &n, output); list(v, output); }
            NbtTag::TagCompound(n, v) => { header(10, &n, output); compound(v, output); }
            NbtTag::IntArray(n, v) => { header(11, &n, output); ints(v, output); }
            NbtTag::LongArray(n, v) => { header(12, &n, output); longs(v, output); }
            NbtTag::Root(_) => panic!("Root tag cannot be in a TagCompound!!"),
        }
    }
    output.push(0);
}

fn bytes(value: Vec<u8>, output: &mut Vec<u8>) {
    output.extend_from_slice(&(value.len() as i32).to_be_bytes());
    output.extend_from_slice(&value);
}
fn ints(value: Vec<i32>, output: &mut Vec<u8>) {
    output.extend_from_slice(&(value.len() as i32).to_be_bytes());
    for v in value { output.extend_from_slice(&v.to_be_bytes()); }
}
fn longs(value: Vec<i64>, output: &mut Vec<u8>) {
    output.extend_from_slice(&(value.len() as i32).to_be_bytes());
    for v in value { output.extend_from_slice(&v.to_be_bytes()); }
}

fn kind(tag: &NbtListTag) -> u8 {
    match tag {
        NbtListTag::Byte(_) => 1, NbtListTag::Short(_) => 2, NbtListTag::Int(_) => 3,
        NbtListTag::Long(_) => 4, NbtListTag::Float(_) => 5, NbtListTag::Double(_) => 6,
        NbtListTag::ByteArray(_) => 7, NbtListTag::String(_) => 8, NbtListTag::List(_) => 9,
        NbtListTag::TagCompound(_) => 10, NbtListTag::IntArray(_) => 11, NbtListTag::LongArray(_) => 12,
    }
}

fn list(items: Vec<NbtListTag>, output: &mut Vec<u8>) {
    let element = items.first().map(kind).unwrap_or(0);
    output.push(element);
    output.extend_from_slice(&(items.len() as i32).to_be_bytes());
    for item in items {
        assert_eq!(kind(&item), element, "NBT lists must be homogeneous");
        match item {
            NbtListTag::Byte(v) => output.push(v),
            NbtListTag::Short(v) => output.extend_from_slice(&v.to_be_bytes()),
            NbtListTag::Int(v) => output.extend_from_slice(&v.to_be_bytes()),
            NbtListTag::Long(v) => output.extend_from_slice(&v.to_be_bytes()),
            NbtListTag::Float(v) => output.extend_from_slice(&v.to_be_bytes()),
            NbtListTag::Double(v) => output.extend_from_slice(&v.to_be_bytes()),
            NbtListTag::ByteArray(v) => bytes(v, output),
            NbtListTag::String(v) => string(&v, output),
            NbtListTag::List(v) => list(v, output),
            NbtListTag::TagCompound(v) => compound(v, output),
            NbtListTag::IntArray(v) => ints(v, output),
            NbtListTag::LongArray(v) => longs(v, output),
        }
    }
}

