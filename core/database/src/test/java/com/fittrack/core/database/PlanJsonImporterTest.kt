package com.fittrack.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlanJsonImporterTest {
    private val samplePlanJson = """
        {
          "schemaVersion": 1,
          "name": "Plan startowy",
          "dayLabel": "Full body A",
          "scheduledDayOfWeek": 2,
          "scheduledMinuteOfDay": 1080,
          "exercises": [
            {
              "name": "Przysiad ze sztangą",
              "movementPattern": "SQUAT", "muscleRole": "QUADS", "equipment": "BARBELL",
              "warmupSets": 2, "workingSets": 3, "repMin": 6, "repMax": 8,
              "targetRir": 2, "startingWeightKg": 40.0, "restSeconds": 180, "weightIncrementKg": 2.5
            }
          ]
        }
    """.trimIndent()

    @Test fun `parses the versioned plan schema`() {
        val parsed = PlanJsonImporter.parsePlan(samplePlanJson)
        assertEquals("Plan startowy", parsed.name)
        assertEquals(1, parsed.exercises.size)
        assertEquals(40.0, parsed.exercises.first().startingWeightKg, 0.0)
        assertEquals(2, parsed.exercises.first().targetRir)
    }

    @Test fun `defaults schemaVersion to 1 when absent for backward compatibility with the original fixture`() {
        val withoutVersion = samplePlanJson.replace("\"schemaVersion\": 1,", "")
        val parsed = PlanJsonImporter.parsePlan(withoutVersion)
        assertEquals(1, parsed.schemaVersion)
    }

    @Test fun `rejects an unsupported future schema version`() {
        val futureVersion = samplePlanJson.replace("\"schemaVersion\": 1,", "\"schemaVersion\": 99,")
        assertThrows(IllegalArgumentException::class.java) { PlanJsonImporter.parsePlan(futureVersion) }
    }

    @Test fun `omitting targetRir yields a null RIR target, distinct from zero`() {
        val noRir = samplePlanJson.replace("\"targetRir\": 2,", "")
        val parsed = PlanJsonImporter.parsePlan(noRir)
        assertNull(parsed.exercises.first().targetRir)
    }

    @Test fun `parses the exercise library`() {
        val libraryJson = """[{"name":"Goblet squat","movementPattern":"SQUAT","muscleRole":"QUADS","equipment":"DUMBBELL"}]"""
        val parsed = PlanJsonImporter.parseLibrary(libraryJson)
        assertEquals(1, parsed.size)
        assertEquals("Goblet squat", parsed.first().name)
    }
}
