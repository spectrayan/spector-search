/*
 * Copyright 2026 Spectrayan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.vectorstore.spector;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;
import org.springframework.ai.vectorstore.filter.Filter.Key;
import org.springframework.ai.vectorstore.filter.Filter.Value;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SpectorFilterEvaluator}.
 */
class SpectorFilterEvaluatorTest {

    @Test
    void evaluateNull_returnsTrue() {
        assertThat(SpectorFilterEvaluator.evaluate(null, Map.of("a", 1))).isTrue();
    }

    @Test
    void evaluateEq_matchingValue_returnsTrue() {
        var expr = new Expression(ExpressionType.EQ, new Key("category"), new Value("science"));
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("category", "science"))).isTrue();
    }

    @Test
    void evaluateEq_nonMatchingValue_returnsFalse() {
        var expr = new Expression(ExpressionType.EQ, new Key("category"), new Value("science"));
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("category", "fiction"))).isFalse();
    }

    @Test
    void evaluateNe_differentValue_returnsTrue() {
        var expr = new Expression(ExpressionType.NE, new Key("status"), new Value("deleted"));
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("status", "active"))).isTrue();
    }

    @Test
    void evaluateGt_greaterValue_returnsTrue() {
        var expr = new Expression(ExpressionType.GT, new Key("price"), new Value(50));
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("price", 100))).isTrue();
    }

    @Test
    void evaluateGt_equalValue_returnsFalse() {
        var expr = new Expression(ExpressionType.GT, new Key("price"), new Value(50));
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("price", 50))).isFalse();
    }

    @Test
    void evaluateGte_equalValue_returnsTrue() {
        var expr = new Expression(ExpressionType.GTE, new Key("count"), new Value(10));
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("count", 10))).isTrue();
    }

    @Test
    void evaluateLt_lesserValue_returnsTrue() {
        var expr = new Expression(ExpressionType.LT, new Key("age"), new Value(30));
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("age", 25))).isTrue();
    }

    @Test
    void evaluateLte_equalValue_returnsTrue() {
        var expr = new Expression(ExpressionType.LTE, new Key("size"), new Value(5));
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("size", 5))).isTrue();
    }

    @Test
    void evaluateAnd_bothTrue_returnsTrue() {
        var left = new Expression(ExpressionType.EQ, new Key("a"), new Value(1));
        var right = new Expression(ExpressionType.EQ, new Key("b"), new Value(2));
        var expr = new Expression(ExpressionType.AND, left, right);
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("a", 1, "b", 2))).isTrue();
    }

    @Test
    void evaluateAnd_oneFalse_returnsFalse() {
        var left = new Expression(ExpressionType.EQ, new Key("a"), new Value(1));
        var right = new Expression(ExpressionType.EQ, new Key("b"), new Value(2));
        var expr = new Expression(ExpressionType.AND, left, right);
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("a", 1, "b", 99))).isFalse();
    }

    @Test
    void evaluateOr_oneTrue_returnsTrue() {
        var left = new Expression(ExpressionType.EQ, new Key("x"), new Value(1));
        var right = new Expression(ExpressionType.EQ, new Key("y"), new Value(2));
        var expr = new Expression(ExpressionType.OR, left, right);
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("x", 1, "y", 99))).isTrue();
    }

    @Test
    void evaluateNot_negatesResult() {
        var inner = new Expression(ExpressionType.EQ, new Key("deleted"), new Value(true));
        var expr = new Expression(ExpressionType.NOT, inner);
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("deleted", false))).isTrue();
    }

    @Test
    void evaluateIn_valuePresent_returnsTrue() {
        var expr = new Expression(ExpressionType.IN, new Key("color"), new Value(List.of("red", "blue", "green")));
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("color", "blue"))).isTrue();
    }

    @Test
    void evaluateIn_valueAbsent_returnsFalse() {
        var expr = new Expression(ExpressionType.IN, new Key("color"), new Value(List.of("red", "blue", "green")));
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("color", "yellow"))).isFalse();
    }

    @Test
    void evaluateNin_valueAbsent_returnsTrue() {
        var expr = new Expression(ExpressionType.NIN, new Key("status"), new Value(List.of("deleted", "archived")));
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("status", "active"))).isTrue();
    }

    @Test
    void evaluateMissingKey_eq_returnsFalse() {
        var expr = new Expression(ExpressionType.EQ, new Key("missing"), new Value("value"));
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("other", "data"))).isFalse();
    }

    @Test
    void evaluateMissingKey_ne_returnsTrue() {
        var expr = new Expression(ExpressionType.NE, new Key("missing"), new Value("value"));
        assertThat(SpectorFilterEvaluator.evaluate(expr, Map.of("other", "data"))).isTrue();
    }
}
