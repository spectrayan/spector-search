package org.springframework.ai.vectorstore.spector;

import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.Filter.Group;
import org.springframework.ai.vectorstore.filter.Filter.Key;
import org.springframework.ai.vectorstore.filter.Filter.Value;
import org.springframework.ai.vectorstore.filter.FilterExpressionConverter;

import java.util.List;
import com.spectrayan.spector.commons.error.SpectorValidationException;
import com.spectrayan.spector.commons.error.ErrorCode;

/**
 * Converts Spring AI {@link Filter.Expression} into Spector Search filter query strings.
 *
 * <p>Supports:
 * <ul>
 *   <li>Comparison operators: EQ, NE, GT, GTE, LT, LTE</li>
 *   <li>Logical operators: AND, OR, NOT</li>
 *   <li>Collection operators: IN, NIN</li>
 * </ul>
 */
public class SpectorFilterExpressionConverter implements FilterExpressionConverter {

    @Override
    public String convertExpression(Expression expression) {
        if (expression == null) {
            return null;
        }
        return convert(expression);
    }

    private String convert(Filter.Operand operand) {
        return switch (operand) {
            case Group group -> "(" + convert(group.content()) + ")";
            case Expression expr -> convertExpr(expr);
            default -> operand.toString();
        };
    }

    private String convertExpr(Expression expression) {
        ExpressionType type = expression.type();
        Filter.Operand left = expression.left();
        Filter.Operand right = expression.right();

        return switch (type) {
            case AND -> "(" + convert(left) + " AND " + convert(right) + ")";
            case OR -> "(" + convert(left) + " OR " + convert(right) + ")";
            case NOT -> "NOT (" + convert(left) + ")";
            case IN -> convertIn((Key) left, (Value) right);
            case NIN -> convertNin((Key) left, (Value) right);
            case EQ, NE, GT, GTE, LT, LTE -> convertCompare(type, (Key) left, (Value) right);
            case ISNULL -> ((Key) left).key() + " IS NULL";
            case ISNOTNULL -> ((Key) left).key() + " IS NOT NULL";
        };
    }

    private String convertIn(Key key, Value value) {
        String values = formatValueList(value);
        return key.key() + " IN [" + values + "]";
    }

    private String convertNin(Key key, Value value) {
        String values = formatValueList(value);
        return key.key() + " NIN [" + values + "]";
    }

    @SuppressWarnings("unchecked")
    private String formatValueList(Value value) {
        if (value.value() instanceof List<?> list) {
            return list.stream()
                    .map(this::formatValue)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
        }
        return formatValue(value.value());
    }

    private String convertCompare(ExpressionType type, Key key, Value value) {
        String operator = mapOperator(type);
        String formattedValue = formatValue(value.value());
        return key.key() + " " + operator + " " + formattedValue;
    }

    private String mapOperator(ExpressionType type) {
        return switch (type) {
            case EQ -> "==";
            case NE -> "!=";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            default -> throw new SpectorValidationException(ErrorCode.ARGUMENT_INVALID, "comparisonType", type);
        };
    }

    private String formatValue(Object value) {
        if (value instanceof String s) {
            return "\"" + s.replace("\"", "\\\"") + "\"";
        } else if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + value + "\"";
    }
}
