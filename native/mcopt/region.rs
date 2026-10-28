//! Conservative RegionSkip adaptation: skip only exact NBT matches, including LastUpdate.
use std::fs::{self, OpenOptions};
use std::io::{self, Read, Write};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};

pub fn zlib_payload(sector: &[u8]) -> Option<&[u8]> {
    let size = u32::from_be_bytes(sector.get(..4)?.try_into().ok()?) as usize;
    if size < 1 || *sector.get(4)? != 2 { return None; }
    sector.get(5..4usize.checked_add(size)?)
}

pub fn identical(fresh: &[u8], mut stored: impl Read) -> io::Result<bool> {
    let mut buffer = [0; 8192];
    let mut offset = 0;
    loop {
        let count = stored.read(&mut buffer)?;
        if count == 0 { return Ok(offset == fresh.len()); }
        let Some(end) = offset.checked_add(count) else { return Ok(false) };
        if fresh.get(offset..end) != Some(&buffer[..count]) { return Ok(false); }
        offset = end;
    }
}

/// Keep the previous region intact if writing or syncing the temporary file fails.
pub fn replace_file(path: &Path, bytes: &[u8]) -> io::Result<()> {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let name = path.file_name().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "missing filename"))?;
    let mut temporary = name.to_os_string();
    temporary.push(format!(".{}.{}.tmp", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
    let temporary = path.with_file_name(temporary);
    let mut file = OpenOptions::new().write(true).create_new(true).open(&temporary)?;
    let result = (|| {
        file.write_all(bytes)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary, path)?;
        // Android/Linux permit syncing the directory to persist the rename as well.
        fs::File::open(path.parent().unwrap_or(Path::new(".")))?.sync_all()
    })();
    if result.is_err() { let _ = fs::remove_file(&temporary); }
    result
}

