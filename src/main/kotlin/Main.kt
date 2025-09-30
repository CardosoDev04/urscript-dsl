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

    val json = Json { ignoreUnknownKeys = true }
    val scenario = json.decodeFromString<ScenarioFile>(inputJson)

    val urscript = UrscriptGenerator().generate(scenario)
    println("=== Generated URScript ===")
    println(urscript)
}
