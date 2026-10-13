//! [`typst::World`] implementation backed entirely by the in-memory [`Vfs`].

use std::collections::BTreeSet;
use std::sync::Mutex;

use typst::diag::{FileError, FileResult};
use typst::foundations::{Bytes, Datetime, Duration};
use typst::syntax::{FileId, Source, VirtualRoot};
use typst::text::{Font, FontBook};
use typst::utils::LazyHash;
use typst::{Library, World};

use crate::cancel::{self, CancelToken};
use crate::fonts::Fonts;
use crate::protocol::Missing;
use crate::vfs::{format_path, Vfs};

pub struct KmpWorld<'a> {
    library: LazyHash<Library>,
    fonts: &'a Fonts,
    vfs: &'a Vfs,
    /// Files belonging to this compilation alone, consulted before [`Self::vfs`].
    overlay: &'a Vfs,
    main: FileId,
    today: Option<Datetime>,
    /// Every lookup that failed, recorded structurally so the Kotlin resolution loop can act on
    /// it without having to parse human-readable diagnostic text.
    misses: Mutex<BTreeSet<Missing>>,
    /// Checked on every call the compiler makes into the world; see [`crate::cancel`].
    cancel: Option<&'a CancelToken>,
}

impl<'a> KmpWorld<'a> {
    pub fn new(
        library: Library,
        fonts: &'a Fonts,
        vfs: &'a Vfs,
        overlay: &'a Vfs,
        main: FileId,
        today: Option<Datetime>,
        cancel: Option<&'a CancelToken>,
    ) -> Self {
        Self {
            library: LazyHash::new(library),
            fonts,
            vfs,
            overlay,
            main,
            today,
            misses: Mutex::new(BTreeSet::new()),
            cancel,
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

    /// The layer that answers for `id`: the overlay when it has the file, the shared VFS otherwise.
    fn layer(&self, id: FileId) -> &Vfs {
        if self.overlay.contains(id) {
            self.overlay
        } else {
            self.vfs
        }
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
        cancel::check(self.cancel);
        &self.library
    }

    fn book(&self) -> &LazyHash<FontBook> {
        cancel::check(self.cancel);
        self.fonts.book()
    }

    fn main(&self) -> FileId {
        cancel::check(self.cancel);
        self.main
    }

    fn source(&self, id: FileId) -> FileResult<Source> {
        cancel::check(self.cancel);
        self.recording(id, self.layer(id).source(id))
    }

    fn file(&self, id: FileId) -> FileResult<Bytes> {
        cancel::check(self.cancel);
        self.recording(id, self.layer(id).file(id))
    }

    fn font(&self, index: usize) -> Option<Font> {
        cancel::check(self.cancel);
        self.fonts.slots().get(index)?.get()
    }

    fn today(&self, _offset: Option<Duration>) -> Option<Datetime> {
        // The host supplies a fixed timestamp; we deliberately never read the system clock, which
        // also makes compilations reproducible. The offset is already baked into what Kotlin sent.
        self.today
    }
}
