package com.example.calc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Eval03HiddenTest {

    private final Statistics stats = new Statistics();

    @Test
    void computesMinMaxAverageForNormalAndNegativeArrays() {
        int[] values = {-5, 0, 10, 15};
        assertEquals(-5, stats.min(values));
        assertEquals(15, stats.max(values));
        assertEquals(5.0, stats.average(values), 1e-6);
    }

    @Test
    void rejectsNullAndEmptyArrays() {
        assertThrows(IllegalArgumentException.class, () -> stats.min(null));
        assertThrows(IllegalArgumentException.class, () -> stats.max(new int[]{}));
        assertThrows(IllegalArgumentException.class, () -> stats.average(new int[]{}));
    }
}
