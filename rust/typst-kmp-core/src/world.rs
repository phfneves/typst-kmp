//! [`typst::World`] implementation backed entirely by the in-memory [`Vfs`].

use std::collections::BTreeSet;
use std::sync::Mutex;

use typst::diag::{FileError, FileResult};
use typst::foundations::{Bytes, Datetime, Duration};
use typst::syntax::{FileId, Source, VirtualRoot};
use typst::text::{Font, FontBook};
use typst::utils::LazyHash;
use typst::{Library, World};

use crate::protocol::Missing;
use crate::vfs::{format_path, Vfs};

pub struct KmpWorld<'a> {
    library: LazyHash<Library>,
    book: &'a LazyHash<FontBook>,
    fonts: &'a [Font],
    vfs: &'a Vfs,
    main: FileId,
    today: Option<Datetime>,
    /// Every lookup that failed, recorded structurally so the Kotlin resolution loop can act on
    /// it without having to parse human-readable diagnostic text.
    misses: Mutex<BTreeSet<Missing>>,
}

impl<'a> KmpWorld<'a> {
    pub fn new(
        library: Library,
        book: &'a LazyHash<FontBook>,
        fonts: &'a [Font],
        vfs: &'a Vfs,
        main: FileId,
        today: Option<Datetime>,
    ) -> Self {
        Self {
            library: LazyHash::new(library),
            book,
            fonts,
            vfs,
            main,
            today,
            misses: Mutex::new(BTreeSet::new()),
        }
    }

    /// Consumes the world and returns everything it could not resolve.
    pub fn into_misses(self) -> Vec<Missing> {
        self.misses
            .into_inner()
            .expect("miss set poisoned")
            .into_iter()
            .collect()
    }

    /// Classifies a failed lookup: a file inside a package we never loaded means the *package*
    /// is missing, otherwise it is an individual file.
    fn record_miss(&self, id: FileId) {
        let rooted = id.get();
        let miss = match rooted.root() {
            VirtualRoot::Package(spec) if !self.vfs.has_package(spec) => Missing::Package {
                namespace: spec.namespace.to_string(),
                name: spec.name.to_string(),
                version: spec.version.to_string(),
            },
            _ => Missing::File {
                path: format_path(id),
            },
        };
        self.misses.lock().expect("miss set poisoned").insert(miss);
    }

    fn recording<T>(&self, id: FileId, result: FileResult<T>) -> FileResult<T> {
        if matches!(result, Err(FileError::NotFound(_))) {
            self.record_miss(id);
        }
        result
    }
}

impl World for KmpWorld<'_> {
    fn library(&self) -> &LazyHash<Library> {
        &self.library
    }

    fn book(&self) -> &LazyHash<FontBook> {
        self.book
    }

    fn main(&self) -> FileId {
        self.main
    }

    fn source(&self, id: FileId) -> FileResult<Source> {
        self.recording(id, self.vfs.source(id))
    }

    fn file(&self, id: FileId) -> FileResult<Bytes> {
        self.recording(id, self.vfs.file(id))
    }

    fn font(&self, index: usize) -> Option<Font> {
        self.fonts.get(index).cloned()
    }

    fn today(&self, _offset: Option<Duration>) -> Option<Datetime> {
        // The host supplies a fixed timestamp; we deliberately never read the system clock, which
        // also makes compilations reproducible. The offset is already baked into what Kotlin sent.
        self.today
    }
}
