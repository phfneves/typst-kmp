// End-to-end fixture for typst-kmp.
//
// Every platform's test suite compiles this exact document and writes the resulting PDF out, so
// the output can be opened and inspected by hand. It deliberately exercises the parts of the
// pipeline that are easy to get subtly wrong across an FFI boundary: multiple pages, an import
// resolved through the VFS, `sys.inputs`, non-ASCII text, maths, tables and a queryable label.

#import "/helpers.typ": callout, sample-table

#let author = sys.inputs.at("author", default: "unknown")
#let build = sys.inputs.at("build", default: "dev")

#set document(title: "typst-kmp end-to-end report", author: author)
#set page(paper: "a5", margin: 2cm, numbering: "1 / 1")
#set text(size: 10pt, lang: "pt")
#set heading(numbering: "1.1")

#align(center)[
  #text(size: 20pt, weight: "bold")[Relatório typst-kmp]
  #v(0.4em)
  #text(size: 10pt)[Gerado por #author · build #build]
]

#v(1em)

= Introdução

Este documento é compilado por *todas* as plataformas suportadas — JVM, Android,
iOS, macOS, Linux e Windows — a partir do mesmo código em `commonMain`. Se o PDF
que você está lendo foi produzido pelo teste de ponta a ponta, então o motor Rust,
a fronteira FFI e a camada Kotlin funcionaram juntos.

#callout[
  Acentuação é intencional: `á é í ó ú ã õ ç`. Se estes caracteres saírem errados,
  algo se perdeu na conversão UTF-8 entre Kotlin e Rust.
]

= Composição matemática

O compilador precisa resolver fontes matemáticas para renderizar isto:

$ integral_0^oo e^(-x^2) dif x = sqrt(pi) / 2 $

E, em linha, $a^2 + b^2 = c^2$ deve permanecer alinhado com o texto ao redor.

= Tabela

#sample-table

#pagebreak()

= Segunda página

Esta página existe para que o teste possa afirmar que a saída tem exatamente duas
páginas — o que também prova que os exportadores SVG e PNG emitem um blob por página.

== Lista

+ Motor Rust sem qualquer I/O
+ VFS explícito preenchido pelo hospedeiro
+ Diagnósticos estruturados em vez de texto analisado

== Bloco de código

```kotlin
Typst.create().use { typst ->
    val pdf = typst.compile(CompileRequest.of(source)).getOrThrow()
}
```

#metadata("typst-kmp") <marker>
