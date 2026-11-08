package com.jimmy

import kotlin.random.Random

class GridGenerator(val seed: Long) {
    fun generateRandom(grid: SudokuGrid,initial: String? = null): SudokuGrid?{

        initial?.let{
            require(it.length == 81){
                "Initial grid must be 81 characters long"
            }
            for(i in 0 until 9){
                for(j in 0 until 9){
                    val value = if(it[i*9+j] == '.') 0 else it[i*9+j].digitToIntOrNull()
                    require(value != null && value in 0..9){
                        "Initial grid must contain only digits 0-9 or ."
                    }
                    grid[i, j] = value
                }
            }
            return grid
        }

        val random = Random(seed)
        for(i in 0 until 81){
            val row = i / 9
            val col = i % 9
            if(grid[row, col] != 0) continue
            val options = grid.getOptions(row, col).shuffled(random)
            for (option in options){
                grid[row, col] = option
                if(grid.nonEmptyCount() == 81 && grid.isValid()) return grid
                generateRandom(grid)?.let { return it }
                grid[row, col] = 0
            }
            return null
        }
        return null
    }

}

