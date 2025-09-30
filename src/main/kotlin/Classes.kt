// ScenarioModel.kt
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class ScenarioFile(
    val scenario: String,
    val checks: List<Check> = emptyList(),
    val domain: Domain,
    val steps: List<Step>
)

@Serializable
data class Check(
    val name: String,
    @SerialName("true") val predicate: String // e.g. "light.status == LightStatus.GREEN"
)

@Serializable
data class Domain(
    val enums: List<EnumDef> = emptyList(),
    val classes: List<ClassDef> = emptyList()
)

@Serializable
data class EnumDef(val name: String, val values: List<String>)

@Serializable
data class ClassDef(
    val name: String,
    val properties: List<PropertyDef> = emptyList(),
    val methods: List<MethodDef> = emptyList()
)

@Serializable
data class PropertyDef(
    val name: String,
    val type: String,
    val mutable: Boolean = false,
    val initial: String? = null
)

@Serializable
data class MethodDef(
    val name: String,
    val inputs: List<ParamDef> = emptyList(),
    val effect: String // e.g. "set this.properties.status to LightStatus.GREEN"
)

@Serializable
data class ParamDef(val name: String, val type: String)

@Serializable
data class Step(
    val keyword: String, // "Given" | "When" | "Then"
    val values: List<ValueRef>? = null,        // Given
    val condition: String? = null,             // When (e.g. "checks.isLightGreen")
    val effect: String? = null                 // Then (e.g. "call values.robot.addToXPos with double 1.0")
)

@Serializable
data class ValueRef(val name: String, val type: String)


// Dsl.kt
@DslMarker annotation class ScenarioDsl

@ScenarioDsl
class ScenarioBuilder(val name: String) {
    private val checks = mutableListOf<Check>()
    private val enums = mutableListOf<EnumDef>()
    private val classes = mutableListOf<ClassDef>()
    private val steps = mutableListOf<Step>()

    fun domain(block: DomainBuilder.() -> Unit) {
        val d = DomainBuilder().apply(block).build()
        enums += d.enums
        classes += d.classes
    }

    fun checks(block: ChecksBuilder.() -> Unit) {
        checks += ChecksBuilder().apply(block).build()
    }

    fun steps(block: StepsBuilder.() -> Unit) {
        steps += StepsBuilder().apply(block).build()
    }

    fun build(): ScenarioFile = ScenarioFile(
        scenario = name,
        checks = checks.toList(),
        domain = Domain(enums.toList(), classes.toList()),
        steps = steps.toList()
    )
}

@ScenarioDsl
class DomainBuilder {
    val enums = mutableListOf<EnumDef>()
    val classes = mutableListOf<ClassDef>()

    fun enumType(name: String, vararg values: String) {
        enums += EnumDef(name, values.toList())
    }

    fun klass(name: String, block: ClassBuilder.() -> Unit) {
        classes += ClassBuilder(name).apply(block).build()
    }

    fun build() = Domain(enums, classes)
}

@ScenarioDsl
class ClassBuilder(private val name: String) {
    private val props = mutableListOf<PropertyDef>()
    private val methods = mutableListOf<MethodDef>()

    fun prop(name: String, type: String, mutable: Boolean = false, initial: String? = null) {
        props += PropertyDef(name, type, mutable, initial)
    }

    fun method(name: String, vararg inputs: ParamDef, effect: String) {
        methods += MethodDef(name, inputs.toList(), effect)
    }

    fun build() = ClassDef(name, props, methods)
}

@ScenarioDsl
class ChecksBuilder {
    private val checks = mutableListOf<Check>()
    fun check(name: String, predicate: String) {
        checks += Check(name, predicate)
    }
    fun build() = checks
}

@ScenarioDsl
class StepsBuilder {
    private val steps = mutableListOf<Step>()

    fun given(vararg values: Pair<String, String>) {
        steps += Step(keyword = "Given",
            values = values.map { ValueRef(it.first, it.second) })
    }

    fun whenCond(checkRef: String) {
        steps += Step(keyword = "When", condition = checkRef)
    }

    fun then(effect: String) {
        steps += Step(keyword = "Then", effect = effect)
    }

    fun build() = steps
}

fun scenario(name: String, block: ScenarioBuilder.() -> Unit): ScenarioFile =
    ScenarioBuilder(name).apply(block).build()


// UrscriptGenerator.kt
class UrscriptGenerator {

    fun generate(scn: ScenarioFile): String {
        val sb = StringBuilder()

        // --- 0) Build enum indices
        val enumIndex = scn.domain.enums.associate { e ->
            e.name to e.values.mapIndexed { i, v -> v to i }.toMap()
        }

        // --- 1) Emit enum constants
        scn.domain.enums.forEach { e ->
            e.values.forEachIndexed { i, v ->
                sb.appendLine("global ${e.name}_${v} = $i")
            }
        }
        sb.appendLine()

        // --- 2) Build Given bindings: variableName -> className (e.g., robot -> Robot)
        val givenStep = scn.steps.firstOrNull { it.keyword.equals("Given", true) }
        val binding: Map<String, String> =
            givenStep?.values.orEmpty().associate { it.name to it.type }

        // --- 3) Allocate class-scoped globals using class initial values
        // We only allocate for classes that appear in Given (keeps things minimal)
        val classesByName = scn.domain.classes.associateBy { it.name }
        binding.values.distinct().forEach { className ->
            val clazz = classesByName[className]
                ?: error("Given refers to unknown class: $className")
            clazz.properties.forEach { p ->
                val varName = "${className}_${p.name}"
                val initial = p.initial?.let { translateExpr(it, enumIndex, binding) }
                    ?: defaultFor(p.type)
                sb.appendLine("global $varName = $initial")
            }
        }
        sb.appendLine()

        // --- 4) Emit class methods as def Class_method(...)
        scn.domain.classes.forEach { c ->
            c.methods.forEach { m ->
                val params = m.inputs.joinToString(",") { it.name }
                sb.appendLine("def ${c.name}_${m.name}($params):")
                translateEffectAssignForClass(
                    c.name, m.effect, enumIndex, sb
                )
                sb.appendLine("end\n")
            }
        }

        // --- 5) Emit checks as def check_name(): return <expr>
        scn.checks.forEach { chk ->
            sb.appendLine("def check_${chk.name}():")
            val retExpr = translateExpr(chk.predicate, enumIndex, binding)
            sb.appendLine("  return $retExpr")
            sb.appendLine("end\n")
        }

        // --- 6) program(): When/Then
        val whenStep = scn.steps.firstOrNull { it.keyword.equals("When", true) }
        val thenStep = scn.steps.firstOrNull { it.keyword.equals("Then", true) }
        val checkRef = whenStep?.condition?.removePrefix("checks.") ?: ""

        sb.appendLine("def program():")
        if (checkRef.isNotBlank()) {
            sb.appendLine("  if check_${checkRef}():")
            thenStep?.effect?.let { emitCall(it, binding, sb) }
            sb.appendLine("  end")
        } else {
            thenStep?.effect?.let { emitCall(it, binding, sb) }
        }
        sb.appendLine("end")

        return sb.toString()
    }

    private fun defaultFor(type: String): String =
        when {
            type.equals("double", true) -> "0.0"
            type.startsWith("enums.") -> "0"
            else -> "0"
        }

    /**
     * Tiny expression translator:
     * - Enums: LightStatus.GREEN -> LightStatus_GREEN
     * - values.<obj>.<prop> -> <Class>_<prop>   (via Given binding)
     * - <obj>.<prop> -> <Class>_<prop>          (via Given binding)
     */
    private fun translateExpr(
        expr: String,
        enums: Map<String, Map<String, Int>>,
        binding: Map<String, String>
    ): String {
        var out = expr.trim()

        // Enums: LightStatus.GREEN -> LightStatus_GREEN
        enums.keys.forEach { en ->
            out = out.replace(Regex("""\b$en\.([A-Z_]+)\b""")) { m ->
                "${en}_${m.groupValues[1]}"
            }
        }

        // values.obj.prop -> Class_prop
        out = out.replace(Regex("""\bvalues\.([a-zA-Z_]\w*)\.([a-zA-Z_]\w*)\b""")) { m ->
            val obj = m.groupValues[1]
            val prop = m.groupValues[2]
            val cls = binding[obj] ?: error("Unknown value: $obj")
            "${cls}_${prop}"
        }

        // obj.prop -> Class_prop (only for bound obj names)
        out = out.replace(Regex("""\b([a-zA-Z_]\w*)\.([a-zA-Z_]\w*)\b""")) { m ->
            val obj = m.groupValues[1]
            val prop = m.groupValues[2]
            val cls = binding[obj]
            if (cls != null) "${cls}_${prop}" else m.value
        }

        return out
    }

    private fun unescapeBackslashSequences(s: String): String {
        // Order matters: handle CRLF first, then single escapes, then trailing backslashes.
        return s
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\\\", "\\")
    }

    /**
     * "set this.properties.xPos to this.properties.xPos + amount"
     * becomes: global Class_xPos = Class_xPos + amount
     */
    private fun translateEffectAssignForClass(
        className: String,
        effect: String,
        enumIndex: Map<String, Map<String, Int>>,
        sb: StringBuilder
    ) {
        val trimmed = effect.trim()

        // NEW: allow raw URScript bodies using "raw:" prefix
        if (trimmed.startsWith("raw:", ignoreCase = true)) {
            val bodyRaw = trimmed.removePrefix("raw:").trim()
            val body = unescapeBackslashSequences(bodyRaw)

            sb.appendLine(
                "  # raw effect: ${
                    body.replace("\n", " ").take(60)
                }${if (body.length > 60) "...\"" else "\""}"
            )

            // split on semicolons or newlines (one or more), strip empties
            body.split(Regex("""[;\r\n]+"""))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { line -> sb.appendLine("  $line") }
            return
        }

        val m = Regex("""set this\.properties\.([a-zA-Z_]\w*) to (.+)""").find(trimmed)
            ?: error("Unsupported effect: $effect")

        val prop = m.groupValues[1]
        var rhs = m.groupValues[2].trim()

        // this.properties.prop -> Class_prop
        rhs = rhs.replace(Regex("""\bthis\.properties\.([a-zA-Z_]\w*)\b""")) {
            "${className}_${it.groupValues[1]}"
        }

        // Enums in RHS
        enumIndex.keys.forEach { en ->
            rhs = rhs.replace(Regex("""\b$en\.([A-Z_]+)\b""")) {
                "${en}_${it.groupValues[1]}"
            }
        }

        sb.appendLine("  # effect: $effect")
        sb.appendLine("  global ${className}_${prop} = $rhs")
    }

    /**
     * "call values.robot.addToXPos with double 1.0"
     * -> Robot_addToXPos(1.0)
     */
    private fun emitCall(effect: String, binding: Map<String, String>, sb: StringBuilder) {
        val trimmed = effect.trim()

        // Pattern A: call values.obj.method with <args>
        val withArgs = Regex("""^call\s+values\.([a-zA-Z_]\w*)\.([a-zA-Z_]\w*)\s+with\s+(.+)$""")
            .find(trimmed)

        if (withArgs != null) {
            val obj = withArgs.groupValues[1]
            val method = withArgs.groupValues[2]
            val argsRaw = withArgs.groupValues[3]
            val cls = binding[obj] ?: error("Unknown value in call: $obj")
            val args = argsRaw.split(Regex("""\s*,\s*""")).map { token ->
                token.replace(Regex("""^(double|int|bool|string)\s+"""), "").trim()
            }
            sb.appendLine("    ${cls}_${method}(${args.joinToString(", ")})")
            return
        }

        // Pattern B: call values.obj.method   (no args)
        val noArgs = Regex("""^call\s+values\.([a-zA-Z_]\w*)\.([a-zA-Z_]\w*)\s*$""")
            .find(trimmed)

        if (noArgs != null) {
            val obj = noArgs.groupValues[1]
            val method = noArgs.groupValues[2]
            val cls = binding[obj] ?: error("Unknown value in call: $obj")
            sb.appendLine("    ${cls}_${method}()")
            return
        }

        error("Unsupported call: $effect")
    }
}
