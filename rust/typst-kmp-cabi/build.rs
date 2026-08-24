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
}
