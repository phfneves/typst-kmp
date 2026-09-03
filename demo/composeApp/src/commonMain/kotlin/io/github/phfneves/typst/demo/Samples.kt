package io.github.phfneves.typst.demo

/**
 * A document the demo can load, as the in-memory files a [io.github.phfneves.typst.CompileRequest]
 * takes.
 *
 * [main] is what the editor shows and lets you change; [extraFiles] are the ones it imports. They
 * are kept separate because the point of the third sample is that a diagnostic must point at the
 * *imported* file, which only means something if that file is not the one being edited.
 */
class TypstSample(
    val name: String,
    val main: String,
    val extraFiles: Map<String, String> = emptyMap(),
)

/** The path the editor's contents are mounted at, and the entry point of every sample. */
const val MAIN_PATH: String = "/main.typ"

private const val HELPERS_PATH = "/helpers.typ"

private val LETTER = """
    #set page(width: 14cm, height: 20cm, margin: 1.5cm)
    #set text(font: "Libertinus Serif", size: 11pt, lang: "pt")

    #align(right)[#datetime.today().display("[day]/[month]/[year]")]

    = Olá do typst-kmp

    Este documento foi compilado *dentro* do aplicativo, em
    #emph(sys.inputs.at("platform", default: "algum lugar")) — sem processo externo,
    sem servidor e sem escrever nada em disco.

    Edite o texto ao lado e a página se refaz sozinha.

    #v(1fr)

    #align(center)[
      #box(inset: 8pt, radius: 4pt, fill: luma(235))[
        `typst-kmp` · compilador Typst embarcado
      ]
    ]
""".trimIndent()

private val REPORT = """
    #import "$HELPERS_PATH": callout, resultados

    #set page(width: 14cm, height: 20cm, margin: 1.5cm, numbering: "1")
    #set text(font: "Libertinus Serif", size: 10pt, lang: "pt")
    #set heading(numbering: "1.")

    = Relatório de exemplo

    Compilado em #emph(sys.inputs.at("platform", default: "?")).

    #callout[
      Duas fontes alimentam esta página: `$MAIN_PATH`, que o editor mostra, e
      `$HELPERS_PATH`, que ele importa. Ambas vivem só na memória.
    ]

    == Uma tabela

    #resultados

    == Um pouco de matemática

    ${'$'} integral_0^1 x^2 dif x = 1/3 ${'$'}

    #pagebreak()

    = Segunda página

    O exportador de PNG devolve uma imagem por página, e é exatamente isso que a
    lista de páginas mostra. O PDF sai do mesmo passe de layout.
""".trimIndent()

private val HELPERS = """
    #let callout(body) = block(
      width: 100%,
      inset: 10pt,
      radius: 4pt,
      fill: rgb("#eef3ff"),
      stroke: (left: 3pt + rgb("#3b6cf2")),
      body,
    )

    #let resultados = table(
      columns: (auto, 1fr, auto),
      align: (left, left, right),
      table.header[*Plataforma*][*Ligação*][*Biblioteca*],
      [Android], [JNI], [`libtypst_kmp_jni.so`],
      [JVM], [JNI], [`typst_kmp_jni.dll`],
      [iOS], [cinterop], [`libtypst_kmp_cabi.a`],
    )
""".trimIndent()

/**
 * The same report, but with `resultados` bound to a name that does not exist.
 *
 * The error is on line 2 of the imported file, so the diagnostics panel has to report a path and a
 * line that are not the editor's — which is the whole reason this sample is here.
 */
private val BROKEN_HELPERS = """
    #let callout(body) = body
    #let resultados = tabela-que-nao-existe
""".trimIndent()

object TypstSamples {

    val list: List<TypstSample> = listOf(
        TypstSample(name = "Carta", main = LETTER),
        TypstSample(
            name = "Relatório",
            main = REPORT,
            extraFiles = mapOf(HELPERS_PATH to HELPERS),
        ),
        TypstSample(
            name = "Com erro",
            main = REPORT,
            extraFiles = mapOf(HELPERS_PATH to BROKEN_HELPERS),
        ),
    )

    val first: TypstSample get() = list.first()
}
