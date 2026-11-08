package com.jimmy

import kotlin.random.Random

sealed interface SudokuCellStorage: Iterable<Number> {
    operator fun get(row: Int, col: Int): Int
    operator fun set(row: Int, col: Int, value: Int)
    fun reset()
    fun copy(): SudokuCellStorage

    class BasicArray(private val cells: IntArray = IntArray(81) { 0 }) : SudokuCellStorage {
        override fun get(row: Int, col: Int): Int = cells[row * 9 + col]

        override fun set(row: Int, col: Int, value: Int) {
            cells[row * 9 + col] = value
        }

        override fun reset() { repeat(81) { cells[it] = 0 } }

        override fun copy(): BasicArray = BasicArray(cells.copyOf())

        override fun equals(other: Any?): Boolean = other is BasicArray && cells.contentEquals(other.cells)
        override fun hashCode(): Int {
            return cells.contentHashCode()
        }

        override fun iterator(): Iterator<Number> = cells.iterator()
    }

    open class PackedArray(private val cells: Array<ULong> = Array(9) { 0UL }) : SudokuCellStorage {

        companion object {
            const val STEPS = 5
            const val VALUE_MASK = 15UL
            const val USED_MASK = 16UL
        }

        fun get(row: Int, col: Int, steps: Int, mask: ULong): Int = ((cells[row] shr (steps * col)) and mask).toInt()

        override fun get(row: Int, col: Int): Int = get(row, col, STEPS, VALUE_MASK)

        fun set(row: Int, col: Int, value: Int, steps: Int, mask: ULong) {
            val shift = steps * col
            val shiftedMask = mask shl shift
            cells[row] = (cells[row] and shiftedMask.inv()) or ((value.toULong() and mask) shl shift)
        }

        override fun set(row: Int, col: Int, value: Int) = set(row, col, value, STEPS, VALUE_MASK)

        fun markUsed(row: Int, col: Int) {
            cells[row] = cells[row] or (USED_MASK shl (STEPS * col))
        }

        fun isUsed(row: Int, col: Int): Boolean = get(row, col, STEPS, USED_MASK) != 0

        override fun reset() { repeat(9) { cells[it] = 0UL } }

        override fun copy(): PackedArray = PackedArray(cells.copyOf())

        override fun equals(other: Any?): Boolean = other is PackedArray && cells.contentEquals(other.cells)
        override fun hashCode(): Int {
            return cells.contentHashCode()
        }

        override fun iterator(): Iterator<Number> = (0..80).map { index ->
            get(index / 9, index % 9)
        }.iterator()
    }
}

abstract class SudokuGrid {
    open val cells: SudokuCellStorage = SudokuCellStorage.BasicArray()

    fun setCells(cells: SudokuCellStorage) {
        for (i in 0..8) for (j in 0..8) this.cells[i, j] = cells[i, j]
    }

    abstract val v: Int
    private var seed = Random.nextLong()
    protected var random = Random(seed)

    val cache = mutableMapOf<SudokuCellStorage, Int>()

    abstract fun copy(): SudokuGrid

    open fun setSeed(seed: Long) {
        this.seed = seed
        random = Random(seed)
    }

    open fun cacheResult(i: Int){}

    operator fun get(row: Int, col: Int): Int = cells[row, col]

    operator fun set(row: Int, col: Int, value: Int) {
        cells[row, col] = value
    }

    open fun solveCount(): Int {
        cache[cells]?.let { return it }
        return countSolutions(0).also { cacheResult(it) }
    }

    private fun countSolutions(cellIndex: Int): Int {
        if (cellIndex == 81) return if (isValid()) 1 else 0

        val row = cellIndex / 9
        val col = cellIndex % 9

        if (get(row, col) != 0) return countSolutions(cellIndex + 1)

        var totalCount = 0
        val options = getOptions(row, col)

        for (option in options) {
            set(row, col, option)
            totalCount += countSolutions(cellIndex + 1)
            if(totalLimitCheck(totalCount)) break
        }
        set(row, col, 0)

        return totalCount
    }

    open fun totalLimitCheck(total: Int): Boolean = false

    fun getRow(row: Int): IntArray = IntArray(9) { col -> get(row, col) }

    fun getColumn(col: Int): IntArray = IntArray(9) { row -> get(row, col) }

    fun getBlock(block: Int): List<Int> {
        val blockRow = block / 3 * 3
        val blockCol = block % 3 * 3
        return buildList {
            for(row in blockRow..blockRow + 2) {
                for(col in blockCol..blockCol + 2) {
                    add(get(row, col))
                }
            }
        }
    }

    fun nonEmptyCount(): Int {
        var count = 0
        for(i in 0..80) if(get(i / 9, i % 9) != 0) count++
        return count
    }

    open fun markUsed(row: Int, col: Int) {}

    open fun isUsed(row: Int, col: Int): Boolean = false

    open fun getRandomFilled(attempts: Int): Triple<Int, Int, Int>? {
        var i = attempts
        val indices = IntArray(81){it}
        while (i > 0) {
            val index = indices.random(random)
            val row = index / 9
            val col = index % 9
            get(row, col).takeIf { it != 0 && !isUsed(row, col) }?.let {
                return Triple(row, col, it)
            }
            i--
        }
        return null
    }

    open fun playable(n: Int): Int {
        var removed = 0
        var attempts = nonEmptyCount()
        while (removed < n && attempts > 0) {
            val (row, col, value) = getRandomFilled(attempts) ?: break
            markUsed(row, col)
            this[row, col] = 0
            if (solveCount() != 1) {
                this[row, col] = value
                attempts--
            } else {
                removed++
                attempts = nonEmptyCount()
            }
        }
        return removed
    }

    open fun reset(){
        cells.reset()
        random = Random(seed)
        cache.clear()
    }

    fun getOptions(row: Int, col: Int): List<Int> {
        val options = getRow(row) + getColumn(col) + getBlock(row / 3 * 3 + col / 3)
        return IntArray(9){it+1}.filter { it !in options }
    }

    fun isValid(): Boolean {
        val rows = IntArray(9)
        val columns = IntArray(9)
        val blocks = IntArray(9)

        for (i in 0..8) {
            for (j in 0..8) {
                val value = get(i, j) - 1

                if (value !in 0..8) return false

                val bit = 1 shl value
                val block = (i / 3) * 3 + j / 3

                if (rows[i] and bit != 0) return false
                if (columns[j] and bit != 0) return false
                if (blocks[block] and bit != 0) return false

                rows[i] = rows[i] or bit
                columns[j] = columns[j] or bit
                blocks[block] = blocks[block] or bit
            }
        }

        return true
    }

    fun print() {
        println((0..8).joinToString("\n") { row ->
            (0..8).joinToString(" ") { col ->
                get(row, col).toString()
            }
        })
    }

    fun debugString(): String {
        return (0 until 81).map { get(it / 9, it % 9) }.joinToString("") {
            if (it == 0) "." else "$it"
        }
    }

    fun printNiceGrid() {
        val esc = "\u001B"
        val reset = "$esc[0m"

        val colorFrame = "$esc[1;34m"
        val colorDivider = "$esc[0;36m"
        val colorNumber = "$esc[1;37m"
        val colorDot = "$esc[0;90m"
        val topBorder = "$colorFrame╔═══╤═══╤═══╦═══╤═══╤═══╦═══╤═══╤═══╗$reset"
        val bottomBorder = "$colorFrame╚═══╧═══╧═══╩═══╧═══╧═══╩═══╧═══╧═══╝$reset"
        val horizontalBorder =
            "$colorFrame╟$colorDivider───┼───┼───$colorFrame╫$colorDivider───┼───┼───$colorFrame╫$colorDivider───┼───┼───$colorFrame╢$reset"
        val majorHorizontalBorder = "$colorFrame╠═══╪═══╪═══╬═══╪═══╪═══╬═══╪═══╪═══╣$reset"

        println(topBorder)
        for (row in 0..8) {
            if (row > 0) if (row % 3 == 0) println(majorHorizontalBorder) else println(horizontalBorder)

            val rowString = (0..8).joinToString("", "$colorFrame║$reset", "$colorFrame║$reset") { col ->
                val value = get(row, col)
                val cellStr = if (value == 0) "$colorDot $reset" else "$colorNumber$value$reset"

                when {
                    col == 8 -> " $cellStr "
                    (col + 1) % 3 == 0 -> " $cellStr $colorFrame║$reset"
                    else -> " $cellStr $colorDivider│$reset"
                }
            }
            println(rowString)
        }
        println(bottomBorder)
    }
}

open class V1SudokuGridBase: SudokuGrid() {
    override val v: Int = 1
    override fun copy(): SudokuGrid = V1SudokuGridBase().also { grid ->
        for (row in 0..8) {
            for (col in 0..8) {
                grid[row, col] = this[row, col]
            }
        }
    }
}

open class V2SudokuGridPacked : SudokuGrid() {
    override val v: Int = 2
    override val cells: SudokuCellStorage.PackedArray = SudokuCellStorage.PackedArray()
    override fun copy(): SudokuGrid = V2SudokuGridPacked().also { it.setCells(cells.copy()) }
}

open class V3SudokuGridFilteredSelection: V2SudokuGridPacked() {
    override val v: Int = 3
    override fun markUsed(row: Int, col: Int) = cells.markUsed(row, col)
    override fun isUsed(row: Int, col: Int) = cells.isUsed(row, col)
    override fun copy(): SudokuGrid = V3SudokuGridFilteredSelection().also { it.setCells(cells.copy()) }
}

open class V4SudokuGridCaching : V3SudokuGridFilteredSelection() {
    override val v: Int = 4
    override fun cacheResult(i: Int) { cache[cells.copy()] = i }
    override fun copy(): SudokuGrid = V4SudokuGridCaching().also { it.setCells(cells.copy()) }
}

open class V5SudokuGridLimitedSolutions : V4SudokuGridCaching() {
    override val v: Int = 5

    override fun totalLimitCheck(total: Int) = total > 1
    override fun copy(): SudokuGrid = V5SudokuGridLimitedSolutions().also { it.setCells(cells.copy()) }
}

open class V6SudokuGridBetterRandomSelection : V5SudokuGridLimitedSolutions() {
    override val v: Int = 6

    val randomOptions = (0..80).shuffled(random).toMutableList()

    override fun markUsed(row: Int, col: Int) {}

    override fun setSeed(seed: Long) {
        super.setSeed(seed)
        randomOptions.clear()
        randomOptions.addAll((0..80).shuffled(random))
    }

    override fun reset(){
        super.reset()
        randomOptions.clear()
        randomOptions.addAll((0..80).shuffled(random))
    }

    override fun getRandomFilled(attempts: Int): Triple<Int, Int, Int>? {
        while (randomOptions.isNotEmpty()) {
            val index = randomOptions.removeFirst()
            val row = index / 9
            val col = index % 9

            val value = get(row, col)
            if (value != 0) return Triple(row, col, value)
        }
        return null
    }

    override fun copy(): SudokuGrid = V6SudokuGridBetterRandomSelection().also { it.setCells(cells.copy()) }
}