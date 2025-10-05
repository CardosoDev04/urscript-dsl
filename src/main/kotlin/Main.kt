import kotlinx.serialization.*
import kotlinx.serialization.json.*

// === Paste ScenarioModel, DSL, and UrscriptGenerator from previous answer here ===

// For demo, we’ll just hardcode your JSON
fun main() {
    val inputJson = """
        {
  "scenario": "Move robot forward when light is green",
  "checks": [
    { "name": "isLightGreen", "true": "light.status == LightStatus.GREEN" }
  ],
  "domain": {
    "enums": [
      { "name": "LightStatus", "values": ["GREEN", "RED", "YELLOW"] }
    ],
    "classes": [
      {
        "name": "Light",
        "properties": [
          {
            "name": "status",
            "type": "enums.LightStatus",
            "mutable": true,
            "initial": "LightStatus.GREEN"
          }
        ],
        "methods": [
          {
            "name": "turnGreen",
            "inputs": [],
            "effect": "set this.properties.status to LightStatus.GREEN"
          },
          {
            "name": "turnRed",
            "inputs": [],
            "effect": "set this.properties.status to LightStatus.RED"
          }
        ]
      },
      {
        "name": "Robot",
        "properties": [
          { "name": "xPos", "type": "double", "mutable": true, "initial": "0.0" }
        ],
        "methods": [
          {
            "name": "addToXPos",
            "inputs": [ { "name": "amount", "type": "double" } ],
            "effect": "set this.properties.xPos to this.properties.xPos + amount"
          }
        ]
      },
      {
        "name": "Program",
        "methods": [
          {
            "name": "whenGreen",
            "inputs": [],
            "effect": "raw: set_standard_digital_out(0, False);\\nset_standard_digital_out(1, False);\\nset_standard_digital_out(2, False);\\nset_standard_digital_out(0, True);\\npose_target = pose_trans(get_actual_tcp_pose(), p[0.05, 0, 0, 0, 0, 0]);\\nmovel(pose_target, 0.5, 0.10, 0, 0);\\nLight_turnGreen();\\nRobot_addToXPos(0.05)"
          }
        ]
      }
    ]
  },
  "steps": [
    {
      "keyword": "Given",
      "values": [
        { "name": "robot", "type": "Robot" },
        { "name": "light", "type": "Light" },
        { "name": "prog",  "type": "Program" }
      ]
    },
    {
      "keyword": "When",
      "condition": "checks.isLightGreen"
    },
    {
      "keyword": "Then",
      "effect": "call values.prog.whenGreen"
    }
  ]
}
    """.trimIndent()

    fun scenarioFileToDsl(scn: ScenarioFile): String {
        fun quote(s: String) = "\"" + s.replace("\"", "\\\"") + "\""

        val sb = StringBuilder()
        sb.appendLine("scenario(${quote(scn.scenario)}) {")
        // --- Domain
        sb.appendLine("    domain {")
        for (e in scn.domain.enums) {
            sb.appendLine("        enumType(${quote(e.name)}, ${e.values.joinToString(", ") { quote(it) }})")
        }
        for (c in scn.domain.classes) {
            sb.appendLine("        klass(${quote(c.name)}) {")
            for (p in c.properties) {
                val mut = if (p.mutable) ", mutable = true" else ""
                val init = p.initial?.let { ", initial = ${it}" } ?: ""
                sb.appendLine("            prop(${quote(p.name)}, ${quote(p.type)}$mut$init)")
            }
            for (m in c.methods) {
                val params = m.inputs.joinToString(", ") { "ParamDef(${quote(it.name)}, ${quote(it.type)})" }
                val paramsStr = if (params.isNotEmpty()) ", $params" else ""
                val effectStr = if (m.effect.contains("\n") || m.effect.contains("\"\"\"")) {
                    ", effect = \"\"\"${m.effect}\"\"\""
                } else {
                    ", effect = ${quote(m.effect)}"
                }
                sb.appendLine("            method(${quote(m.name)}$paramsStr$effectStr)")
            }
            sb.appendLine("        }")
        }
        sb.appendLine("    }")
        // --- Checks
        if (scn.checks.isNotEmpty()) {
            sb.appendLine("    checks {")
            for (chk in scn.checks) {
                sb.appendLine("        check(${quote(chk.name)}, ${quote(chk.predicate)})")
            }
            sb.appendLine("    }")
        }
        // --- Steps
        sb.appendLine("    steps {")
        for (step in scn.steps) {
            when (step.keyword) {
                "Given" -> {
                    val args = step.values.orEmpty().joinToString(", ") { "${quote(it.name)} to ${quote(it.type)}" }
                    sb.appendLine("        given($args)")
                }

                "When" -> {
                    val cond = step.condition ?: ""
                    sb.appendLine("        whenCond(${quote(cond)})")
                }

                "Then" -> {
                    val eff = step.effect ?: ""
                    sb.appendLine("        then(${quote(eff)})")
                }
            }
        }
        sb.appendLine("    }")
        sb.appendLine("}")
        return sb.toString()
    }

    fun dslToJson(scenarioBuilder: ScenarioBuilder.() -> Unit): String {
        val scenario = ScenarioBuilder("fromDsl").apply(scenarioBuilder).build()
        return Json { prettyPrint = true }.encodeToString(scenario)
    }

    fun parseDslToScenarioFile(dsl: String): ScenarioFile {
        fun unquote(s: String) = s.trim().removeSurrounding("\"")

        // Balanced-brace extractor: returns the INSIDE of the outer {...}
        fun extractBlock(name: String, src: String): String? {
            val startKeyword = Regex("""\b$name\s*\{""")
            val m = startKeyword.find(src) ?: return null
            val openIdx = src.indexOf('{', m.range.first)
            if (openIdx == -1) return null

            var i = openIdx + 1
            var depth = 1
            while (i < src.length && depth > 0) {
                when (src[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                i++
            }
            if (depth != 0) error("Unbalanced braces for block '$name'")
            // return content between the outermost braces
            return src.substring(openIdx + 1, i - 1)
        }

        val scenarioName = Regex("""scenario\((.*?)\)\s*\{""")
            .find(dsl)?.groupValues?.get(1)?.let { unquote(it) }
            ?: error("No scenario name found")

        val domainBlock = extractBlock("domain", dsl) ?: error("No domain block")

        // --- Enums
        val enums = Regex("""enumType\(\s*(.*?)\s*,\s*(.*?)\s*\)""")
            .findAll(domainBlock)
            .map { m ->
                val name = unquote(m.groupValues[1])
                val valuesCsv = m.groupValues[2]
                val values = Regex("""\"(.*?)\"""")
                    .findAll(valuesCsv)
                    .map { it.groupValues[1] }
                    .toList()
                EnumDef(name, values)
            }.toList()

        // --- Classes (balanced each body)
        val classes = run {
            val results = mutableListOf<ClassDef>()
            val headerRegex = Regex("""klass\(\s*(.*?)\s*\)\s*\{""")
            var searchIdx = 0
            while (true) {
                val m = headerRegex.find(domainBlock, searchIdx) ?: break
                val className = unquote(m.groupValues[1])
                val openIdx = domainBlock.indexOf('{', m.range.first)
                var i = openIdx + 1
                var depth = 1
                while (i < domainBlock.length && depth > 0) {
                    when (domainBlock[i]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    i++
                }
                if (depth != 0) error("Unbalanced braces in klass '$className'")
                val body = domainBlock.substring(openIdx + 1, i - 1)

                // props
                val props = Regex(
                    """prop\(\s*(.*?)\s*,\s*(.*?)\s*(?:,\s*mutable\s*=\s*true)?(?:,\s*initial\s*=\s*(.*?))?\)"""
                ).findAll(body).map { p ->
                    val name = unquote(p.groupValues[1])
                    val type = unquote(p.groupValues[2])
                    val mutable = p.value.contains("mutable", ignoreCase = true)
                    val initial = p.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.trim()
                    PropertyDef(name, type, mutable, initial)
                }.toList()

                // methods
                val methods = Regex(
                    """method\(\s*(.*?)\s*(?:,\s*((?:ParamDef\([^)]*\)\s*,\s*)*ParamDef\([^)]*\))\s*)?,\s*effect\s*=\s*(\"\"\".*?\"\"\"|\".*?\")\s*\)""",
                    RegexOption.DOT_MATCHES_ALL
                ).findAll(body).map { mm ->
                    val name = unquote(mm.groupValues[1])
                    val paramsRaw = mm.groupValues[2]
                    val params = if (paramsRaw.isBlank()) emptyList() else
                        Regex("""ParamDef\(\s*(.*?)\s*,\s*(.*?)\s*\)""")
                            .findAll(paramsRaw)
                            .map { pm -> ParamDef(unquote(pm.groupValues[1]), unquote(pm.groupValues[2])) }
                            .toList()
                    val effect = mm.groupValues[3].removeSurrounding("\"\"\"").removeSurrounding("\"")
                    MethodDef(name, params, effect)
                }.toList()

                results += ClassDef(className, props, methods)
                searchIdx = i
            }
            results.toList()
        }

        // --- Checks
        val checksBlock = extractBlock("checks", dsl)
        val checks = checksBlock?.let {
            Regex("""check\(\s*(.*?)\s*,\s*(.*?)\s*\)""")
                .findAll(it).map { m ->
                    Check(unquote(m.groupValues[1]), unquote(m.groupValues[2]))
                }.toList()
        } ?: emptyList()

        // --- Steps
        val stepsBlock = extractBlock("steps", dsl) ?: error("No steps block")
        val steps = Regex("""(given|whenCond|then)\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(stepsBlock).map { m ->
                when (m.groupValues[1]) {
                    "given" -> {
                        // Parse pairs like: "robot" to "Robot", "light" to "Light", ...
                        val pairs = Regex("""\"(.*?)\"\s*to\s*\"(.*?)\"""")
                            .findAll(m.groupValues[2])
                            .map { ValueRef(it.groupValues[1], it.groupValues[2]) }
                            .toList()
                        Step("Given", pairs, null, null)
                    }
                    "whenCond" -> Step("When", null, unquote(m.groupValues[2].trim()), null)
                    "then" -> Step("Then", null, null, unquote(m.groupValues[2].trim()))
                    else -> error("Unknown step: ${m.groupValues[1]}")
                }
            }.toList()

        return ScenarioFile(
            scenario = scenarioName,
            checks = checks,
            domain = Domain(enums, classes),
            steps = steps
        )
    }

    val json = Json { ignoreUnknownKeys = true }
    val scenario = json.decodeFromString<ScenarioFile>(inputJson)

    val urscript = UrscriptGenerator().generate(scenario)
    println("=== Generated URScript ===")
    println(urscript)

    val dsl = scenarioFileToDsl(scenario)
    println("=== Reconstructed DSL ===")
    println(dsl)

    val modifiedDsl = dsl.replace("initial = LightStatus.GREEN", "initial = LightStatus.RED")
    val parsed = parseDslToScenarioFile(modifiedDsl)
    val modifiedJson = json.encodeToString(parsed)
    val modifiedUrscript = UrscriptGenerator().generate(parsed)
    println("=== Modified URScript after DSL change ===")
    println(modifiedUrscript)
    println("=== Modified JSON after DSL change ===")
    println(modifiedJson)
}
