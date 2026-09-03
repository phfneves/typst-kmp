//! Extraction of Typst package archives.
//!
//! Downloading is the Kotlin side's job — this module only turns the `.tar.gz` bytes it hands us
//! into VFS entries.

use std::io::Read;

use flate2::read::GzDecoder;
use tar::Archive;

/// Hard cap on a single extracted file, as a cheap guard against decompression bombs.
const MAX_ENTRY_BYTES: u64 = 64 * 1024 * 1024;

/// Unpacks a gzipped tarball into `(relative path, contents)` pairs.
///
/// Paths are normalised to forward slashes without a leading `./`. Directories, symlinks and
/// anything escaping the archive root are skipped.
pub fn unpack(archive: &[u8]) -> Result<Vec<(String, Vec<u8>)>, String> {
    let mut tar = Archive::new(GzDecoder::new(archive));
    let entries = tar
        .entries()
        .map_err(|err| format!("malformed package archive: {err}"))?;

    let mut files = Vec::new();
    for entry in entries {
        let mut entry = entry.map_err(|err| format!("malformed package entry: {err}"))?;
        if !entry.header().entry_type().is_file() {
            continue;
        }

        let raw = entry
            .path()
            .map_err(|err| format!("malformed package entry path: {err}"))?
            .to_string_lossy()
            .into_owned();
        let Some(path) = normalize_entry_path(&raw) else {
            continue;
        };

        let size = entry.header().size().unwrap_or(0);
        if size > MAX_ENTRY_BYTES {
            return Err(format!("package entry {path} is too large ({size} bytes)"));
        }

        let mut bytes = Vec::with_capacity(size as usize);
        entry
            .read_to_end(&mut bytes)
            .map_err(|err| format!("failed to read package entry {path}: {err}"))?;
        files.push((path, bytes));
    }

    if files.is_empty() {
        return Err("package archive contained no files".to_string());
    }
    Ok(files)
}

/// Normalises an archive entry path, rejecting anything that could escape the package root.
///
/// Returns `None` for entries that must be skipped. This is deliberately a separate function so
/// it can be tested directly: the `tar` crate refuses to *write* a traversing path, which makes
/// a malicious archive impossible to build with its own builder.
fn normalize_entry_path(raw: &str) -> Option<String> {
    let path = raw.replace('\\', "/");
    let path = path.trim_start_matches("./").trim_start_matches('/');
    if path.is_empty() {
        return None;
    }
    if path.split('/').any(|part| part == ".." || part == ".") {
        return None;
    }
    // A Windows drive letter or UNC prefix would also escape the root.
    if path.contains(':') {
        return None;
    }
    Some(path.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::GzEncoder;
    use flate2::Compression;
    use std::io::Write;

    fn make_archive(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut builder = tar::Builder::new(Vec::new());
        for (path, contents) in entries {
            let mut header = tar::Header::new_gnu();
            header.set_size(contents.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            builder.append_data(&mut header, path, *contents).unwrap();
        }
        let tar = builder.into_inner().unwrap();
        let mut encoder = GzEncoder::new(Vec::new(), Compression::fast());
        encoder.write_all(&tar).unwrap();
        encoder.finish().unwrap()
    }

    #[test]
    fn extracts_files() {
        let archive = make_archive(&[("typst.toml", b"[package]"), ("src/lib.typ", b"#let x = 1")]);
        let files = unpack(&archive).unwrap();
        assert_eq!(files.len(), 2);
        assert_eq!(files[0].0, "typst.toml");
        assert_eq!(files[1].1, b"#let x = 1");
    }

    #[test]
    fn rejects_paths_that_escape_the_package_root() {
        for hostile in [
            "../escape.typ",
            "a/../../escape.typ",
            "C:/Windows/x.typ",
            "..",
            "",
        ] {
            assert_eq!(normalize_entry_path(hostile), None, "accepted {hostile}");
        }
    }

    #[test]
    fn normalizes_ordinary_paths() {
        assert_eq!(
            normalize_entry_path("./src/lib.typ").as_deref(),
            Some("src/lib.typ")
        );
        assert_eq!(
            normalize_entry_path("src\\lib.typ").as_deref(),
            Some("src/lib.typ")
        );
        // An absolute entry is made relative to the package root rather than rejected — that is
        // what plain `tar` does, and it cannot escape because we always prefix the package root.
        assert_eq!(
            normalize_entry_path("/etc/passwd").as_deref(),
            Some("etc/passwd")
        );
    }

    #[test]
    fn rejects_empty_archive() {
        let mut encoder = GzEncoder::new(Vec::new(), Compression::fast());
        encoder
            .write_all(&tar::Builder::new(Vec::new()).into_inner().unwrap())
            .unwrap();
        assert!(unpack(&encoder.finish().unwrap()).is_err());
    }
}
