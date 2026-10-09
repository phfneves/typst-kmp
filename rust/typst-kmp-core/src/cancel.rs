//! Interrupting a compilation from another thread.
//!
//! Typst offers no way to stop a compilation, so the [`World`](typst::World) stops it instead:
//! every time the compiler asks it for something — a source file, a font, the standard library —
//! it checks a [`CancelToken`], and once the token is set it unwinds out of the compiler with a
//! private payload. [`catch`] turns exactly that payload back into an error and lets any other
//! panic carry on.
//!
//! Unwinding through the compiler is safe for the caches it keeps between compilations: a
//! memoised result is stored only once the call that computes it returns, so a cancelled call
//! leaves nothing behind, and the next compilation sees the caches as the last finished one left
//! them. `cancelled_compilations_leave_the_engine_intact` in `lib.rs` checks exactly that.
//!
//! WebAssembly cannot unwind, so in the browser a token is never set and nothing here runs.

use std::panic::{catch_unwind, resume_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};

/// The error a cancelled compilation returns.
pub const CANCELLED: &str = "compilation cancelled";

/// Set from any thread to stop the compilation it was handed to.
#[derive(Debug, Default)]
pub struct CancelToken(AtomicBool);

impl CancelToken {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn cancel(&self) {
        self.0.store(true, Ordering::Relaxed);
    }

    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::Relaxed)
    }
}

/// The unwind payload, private so that no other panic can be mistaken for it.
struct Cancelled;

/// Unwinds out of the compiler if `token` has been set.
///
/// `resume_unwind` rather than `panic!`: it skips the panic hook, so a cancellation prints
/// nothing to standard error.
pub(crate) fn check(token: Option<&CancelToken>) {
    if token.is_some_and(CancelToken::is_cancelled) {
        resume_unwind(Box::new(Cancelled));
    }
}

/// Runs `body`, turning a cancellation inside it into [`CANCELLED`].
pub(crate) fn catch<T>(body: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(result) => result,
        Err(payload) if payload.is::<Cancelled>() => Err(CANCELLED.to_string()),
        Err(payload) => resume_unwind(payload),
    }
}
