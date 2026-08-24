package io.github.phfneves.typst

public open class TypstException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * A failure inside the native library: a bad handle, an allocation failure, or a caught panic.
 *
 * The JNI layer throws this type by name, so its fully qualified name must stay
 * `io.github.phfneves.typst.TypstNativeException`.
 */
public class TypstNativeException(message: String) : TypstException(message)

/** Thrown by [CompileResult.getOrThrow] when the document did not compile. */
public class TypstCompilationException(
    public val errors: List<Diagnostic>,
    public val unresolved: List<Unresolved>,
) : TypstException(buildMessage(errors, unresolved)) {

    private companion object {
        fun buildMessage(errors: List<Diagnostic>, unresolved: List<Unresolved>): String =
            buildString {
                append("Typst compilation failed")
                if (errors.isNotEmpty()) {
                    append(':')
                    errors.forEach { append("\n  ").append(it) }
                }
                if (unresolved.isNotEmpty()) {
                    append("\n  unresolved: ")
                    append(
                        unresolved.joinToString(", ") { entry ->
                            when (entry) {
                                is Unresolved.File -> entry.path
                                is Unresolved.Package -> entry.spec.toString()
                            }
                        },
                    )
                }
            }
    }
}
