//! The in-memory file system the compiler sees.
//!
//! There is deliberately no filesystem and no network access anywhere in this crate. Everything
//! the document can read must have been placed here by the Kotlin side first, which makes the
//! sandbox airtight by construction and lets the same code run unchanged in a browser.

use std::collections::{BTreeMap, HashMap};
use std::sync::Mutex;

use typst::diag::{FileError, FileResult};
use typst::foundations::Bytes;
use typst::syntax::package::PackageSpec;
use typst::syntax::{FileId, RootedPath, Source, VirtualPath, VirtualRoot};

/// Parses the textual VFS path format used across the FFI boundary.
///
/// * `"/main.typ"` — a file in the project root.
/// * `"@preview/cetz:0.3.0/src/lib.typ"` — a file inside a package.
pub fn parse_path(path: &str) -> Result<FileId, String> {
    let (root, rest) = if let Some(stripped) = path.strip_prefix('@') {
        // `<namespace>/<name>:<version>/<path…>`
        let slash = stripped
            .find('/')
            .ok_or_else(|| format!("malformed package path: {path}"))?;
        let namespace = &stripped[..slash];
        let after = &stripped[slash + 1..];
        let colon = after
            .find(':')
            .ok_or_else(|| format!("missing package version: {path}"))?;
        let name = &after[..colon];
        let after_colon = &after[colon + 1..];
        let (version, rest) = match after_colon.find('/') {
            Some(idx) => (&after_colon[..idx], &after_colon[idx..]),
            None => (after_colon, "/"),
        };
        let spec: PackageSpec = format!("@{namespace}/{name}:{version}")
            .parse()
            .map_err(|err| format!("invalid package spec in {path}: {err}"))?;
        (VirtualRoot::Package(spec), rest.to_string())
    } else {
        let rest = if path.starts_with('/') {
            path.to_string()
        } else {
            format!("/{path}")
        };
        (VirtualRoot::Project, rest)
    };

    let vpath = VirtualPath::new(&rest).map_err(|err| format!("invalid path {path}: {err:?}"))?;
    Ok(RootedPath::new(root, vpath).intern())
}

/// Renders a [`FileId`] back into the textual format understood by Kotlin.
pub fn format_path(id: FileId) -> String {
    let rooted = id.get();
    let vpath = rooted.vpath().get_with_slash();
    match rooted.root() {
        VirtualRoot::Project => vpath.to_string(),
        VirtualRoot::Package(spec) => {
            format!(
                "@{}/{}:{}{}",
                spec.namespace, spec.name, spec.version, vpath
            )
        }
    }
}

/// Root prefix of a package, used when unpacking an archive.
pub fn package_prefix(spec: &PackageSpec) -> String {
    format!("@{}/{}:{}", spec.namespace, spec.name, spec.version)
}

#[derive(Default)]
pub struct Vfs {
    files: HashMap<FileId, Bytes>,
    /// Parsed sources, cached because `World::source` is called repeatedly during a compile.
    sources: Mutex<HashMap<FileId, Source>>,
    /// Package roots that have been fully populated, so a miss inside one is a real miss
    /// rather than a request to download the package again.
    packages: BTreeMap<String, ()>,
}

impl Vfs {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn insert(&mut self, id: FileId, bytes: Bytes) {
        self.files.insert(id, bytes);
        self.sources
            .get_mut()
            .expect("vfs source cache poisoned")
            .remove(&id);
    }

    pub fn contains(&self, id: FileId) -> bool {
        self.files.contains_key(&id)
    }

    pub fn mark_package_loaded(&mut self, spec: &PackageSpec) {
        self.packages.insert(package_prefix(spec), ());
    }

    pub fn has_package(&self, spec: &PackageSpec) -> bool {
        self.packages.contains_key(&package_prefix(spec))
    }

    pub fn file(&self, id: FileId) -> FileResult<Bytes> {
        self.files
            .get(&id)
            .cloned()
            .ok_or_else(|| FileError::NotFound(std::path::PathBuf::from(format_path(id))))
    }

    pub fn source(&self, id: FileId) -> FileResult<Source> {
        if let Some(cached) = self
            .sources
            .lock()
            .expect("vfs source cache poisoned")
            .get(&id)
        {
            return Ok(cached.clone());
        }
        let bytes = self.file(id)?;
        let text = std::str::from_utf8(&bytes).map_err(|_| FileError::InvalidUtf8)?;
        let source = Source::new(id, text.to_string());
        self.sources
            .lock()
            .expect("vfs source cache poisoned")
            .insert(id, source.clone());
        Ok(source)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn project_paths_round_trip() {
        let id = parse_path("/chapters/one.typ").unwrap();
        assert_eq!(format_path(id), "/chapters/one.typ");
    }

    #[test]
    fn leading_slash_is_optional() {
        assert_eq!(
            parse_path("main.typ").unwrap(),
            parse_path("/main.typ").unwrap()
        );
    }

    #[test]
    fn package_paths_round_trip() {
        let id = parse_path("@preview/cetz:0.3.0/src/lib.typ").unwrap();
        assert_eq!(format_path(id), "@preview/cetz:0.3.0/src/lib.typ");
    }

    #[test]
    fn package_root_defaults_to_slash() {
        let id = parse_path("@preview/cetz:0.3.0").unwrap();
        assert_eq!(format_path(id), "@preview/cetz:0.3.0/");
    }
}
