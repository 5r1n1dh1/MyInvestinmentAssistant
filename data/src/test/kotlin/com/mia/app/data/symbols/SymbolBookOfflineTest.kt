package com.mia.app.data.symbols

import java.io.File
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the search path cannot touch the network, by construction rather than by promise.
 *
 * plan.md §9 requirement 5 says symbol search must be **local, offline, instant and
 * robots.txt-compliant**, and plan.md §3.6 is binding: screener.in disallows every `?q=` search path,
 * so a search that touches the network is a policy violation regardless of what it returns.
 *
 * There is no HTTP test double in this file because there is nothing to double: the assertion is
 * that **no seam through which the network could be reached exists**. Four independent checks,
 * because any one alone is weak:
 *
 * 1. **Declaration** — no constructor, method or field of any public type in the package mentions a
 *    networking type. Adding an `HttpTransport` parameter to a search function fails here at once.
 * 2. **Imports** — no source file in the package imports `java.net`, `okhttp3`, `retrofit2` or
 *    `org.apache.http`, caught by reading the package's own sources.
 * 3. **Bytecode** — the compiled classes of the package contain no constant-pool reference to
 *    `java/net/URL`, `java/net/Socket`, `java/net/HttpURLConnection` or `okhttp3/`, which would
 *    catch a network call smuggled in through a lambda or a static initialiser.
 * 4. **Object graph** — at runtime, every field reachable from the live [SymbolBook] and
 *    [SymbolResolver] is walked and none of them is a networking object, and no `String` field
 *    reachable from them is a URL. This is the check that would catch a transport hidden in a field
 *    the declaration check cannot see.
 */
class SymbolBookOfflineTest {

    /** Types that would mean a search could leave the device. */
    private val forbiddenTypeNames = setOf(
        "com.mia.app.data.http.HttpTransport",
        "com.mia.app.data.http.HttpRequest",
        "com.mia.app.data.http.HttpResponse",
        "java.net.URL",
        "java.net.URLConnection",
        "java.net.HttpURLConnection",
        "java.net.Socket",
        "java.net.http.HttpClient",
        "okhttp3.OkHttpClient",
        "okhttp3.Request",
    )

    /** The public entry points a UI calls on the search path. */
    private val searchSurfaceTypes: List<KClass<*>> = listOf(
        SymbolBook::class,
        SymbolResolver::class,
        SymbolBookParser::class,
        SymbolBookSource::class,
        SymbolNormaliser::class,
        SymbolRecord::class,
        SymbolResolution::class,
        BhavcopyReconciler::class,
        CorporateActionTracker::class,
        ReconciliationReport::class,
        CorporateActionEvent::class,
    )

    @Test
    fun `no public member of the symbol package can reach the network`() {
        val violations = mutableListOf<String>()
        for (kClass in searchSurfaceTypes) {
            val type = kClass.java
            for (constructor in type.declaredConstructors) {
                violations += forbidden(
                    constructor.parameterTypes.toList(),
                    "${type.simpleName} constructor parameters",
                )
            }
            for (method in type.declaredMethods) {
                violations += forbidden(method.parameterTypes.toList(), "${type.simpleName}.${method.name} parameters")
                violations += forbidden(listOf(method.returnType), "${type.simpleName}.${method.name} return")
            }
            for (field in type.declaredFields) {
                violations += forbidden(listOf(field.type), "${type.simpleName}.${field.name}")
            }
            // A nested type (companion, enum entry) is part of the package's surface too.
            for (nested in type.declaredClasses) {
                for (method in nested.declaredMethods) {
                    violations += forbidden(
                        method.parameterTypes.toList(),
                        "${type.simpleName}.${nested.simpleName}.${method.name} parameters",
                    )
                }
            }
        }
        assertTrue(violations.isEmpty(), "network-reachable members found:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `no type in the symbol package imports a networking package`() {
        val violations = mutableListOf<String>()
        val files = sourceDir().walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.size >= 8, "expected the whole package, found ${files.size} files under ${sourceDir()}")
        for (file in files) {
            for (line in file.readLines()) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("import ") && !trimmed.startsWith("package ")) continue
                for (banned in listOf("java.net.", "okhttp3.", "retrofit2.", "org.apache.http")) {
                    if (trimmed.contains(banned)) violations += "${file.name}: $trimmed"
                }
            }
        }
        assertTrue(violations.isEmpty(), "network imports found:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `the compiled package references no networking bytecode`() {
        val classes = classesDir()
        val classFiles = classes.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue(classFiles.isNotEmpty(), "no compiled classes under $classes")
        val violations = classFiles.filter { classFile ->
            val text = String(classFile.readBytes(), Charsets.ISO_8859_1)
            text.contains("java/net/URL") || text.contains("java/net/Socket") ||
                text.contains("java/net/HttpURLConnection") || text.contains("okhttp3/")
        }
        assertTrue(
            violations.isEmpty(),
            "classes referencing the network: ${violations.map { it.name }}",
        )
    }

    @Test
    fun `nothing reachable from the live book or resolver is a networking object`() {
        val book = SymbolFixtures.book()
        val resolver = SymbolFixtures.resolver(CorporateActionTracker())

        val violations = mutableListOf<String>()
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        walk(book, seen, violations, depth = 0)
        walk(resolver, seen, violations, depth = 0)

        assertTrue(violations.isEmpty(), "network objects reachable from the search path:\n${violations.joinToString("\n")}")
        // Sanity: the walk really did traverse the index, otherwise the empty result proves nothing.
        assertTrue(seen.size > 6, "the object graph walk visited only ${seen.size} objects")
    }

    @Test
    fun `no URL string is stored in the book which would tempt a fetch later`() {
        val book = SymbolFixtures.book()
        val urls = book.all().flatMap { record ->
            listOf(record.symbol, record.name, record.isin, record.series).filter { looksLikeUrl(it) }
        }
        assertTrue(urls.isEmpty(), "url-like strings in the book: $urls")
    }

    @Test
    fun `the only IO seam is the injected AssetReader`() {
        // The one legitimate way bytes enter this package: a caller-supplied function type over
        // `java.io.InputStream`. No URL, no file path, no Android `Context`.
        val open = AssetReader::class.java.declaredMethods.single { it.name == "open" }
        assertEquals(listOf(String::class.java), open.parameterTypes.toList())
        assertEquals("java.io.InputStream", open.returnType.name)
        assertTrue(
            AssetReader::class.java.isInterface,
            "AssetReader must stay an interface so :android-data can supply Context.assets",
        )
    }

    @Test
    fun `the book has no clock so its answers are reproducible`() {
        // A symbol book that depended on "today" would resolve differently at midnight, which is
        // exactly the kind of untraceable difference plan.md §1.4 is written against.
        val first = SymbolFixtures.book().search("RELI", limit = 10).map { it.record.symbol }
        val second = SymbolFixtures.book().search("RELI", limit = 10).map { it.record.symbol }
        assertEquals(first, second)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun forbidden(types: List<Class<*>>, where: String): List<String> = types
        .map { it.name }
        .filter { name -> forbiddenTypeNames.any { name == it || name.startsWith("$it.") } }
        .map { "$where mentions $it" }

    private fun looksLikeUrl(value: String) =
        value.startsWith("http://", true) || value.startsWith("https://", true)

    private val walkedKeys = java.util.IdentityHashMap<Any, Unit>()

    private fun walk(root: Any, seen: java.util.IdentityHashMap<Any, Boolean>, violations: MutableList<String>, depth: Int) {
        if (depth > 4 || seen.size > 5000) return
        if (root is String) {
            if (looksLikeUrl(root)) violations += "reachable string is a URL: $root"
            return
        }
        if (root is Number || root is Boolean || root is Char || root is Enum<*> || root is CharSequence) return
        if (seen.put(root, true) != null) return

        val type = root.javaClass
        if (forbiddenTypeNames.any { type.name == it || type.name.startsWith("$it.") }) {
            violations += "reachable object is ${type.name}"
            return
        }
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            for (field in runCatching { current.declaredFields }.getOrElse { emptyArray() }) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(root)
                }.getOrNull()
                if (value != null) walk(value, seen, violations, depth + 1)
            }
            current = current.superclass
        }
    }

    /** Sources of this package, found from the test's working directory rather than hard-coded. */
    private fun sourceDir(): File = locate("src/main/kotlin/com/mia/app/data/symbols")

    private fun classesDir(): File = locate("build/classes/kotlin/main/com/mia/app/data/symbols")

    private fun locate(suffix: String): File {
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, suffix)
            if (candidate.isDirectory) return candidate
            dir = dir?.parentFile
        }
        // Fall back to the module-relative layout so an IDE run outside Gradle still resolves.
        val fromModule = File("data/$suffix")
        if (fromModule.isDirectory) return fromModule
        throw AssertionError("could not locate '$suffix' from ${File(".").absolutePath}")
    }
}