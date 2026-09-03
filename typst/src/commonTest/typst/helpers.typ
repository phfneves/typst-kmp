// Imported by report.typ. Its only job is to prove that an import is resolved through the VFS
// rather than through a filesystem the Rust side does not have.

#let callout(body) = block(
  fill: luma(240),
  stroke: (left: 2pt + luma(150)),
  inset: 8pt,
  radius: 2pt,
  width: 100%,
  body,
)

#let sample-table = table(
  columns: (auto, auto, auto),
  align: (left, left, right),
  table.header([*Alvo*], [*Ponte*], [*Artefato*]),
  [JVM], [JNI], [`.so` / `.dylib` / `.dll`],
  [Android], [JNI], [`jniLibs/<abi>`],
  [Kotlin/Native], [cinterop], [`.a` no klib],
)
