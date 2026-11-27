package com.example.calc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Eval05HiddenTest {

    private final PercentageCalculator calc = new PercentageCalculator();

    @Test
    void calculatesPercentOfWithHalfUpRounding() {
        assertEquals(25.00, calc.percentOf(200.0, 12.5), 1e-6);
        assertEquals(33.34, calc.percentOf(100.0, 33.335), 1e-6);
    }

    @Test
    void calculatesPercentageChangeAndRejectsZeroBase() {
        assertEquals(50.00, calc.percentageChange(100.0, 150.0), 1e-6);
        assertEquals(-33.33, calc.percentageChange(3.0, 2.0), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> calc.percentageChange(0.0, 10.0));
    }
}
