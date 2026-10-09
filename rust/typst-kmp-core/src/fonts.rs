//! The engine's fonts: faces held in memory, and faces on disk that load on first use.
//!
//! A font handed over as bytes is parsed and kept whole. A font named by path is only *indexed*:
//! its metadata goes into the [`FontBook`] so Typst can select it, and the file is mapped into
//! memory the first time a document actually uses one of its faces. A machine's system fonts
//! can then be offered without reading hundreds of megabytes up front, and a mapped file is
//! paged in, and out again, by the operating system rather than held on the heap.

use std::sync::OnceLock;

use typst::text::{Font, FontBook, FontInfo};
use typst::utils::LazyHash;

/// One face the compiler can select.
pub struct FontSlot {
    info: FontInfo,
    source: Source,
}

enum Source {
    Memory(Font),
    /// Face `index` of the file at `path`, loaded on the first [`FontSlot::get`].
    #[cfg_attr(target_arch = "wasm32", allow(dead_code))]
    File {
        path: std::path::PathBuf,
        index: u32,
        font: OnceLock<Option<Font>>,
    },
}

impl FontSlot {
    /// The face, loading it first if it lives on disk. `None` if the file has become unreadable.
    pub fn get(&self) -> Option<Font> {
        match &self.source {
            Source::Memory(font) => Some(font.clone()),
            Source::File { path, index, font } => font
                .get_or_init(|| disk::map(path).and_then(|data| Font::new(data, *index)))
                .clone(),
        }
    }

    /// Whether the face is held in memory, rather than waiting on disk to be used.
    pub fn is_loaded(&self) -> bool {
        match &self.source {
            Source::Memory(_) => true,
            Source::File { font, .. } => font.get().is_some(),
        }
    }
}

/// Every face the engine knows, and the book Typst selects among them with.
pub struct Fonts {
    slots: Vec<FontSlot>,
    book: LazyHash<FontBook>,
}

impl Fonts {
    pub fn new() -> Self {
        Self {
            slots: Vec::new(),
            book: LazyHash::new(FontBook::new()),
        }
    }

    pub fn book(&self) -> &LazyHash<FontBook> {
        &self.book
    }

    pub fn slots(&self) -> &[FontSlot] {
        &self.slots
    }

    /// Adds fonts already parsed. Returns how many faces were added.
    pub fn add_loaded(&mut self, fonts: impl IntoIterator<Item = Font>) -> usize {
        let before = self.slots.len();
        self.slots.extend(fonts.into_iter().map(|font| FontSlot {
            info: font.info().clone(),
            source: Source::Memory(font),
        }));
        self.rebuild();
        self.slots.len() - before
    }

    /// Indexes the font file at `path`, or every font file under it if it is a directory.
    /// Returns how many faces were found.
    ///
    /// Fails if `path` does not exist; files inside a directory that are not fonts, or cannot be
    /// read, are passed over.
    pub fn add_path(&mut self, path: &str) -> Result<usize, String> {
        let before = self.slots.len();
        disk::scan(std::path::Path::new(path), true, &mut self.slots)?;
        self.rebuild();
        Ok(self.slots.len() - before)
    }

    /// Indexes the platform's usual font directories, skipping any that do not exist. Returns how
    /// many faces were found.
    pub fn add_system(&mut self) -> usize {
        let before = self.slots.len();
        for directory in disk::system_directories() {
            let _ = disk::scan(&directory, false, &mut self.slots);
        }
        self.rebuild();
        self.slots.len() - before
    }

    fn rebuild(&mut self) {
        self.book = LazyHash::new(FontBook::from_infos(
            self.slots.iter().map(|slot| slot.info.clone()),
        ));
    }
}

impl Default for Fonts {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(not(target_arch = "wasm32"))]
mod disk {
    use std::fs::File;
    use std::path::{Path, PathBuf};
    use std::sync::OnceLock;

    use memmap2::Mmap;
    use typst::foundations::Bytes;
    use typst::text::FontInfo;

    use super::{FontSlot, Source};

    /// How deep a directory walk goes, which also bounds a cycle of symbolic links.
    const MAX_DEPTH: usize = 16;

    /// Maps a font file into memory.
    pub fn map(path: &Path) -> Option<Bytes> {
        let file = File::open(path).ok()?;
        // SAFETY: the map is read-only. Should another process truncate the file while it is
        // mapped, reading it faults; that is the trade every mmap-based font loader makes,
        // `typst` and the operating system's own included, for not holding fonts on the heap.
        let map = unsafe { Mmap::map(&file) }.ok()?;
        Some(Bytes::new(map))
    }

    pub fn scan(path: &Path, explicit: bool, slots: &mut Vec<FontSlot>) -> Result<(), String> {
        let metadata = std::fs::metadata(path)
            .map_err(|err| format!("font path {} cannot be read: {err}", path.display()))?;
        if metadata.is_dir() {
            walk(path, 0, slots);
        } else if !index(path, slots) && explicit {
            return Err(format!("{} is not a font file", path.display()));
        }
        Ok(())
    }

    fn walk(directory: &Path, depth: usize, slots: &mut Vec<FontSlot>) {
        if depth > MAX_DEPTH {
            return;
        }
        let Ok(entries) = std::fs::read_dir(directory) else {
            return;
        };
        let mut paths: Vec<PathBuf> = entries
            .filter_map(|entry| Some(entry.ok()?.path()))
            .collect();
        // Sorted, so the same directory always yields the same font order and the same book.
        paths.sort();
        for path in paths {
            if path.is_dir() {
                walk(&path, depth + 1, slots);
            } else if has_font_extension(&path) {
                index(&path, slots);
            }
        }
    }

    /// Adds a slot per face of the file at `path`. Returns whether it held any.
    fn index(path: &Path, slots: &mut Vec<FontSlot>) -> bool {
        let Some(data) = map(path) else {
            return false;
        };
        let count = ttf_parser::fonts_in_collection(&data).unwrap_or(1);
        let before = slots.len();
        for face in 0..count {
            if let Some(info) = FontInfo::new(&data, face) {
                slots.push(FontSlot {
                    info,
                    source: Source::File {
                        path: path.to_path_buf(),
                        index: face,
                        font: OnceLock::new(),
                    },
                });
            }
        }
        // `data` drops here: the scan only touched the header tables, and the face is mapped
        // again, lazily, when a document uses it.
        slots.len() > before
    }

    fn has_font_extension(path: &Path) -> bool {
        path.extension()
            .and_then(|ext| ext.to_str())
            .is_some_and(|ext| {
                matches!(
                    ext.to_ascii_lowercase().as_str(),
                    "ttf" | "otf" | "ttc" | "otc"
                )
            })
    }

    fn home() -> Option<PathBuf> {
        std::env::var_os("HOME")
            .or_else(|| std::env::var_os("USERPROFILE"))
            .map(PathBuf::from)
    }

    /// Where each platform keeps its fonts. Only directories; what is in them is discovered.
    pub fn system_directories() -> Vec<PathBuf> {
        let mut directories: Vec<PathBuf> = Vec::new();
        if cfg!(target_os = "android") {
            directories.extend(["/system/fonts", "/product/fonts"].map(PathBuf::from));
        } else if cfg!(target_os = "ios") {
            directories.push(PathBuf::from("/System/Library/Fonts"));
        } else if cfg!(target_os = "macos") {
            directories.extend(
                [
                    "/System/Library/Fonts",
                    "/Library/Fonts",
                    "/Network/Library/Fonts",
                ]
                .map(PathBuf::from),
            );
            directories.extend(home().map(|home| home.join("Library/Fonts")));
        } else if cfg!(windows) {
            let windows = std::env::var_os("WINDIR")
                .or_else(|| std::env::var_os("SystemRoot"))
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("C:\\Windows"));
            directories.push(windows.join("Fonts"));
            if let Some(local) = std::env::var_os("LOCALAPPDATA") {
                directories.push(PathBuf::from(local).join("Microsoft\\Windows\\Fonts"));
            }
        } else {
            directories.extend(["/usr/share/fonts", "/usr/local/share/fonts"].map(PathBuf::from));
            let data_home = std::env::var_os("XDG_DATA_HOME")
                .map(PathBuf::from)
                .or_else(|| home().map(|home| home.join(".local/share")));
            directories.extend(data_home.map(|data| data.join("fonts")));
            directories.extend(home().map(|home| home.join(".fonts")));
        }
        directories
    }
}

/// The browser has no file system: a path can never be read, and there are no system fonts.
#[cfg(target_arch = "wasm32")]
mod disk {
    use std::path::{Path, PathBuf};

    use typst::foundations::Bytes;

    use super::FontSlot;

    pub fn map(_path: &Path) -> Option<Bytes> {
        None
    }

    pub fn scan(path: &Path, _explicit: bool, _slots: &mut Vec<FontSlot>) -> Result<(), String> {
        Err(format!(
            "font path {} cannot be read: the browser has no file system",
            path.display()
        ))
    }

    pub fn system_directories() -> Vec<PathBuf> {
        Vec::new()
    }
}
