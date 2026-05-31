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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;
import org.springframework.ai.vectorstore.filter.Filter.Group;
import org.springframework.ai.vectorstore.filter.Filter.Key;
import org.springframework.ai.vectorstore.filter.Filter.Value;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SpectorFilterExpressionConverter}.
 */
class SpectorFilterExpressionConverterTest {

    private SpectorFilterExpressionConverter converter;

    @BeforeEach
    void setUp() {
        converter = new SpectorFilterExpressionConverter();
    }

    @Test
    void convertNull_returnsNull() {
        assertThat(converter.convertExpression(null)).isNull();
    }

    @Test
    void convertEq_producesCorrectString() {
        var expr = new Expression(ExpressionType.EQ, new Key("category"), new Value("science"));
        assertThat(converter.convertExpression(expr)).isEqualTo("category == \"science\"");
    }

    @Test
    void convertNe_producesCorrectString() {
        var expr = new Expression(ExpressionType.NE, new Key("status"), new Value("deleted"));
        assertThat(converter.convertExpression(expr)).isEqualTo("status != \"deleted\"");
    }

    @Test
    void convertGt_withNumber() {
        var expr = new Expression(ExpressionType.GT, new Key("price"), new Value(100));
        assertThat(converter.convertExpression(expr)).isEqualTo("price > 100");
    }

    @Test
    void convertGte_withNumber() {
        var expr = new Expression(ExpressionType.GTE, new Key("rating"), new Value(4.5));
        assertThat(converter.convertExpression(expr)).isEqualTo("rating >= 4.5");
    }

    @Test
    void convertLt_withNumber() {
        var expr = new Expression(ExpressionType.LT, new Key("age"), new Value(30));
        assertThat(converter.convertExpression(expr)).isEqualTo("age < 30");
    }

    @Test
    void convertLte_withNumber() {
        var expr = new Expression(ExpressionType.LTE, new Key("count"), new Value(10));
        assertThat(converter.convertExpression(expr)).isEqualTo("count <= 10");
    }

    @Test
    void convertAnd_combinesTwoExpressions() {
        var left = new Expression(ExpressionType.EQ, new Key("type"), new Value("book"));
        var right = new Expression(ExpressionType.LT, new Key("price"), new Value(50));
        var expr = new Expression(ExpressionType.AND, left, right);

        assertThat(converter.convertExpression(expr))
                .isEqualTo("(type == \"book\" AND price < 50)");
    }

    @Test
    void convertOr_combinesTwoExpressions() {
        var left = new Expression(ExpressionType.EQ, new Key("genre"), new Value("fiction"));
        var right = new Expression(ExpressionType.EQ, new Key("genre"), new Value("science"));
        var expr = new Expression(ExpressionType.OR, left, right);

        assertThat(converter.convertExpression(expr))
                .isEqualTo("(genre == \"fiction\" OR genre == \"science\")");
    }

    @Test
    void convertNot_negatesExpression() {
        var inner = new Expression(ExpressionType.EQ, new Key("deleted"), new Value(true));
        var expr = new Expression(ExpressionType.NOT, inner);

        assertThat(converter.convertExpression(expr))
                .isEqualTo("NOT (deleted == true)");
    }

    @Test
    void convertNestedAndOr() {
        var eq1 = new Expression(ExpressionType.EQ, new Key("a"), new Value(1));
        var eq2 = new Expression(ExpressionType.GT, new Key("b"), new Value(2));
        var eq3 = new Expression(ExpressionType.LT, new Key("c"), new Value(3));
        var and = new Expression(ExpressionType.AND, eq1, eq2);
        var or = new Expression(ExpressionType.OR, and, eq3);

        assertThat(converter.convertExpression(or))
                .isEqualTo("((a == 1 AND b > 2) OR c < 3)");
    }

    @Test
    void convertIn_producesCorrectString() {
        var expr = new Expression(ExpressionType.IN, new Key("color"), new Value(List.of("red", "blue", "green")));

        assertThat(converter.convertExpression(expr))
                .isEqualTo("color IN [\"red\", \"blue\", \"green\"]");
    }

    @Test
    void convertGroup_wrapsInParentheses() {
        var inner = new Expression(ExpressionType.EQ, new Key("x"), new Value(42));
        var group = new Group(inner);

        // Group is an Operand but not an Expression — test via nested expression
        var wrapper = new Expression(ExpressionType.AND, group,
                new Expression(ExpressionType.EQ, new Key("y"), new Value(1)));
        assertThat(converter.convertExpression(wrapper))
                .isEqualTo("((x == 42) AND y == 1)");
    }
}
