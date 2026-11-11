package com.jimmy

import com.typesafe.config.ConfigFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.hocon.Hocon
import kotlinx.serialization.hocon.decodeFromConfig
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalSerializationApi::class)
class ConfigTest {

    @Test
    fun `test hocon decoding direct`() {
        val hoconString = """
            test = true
            plot = false
            version = [1, 2, 3]
            difficulty = [10, 20]
            iterations = 50
            printProgress = true
            path = "my_benchmarks"
            plotPath = "my_plots"
        """.trimIndent()
        val typesafeConfig = ConfigFactory.parseString(hoconString).resolve()
        val config = Hocon.decodeFromConfig<Config>(typesafeConfig)
        assertTrue(config.test)
        assertFalse(config.plot)
        assertEquals(setOf(1, 2, 3), config.version)
        assertEquals(setOf(10, 20), config.difficulty)
        assertEquals(50, config.iterations)
        assertTrue(config.printProgress)
        assertEquals("my_benchmarks", config.path)
        assertEquals("my_plots", config.plotPath)
    }

    @Test
    fun `test hocon decoding with string ranges`() {
        val hoconString = """
            test = true
            version = "1,3,5-6"
            difficulty = "10-20"
        """.trimIndent()
        val typesafeConfig = ConfigFactory.parseString(hoconString).resolve()
        val config = Hocon.decodeFromConfig<Config>(typesafeConfig)
        assertTrue(config.test)
        assertEquals(setOf(1, 3, 5, 6), config.version)
        assertEquals((10..20).toSet(), config.difficulty)
    }

    @Test
    fun `test default config when no args provided`() {
        val config = extractConfig(emptyArray())
        assertFalse(config.test)
        assertFalse(config.plot)
        assertNull(config.version)
        assertNull(config.difficulty)
        assertEquals(100, config.iterations)
        assertNull(config.grid)
        assertFalse(config.printProgress)
        assertFalse(config.printTime)
        assertFalse(config.printNice)
        assertFalse(config.printDebug)
        assertEquals("benchmarks", config.path)
        assertEquals("plots", config.plotPath)
    }

    @Test
    fun `test manual cli arguments parsing`() {
        val args = arrayOf(
            "-test",
            "-v", "1,3,5-6",
            "-difficulty", "10-20,30",
            "-i", "50",
            "-p",
            "-t",
            "-b",
            "-d",
            "-path", "custom_benchmarks",
            "-plotPath", "custom_plots"
        )
        val config = extractConfig(args)
        assertTrue(config.test)
        assertFalse(config.plot)
        assertEquals(setOf(1, 3, 5, 6), config.version)
        assertEquals((10..20).toSet() + setOf(30), config.difficulty)
        assertEquals(50, config.iterations)
        assertTrue(config.printProgress)
        assertTrue(config.printTime)
        assertTrue(config.printNice)
        assertTrue(config.printDebug)
        assertEquals("custom_benchmarks", config.path)
        assertEquals("custom_plots", config.plotPath)
    }

    @Test
    fun `test config loaded from file`() {
        val tempFile = File.createTempFile("sudoku_test_config", ".properties")
        try {
            tempFile.writeText(
                """
                # Benchmark config
                test = true
                v = 2-4
                difficulty = 15-25
                i = 200
                p = true
                path = file_benchmarks
                plotPath = file_plots
                """.trimIndent()
            )

            val config = extractConfig(arrayOf("-c", tempFile.absolutePath))
            assertTrue(config.test)
            assertFalse(config.plot)
            assertEquals(setOf(2, 3, 4), config.version)
            assertEquals((15..25).toSet(), config.difficulty)
            assertEquals(200, config.iterations)
            assertTrue(config.printProgress)
            assertEquals("file_benchmarks", config.path)
            assertEquals("file_plots", config.plotPath)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `test cli args override config file values`() {
        val tempFile = File.createTempFile("sudoku_override_config", ".txt")
        try {
            tempFile.writeText(
                """
                test: true
                v: 1-6
                difficulty: 10-40
                i: 100
                p: true
                path: file_benchmarks
                """.trimIndent()
            )

            val config = extractConfig(
                arrayOf(
                    "-c", tempFile.absolutePath,
                    "-difficulty", "50",
                    "-i", "10",
                    "-path", "cli_benchmarks"
                )
            )

            assertTrue(config.test)
            assertEquals(setOf(1, 2, 3, 4, 5, 6), config.version)
            assertEquals(setOf(50), config.difficulty)
            assertEquals(10, config.iterations)
            assertTrue(config.printProgress)
            assertEquals("cli_benchmarks", config.path)
            assertEquals("plots", config.plotPath)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `test alternate config key formats and comments`() {
        val tempFile = File.createTempFile("sudoku_alt_config", ".conf")
        try {
            tempFile.writeText(
                """
                # Comment line 1
                // Comment line 2
                
                test = true
                version = "1,2"
                difficulty = "5-10"
                iterations = 25
                printProgress = true
                printTime = true
                printNice = true
                printDebug = true
                grid = "379284615428561739561793428213657984786149253945328176157436892692875341834912567"
                """.trimIndent()
            )

            val config = extractConfig(arrayOf("--config", tempFile.absolutePath))
            assertTrue(config.test)
            assertEquals(setOf(1, 2), config.version)
            assertEquals((5..10).toSet(), config.difficulty)
            assertEquals(25, config.iterations)
            assertTrue(config.printProgress)
            assertTrue(config.printTime)
            assertTrue(config.printNice)
            assertTrue(config.printDebug)
            assertEquals(
                "379284615428561739561793428213657984786149253945328176157436892692875341834912567",
                config.grid
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `test non-existent config file throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            extractConfig(arrayOf("-c", "non_existent_file_12345.properties"))
        }
    }

    @Test
    fun `test default sudoku conf file in project root`() {
        val config = extractConfig(arrayOf("-c", "sudoku.conf"))
        assertFalse(config.test)
        assertFalse(config.plot)
        assertEquals(setOf(1, 2, 3, 4, 5, 6), config.version)
        assertEquals(setOf(10, 20, 30, 40), config.difficulty)
        assertEquals(100, config.iterations)
        assertEquals("benchmarks", config.path)
        assertEquals("plots", config.plotPath)
    }


    @Test
    fun `test both test and plot flags enabled in config and args`() {
        val config = extractConfig(arrayOf("-test", "-plot"))
        assertTrue(config.test)
        assertTrue(config.plot)
    }
}
