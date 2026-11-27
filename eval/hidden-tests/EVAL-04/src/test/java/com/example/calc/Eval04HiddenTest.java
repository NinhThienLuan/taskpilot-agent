package com.example.calc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Eval04HiddenTest {

    private final Calculator calc = new Calculator();

    @Test
    void safeAddHandlesNormalAndOverflow() {
        assertEquals(7, calc.safeAdd(3, 4));
        assertThrows(ArithmeticException.class, () -> calc.safeAdd(Integer.MAX_VALUE, 1));
        assertThrows(ArithmeticException.class, () -> calc.safeAdd(Integer.MIN_VALUE, -1));
    }
}
