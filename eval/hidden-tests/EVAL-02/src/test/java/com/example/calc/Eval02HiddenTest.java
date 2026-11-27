package com.example.calc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Eval02HiddenTest {

    private final Calculator calc = new Calculator();

    @Test
    void multipliesCorrectly() {
        assertEquals(15, calc.multiply(3, 5));
        assertEquals(0, calc.multiply(7, 0));
        assertEquals(-12, calc.multiply(-3, 4));
    }

    @Test
    void computesPowerAndRejectsNegativeExponent() {
        assertEquals(1, calc.power(5, 0));
        assertEquals(8, calc.power(2, 3));
        assertEquals(-27, calc.power(-3, 3));
        assertThrows(IllegalArgumentException.class, () -> calc.power(2, -1));
    }
}
