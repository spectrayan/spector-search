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

import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;
import org.springframework.ai.vectorstore.filter.Filter.Group;
import org.springframework.ai.vectorstore.filter.Filter.Key;
import org.springframework.ai.vectorstore.filter.Filter.Value;

import java.util.List;
import java.util.Map;

/**
 * Evaluates Spring AI {@link Filter.Expression} against a document's metadata in memory.
 *
 * <p>Supports comparison operators (EQ, NE, GT, GTE, LT, LTE) and
 * logical operators (AND, OR, NOT).
 */
public final class SpectorFilterEvaluator {

    private SpectorFilterEvaluator() {}

    /**
     * Evaluates whether the given metadata satisfies the filter expression.
     *
     * @param expression the filter expression to evaluate
     * @param metadata   the document metadata
     * @return true if the metadata satisfies the expression
     */
    public static boolean evaluate(Expression expression, Map<String, Object> metadata) {
        if (expression == null) {
            return true;
        }
        return evaluateNode(expression, metadata);
    }

    private static boolean evaluateNode(Filter.Operand operand, Map<String, Object> metadata) {
        return switch (operand) {
            case Group group -> evaluateNode(group.content(), metadata);
            case Expression expr -> evaluateExpression(expr, metadata);
            default -> false;
        };
    }

    private static boolean evaluateExpression(Expression expression, Map<String, Object> metadata) {
        ExpressionType type = expression.type();
        Filter.Operand left = expression.left();
        Filter.Operand right = expression.right();

        return switch (type) {
            case AND -> evaluateNode(left, metadata) && evaluateNode(right, metadata);
            case OR -> evaluateNode(left, metadata) || evaluateNode(right, metadata);
            case NOT -> !evaluateNode(left, metadata);
            case IN -> evaluateIn((Key) left, (Value) right, metadata);
            case NIN -> evaluateNin((Key) left, (Value) right, metadata);
            case EQ, NE, GT, GTE, LT, LTE -> evaluateCompare(type, (Key) left, (Value) right, metadata);
            case ISNULL -> metadata.get(((Key) left).key()) == null;
            case ISNOTNULL -> metadata.get(((Key) left).key()) != null;
        };
    }

    private static boolean evaluateCompare(ExpressionType type, Key key, Value value, Map<String, Object> metadata) {
        Object metaValue = metadata.get(key.key());
        Object filterValue = value.value();

        if (metaValue == null) {
            return type == ExpressionType.NE;
        }

        return switch (type) {
            case EQ -> equals(metaValue, filterValue);
            case NE -> !equals(metaValue, filterValue);
            case GT -> compareValues(metaValue, filterValue) > 0;
            case GTE -> compareValues(metaValue, filterValue) >= 0;
            case LT -> compareValues(metaValue, filterValue) < 0;
            case LTE -> compareValues(metaValue, filterValue) <= 0;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private static boolean evaluateIn(Key key, Value value, Map<String, Object> metadata) {
        Object metaValue = metadata.get(key.key());
        if (metaValue == null) {
            return false;
        }
        if (value.value() instanceof List<?> values) {
            return values.stream().anyMatch(v -> equals(metaValue, v));
        }
        return equals(metaValue, value.value());
    }

    @SuppressWarnings("unchecked")
    private static boolean evaluateNin(Key key, Value value, Map<String, Object> metadata) {
        Object metaValue = metadata.get(key.key());
        if (metaValue == null) {
            return true;
        }
        if (value.value() instanceof List<?> values) {
            return values.stream().noneMatch(v -> equals(metaValue, v));
        }
        return !equals(metaValue, value.value());
    }

    private static boolean equals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;

        // Handle numeric comparison across types
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }

        return a.toString().equals(b.toString());
    }

    @SuppressWarnings("unchecked")
    private static int compareValues(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof Comparable ca && b != null) {
            try {
                return ca.compareTo(b);
            } catch (ClassCastException e) {
                return a.toString().compareTo(b.toString());
            }
        }
        return a.toString().compareTo(b != null ? b.toString() : "");
    }
}
