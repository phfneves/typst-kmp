use std::env;
use std::path::PathBuf;

fn main() {
    let crate_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let header = crate_dir.join("include").join("typst_kmp.h");
    std::fs::create_dir_all(header.parent().expect("header parent")).expect("create include dir");

    // Regenerating on every build keeps the header in lockstep with the ABI, which is what the
    // cinterop .def file consumes.
    match cbindgen::generate(&crate_dir) {
        Ok(bindings) => {
            bindings.write_to_file(&header);
        }
        Err(err) => {
            // Do not fail the build in environments where cbindgen cannot run (for example when
            // the header is already checked in); surface it loudly instead.
            println!("cargo:warning=cbindgen failed: {err}");
        }
    }

    println!("cargo:rerun-if-changed=src/lib.rs");
    println!("cargo:rerun-if-changed=cbindgen.toml");
    // The header this script writes is also watched, so that a cached `target/` cannot leave the
    // build "fresh" while the header the cinterop reads is gone — deleted by a clean, or simply
    // never produced on this machine. cbindgen skips the write when the content is unchanged, so
    // this does not chase its own timestamp into a rebuild on every invocation.
    println!("cargo:rerun-if-changed=include/typst_kmp.h");
}
