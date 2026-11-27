package com.example.calc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Eval01HiddenTest {

    private final Calculator calc = new Calculator();

    @Test
    void dividesPositiveAndNegativeNumbers() {
        assertEquals(4, calc.divide(20, 5));
        assertEquals(-3, calc.divide(-9, 3));
        assertEquals(0, calc.divide(0, 7));
    }

    @Test
    void throwsOnDivideByZero() {
        assertThrows(IllegalArgumentException.class, () -> calc.divide(10, 0));
    }
}
