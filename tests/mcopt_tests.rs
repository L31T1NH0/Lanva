#![allow(dead_code)]
mod types {
    include!("fixtures/oxide/nbt_types.rs");
    pub mod position { pub struct BlockPosition { pub x: i32, pub y: i16, pub z: i32 } }
}
#[path = "../native/mcopt/mod.rs"]
mod mcopt;
#[path = "fixtures/oxide/serialize.rs"]
mod reference;

use types::{NbtListTag as L, NbtTag as T};
use std::io::{self, Cursor, Read};

fn samples() -> Vec<T> {
    let values = vec![L::Byte(255), L::Short(-32768), L::Int(-123), L::Long(i64::MIN),
        L::Float(f32::from_bits(0x7fc01234)), L::Double(-0.), L::ByteArray(vec![0, 1, 255]),
        L::String("á\0😀".into()), L::List(vec![L::Int(1), L::Int(2)]),
        L::TagCompound(vec![T::String("nested".into(), "value".into())]),
        L::IntArray(vec![i32::MIN, 0, i32::MAX]), L::LongArray(vec![i64::MIN, i64::MAX])];
    let mut output: Vec<_> = values.into_iter().map(|v| T::Root(vec![T::List("list".into(), vec![v.clone(), v])])).collect();
    output.push(T::Root(vec![T::Byte("b".into(), 255), T::Short("s".into(), i16::MIN),
        T::Int("i".into(), i32::MAX), T::Long("LastUpdate".into(), i64::MAX),
        T::Float("f".into(), -0.), T::Double("d".into(), f64::from_bits(0x7ff8000000001234)),
        T::ByteArray("ba".into(), vec![1; 20000]), T::String("nameá".into(), "\0é😀".into()),
        T::IntArray("ia".into(), vec![0, -1, 123]), T::LongArray("la".into(), vec![42, -128]),
        T::List("empty".into(), vec![]), T::TagCompound("compound".into(), vec![
            T::TagCompound("deep".into(), vec![T::List("list".into(), vec![L::List(vec![])])])])]));
    output.push(T::Root(vec![]));
    output
}

#[test]
fn all_nbt_variants_match_the_pinned_oxide_bytes() {
    for tag in samples() {
        assert_eq!(mcopt::nbt::disk(tag.clone()), reference::nbt_disk(tag.clone()));
        assert_eq!(mcopt::nbt::network(tag.clone()), reference::nbt_network(tag));
    }
}

#[test]
fn output_buffer_is_reused_and_cleared() {
    let mut output = Vec::with_capacity(100000);
    let pointer = output.as_ptr();
    for tag in samples() {
        mcopt::nbt::disk_into(tag.clone(), &mut output);
        assert_eq!(output, reference::nbt_disk(tag));
        assert_eq!(output.as_ptr(), pointer);
    }
}

#[test]
fn invalid_lists_and_nested_roots_are_rejected() {
    let malformed = T::Root(vec![T::List("bad".into(), vec![L::Byte(0), L::Int(0)])]);
    assert!(std::panic::catch_unwind(|| mcopt::nbt::disk(malformed.clone())).is_err());
    assert!(std::panic::catch_unwind(|| reference::nbt_disk(malformed)).is_err());
    assert!(std::panic::catch_unwind(|| mcopt::nbt::disk(T::Root(vec![T::Root(vec![]) ]))).is_err());
}

struct ShortReads(Cursor<Vec<u8>>);
impl Read for ShortReads {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        let size = buffer.len().min(3);
        self.0.read(&mut buffer[..size])
    }
}

#[test]
fn region_skip_is_exact_and_rejects_truncated_or_extra_data() {
    let bytes: Vec<u8> = (0..20000).map(|i| (i % 255) as u8).collect();
    assert!(mcopt::region::identical(&bytes, ShortReads(Cursor::new(bytes.clone()))).unwrap());
    assert!(!mcopt::region::identical(&bytes, &bytes[..19999]).unwrap());
    let mut different = bytes.clone();
    different[8192] ^= 1;
    assert!(!mcopt::region::identical(&bytes, &different[..]).unwrap());
    different = bytes.clone();
    different.push(0);
    assert!(!mcopt::region::identical(&bytes, &different[..]).unwrap());
    let before = mcopt::nbt::disk(T::Root(vec![T::Long("LastUpdate".into(), 1)]));
    let after = mcopt::nbt::disk(T::Root(vec![T::Long("LastUpdate".into(), 2)]));
    assert!(!mcopt::region::identical(&after, &before[..]).unwrap());
}

#[test]
fn sector_length_and_compression_are_validated() {
    assert_eq!(mcopt::region::zlib_payload(&[0, 0, 0, 3, 2, 7, 8, 0]), Some(&[7, 8][..]));
    for bytes in [&[][..], &[0, 0, 0, 0, 2], &[0, 0, 0, 5, 2, 1], &[0, 0, 0, 1, 1]] {
        assert!(mcopt::region::zlib_payload(bytes).is_none());
    }
}

#[test]
fn region_replace_preserves_old_file_when_rename_fails() {
    let root = std::env::temp_dir().join(format!("oxide-region-test-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let file = root.join("r.0.0.mca");
    mcopt::region::replace_file(&file, b"old").unwrap();
    mcopt::region::replace_file(&file, b"new").unwrap();
    assert_eq!(std::fs::read(&file).unwrap(), b"new");
    let directory = root.join("existing-directory");
    std::fs::create_dir_all(&directory).unwrap();
    std::fs::write(directory.join("keep"), b"old").unwrap();
    assert!(mcopt::region::replace_file(&directory, b"replacement").is_err());
    assert_eq!(std::fs::read(directory.join("keep")).unwrap(), b"old");
    assert!(!std::fs::read_dir(&root).unwrap().any(|file| file.unwrap().file_name().to_string_lossy().ends_with(".tmp")));
    std::fs::remove_dir_all(&root).unwrap();
}

#[test]
fn seed_persists_and_existing_worlds_cannot_change_generator() {
    let root = std::env::temp_dir().join(format!("oxide-seed-test-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let fresh = root.join("fresh");
    assert_eq!(mcopt::terrain::world_seed(&fresh, None).unwrap(), None);
    assert_eq!(mcopt::terrain::world_seed(&fresh, Some("42")).unwrap(), Some(42));
    assert_eq!(mcopt::terrain::world_seed(&fresh, None).unwrap(), Some(42));
    assert!(mcopt::terrain::world_seed(&fresh, Some("43")).is_err());
    assert!(mcopt::terrain::world_seed(&fresh, Some("-1")).is_err());
    let existing = root.join("existing");
    std::fs::create_dir_all(&existing).unwrap();
    std::fs::write(existing.join("level.dat"), b"world").unwrap();
    assert!(mcopt::terrain::world_seed(&existing, Some("42")).is_err());
    assert_eq!(mcopt::terrain::world_seed(&existing, None).unwrap(), None);
    mcopt::terrain::configure(&fresh).unwrap();
    let heights = mcopt::terrain::heights(0, 0).unwrap();
    assert_eq!(heights, mcopt::terrain::heights(0, 0).unwrap());
    assert_ne!(heights, mcopt::terrain::heights(1, 0).unwrap());
    assert_eq!(mcopt::terrain::spawn_y(), heights[0] + 1);
    for (x, z) in [(0, 0), (-1, -1), (i32::MIN, i32::MAX)] {
        assert!(mcopt::terrain::heights(x, z).unwrap().iter().all(|&y| (-57..=-41).contains(&y)));
    }
    std::fs::remove_dir_all(&root).unwrap();
}
