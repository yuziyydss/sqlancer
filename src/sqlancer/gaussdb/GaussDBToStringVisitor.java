package sqlancer.gaussdb;

import sqlancer.common.visitor.ToStringVisitor;
import sqlancer.gaussdb.GaussDBSchema.GaussDBDataType;
import sqlancer.gaussdb.ast.GaussDBAggregate;
import sqlancer.gaussdb.ast.GaussDBAlias;
import sqlancer.gaussdb.ast.GaussDBAnyAllOperation;
import sqlancer.gaussdb.ast.GaussDBBetweenOperation;
import sqlancer.gaussdb.ast.GaussDBCaseOperation;
import sqlancer.gaussdb.ast.GaussDBCastOperation;
import sqlancer.gaussdb.ast.GaussDBColumnReference;
import sqlancer.gaussdb.ast.GaussDBConstant;
import sqlancer.gaussdb.ast.GaussDBExists;
import sqlancer.gaussdb.ast.GaussDBExpression;
import sqlancer.gaussdb.ast.GaussDBExpressionBag;
import sqlancer.gaussdb.ast.GaussDBExtractOperation;
import sqlancer.gaussdb.ast.GaussDBFunction;
import sqlancer.gaussdb.ast.GaussDBInOperation;
import sqlancer.gaussdb.ast.GaussDBInSubquery;
import sqlancer.gaussdb.ast.GaussDBJoin;
import sqlancer.gaussdb.ast.GaussDBSelect;
import sqlancer.gaussdb.ast.GaussDBSetOperation;
import sqlancer.gaussdb.ast.GaussDBStar;
import sqlancer.gaussdb.ast.GaussDBSubquery;
import sqlancer.gaussdb.ast.GaussDBTableReference;
import sqlancer.gaussdb.ast.GaussDBValues;
import sqlancer.gaussdb.ast.GaussDBWindowFrame;
import sqlancer.gaussdb.ast.GaussDBWindowFunction;
import sqlancer.gaussdb.ast.GaussDBWithClause;
import sqlancer.gaussdb.ast.GaussDBWithClauseGroup;

public final class GaussDBToStringVisitor extends ToStringVisitor<GaussDBExpression> implements GaussDBVisitor {

    @Override
    public void visitSpecific(GaussDBExpression expr) {
        if (expr instanceof GaussDBSelect) {
            visit((GaussDBSelect) expr);
        } else if (expr instanceof GaussDBSubquery) {
            visit((GaussDBSubquery) expr);
        } else if (expr instanceof GaussDBTableReference) {
            visit((GaussDBTableReference) expr);
        } else if (expr instanceof GaussDBAlias) {
            visit((GaussDBAlias) expr);
        } else if (expr instanceof GaussDBValues) {
            visit((GaussDBValues) expr);
        } else if (expr instanceof GaussDBWithClause) {
            visit((GaussDBWithClause) expr);
        } else if (expr instanceof GaussDBWithClauseGroup) {
            visit((GaussDBWithClauseGroup) expr);
        } else if (expr instanceof GaussDBJoin) {
            visit((GaussDBJoin) expr);
        } else if (expr instanceof GaussDBExists) {
            visit((GaussDBExists) expr);
        } else if (expr instanceof GaussDBExpressionBag) {
            visit((GaussDBExpressionBag) expr);
        } else if (expr instanceof GaussDBFunction) {
            visit((GaussDBFunction) expr);
        } else if (expr instanceof GaussDBExtractOperation) {
            visit((GaussDBExtractOperation) expr);
        } else if (expr instanceof GaussDBInSubquery) {
            visit((GaussDBInSubquery) expr);
        } else if (expr instanceof GaussDBAnyAllOperation) {
            visit((GaussDBAnyAllOperation) expr);
        } else if (expr instanceof GaussDBStar) {
            visit((GaussDBStar) expr);
        } else if (expr instanceof GaussDBColumnReference) {
            visit((GaussDBColumnReference) expr);
        } else if (expr instanceof GaussDBInOperation) {
            visit((GaussDBInOperation) expr);
        } else if (expr instanceof GaussDBBetweenOperation) {
            visit((GaussDBBetweenOperation) expr);
        } else if (expr instanceof GaussDBCaseOperation) {
            visit((GaussDBCaseOperation) expr);
        } else if (expr instanceof GaussDBCastOperation) {
            visit((GaussDBCastOperation) expr);
        } else if (expr instanceof GaussDBAggregate) {
            visit((GaussDBAggregate) expr);
        } else if (expr instanceof GaussDBConstant) {
            visit((GaussDBConstant) expr);
        } else if (expr instanceof GaussDBWindowFunction) {
            visit((GaussDBWindowFunction) expr);
        } else if (expr instanceof GaussDBSetOperation) {
            visit((GaussDBSetOperation) expr);
        } else {
            throw new AssertionError(expr);
        }
    }

    @Override
    public String get() {
        return sb.toString();
    }

    @Override
    public void visit(GaussDBConstant constant) {
        sb.append(constant.getTextRepresentation());
    }

    public void visit(GaussDBStar star) {
        sb.append("*");
    }

    public void visit(GaussDBTableReference tableReference) {
        sb.append(tableReference.getTable().getName());
    }

    public void visit(GaussDBAlias alias) {
        if (isValues(alias.getExpression())) {
            sb.append("(");
            visit(alias.getExpression());
            sb.append(")");
        } else {
            visit(alias.getExpression());
        }
        sb.append(" AS ");
        sb.append(alias.getAlias());
    }

    private boolean isValues(GaussDBExpression expression) {
        if (expression instanceof GaussDBValues) {
            return true;
        }
        return expression instanceof GaussDBExpressionBag
                && ((GaussDBExpressionBag) expression).getInnerExpression() instanceof GaussDBValues;
    }

    public void visit(GaussDBSubquery subquery) {
        sb.append("(");
        visit(subquery.getExpression());
        sb.append(")");
    }

    public void visit(GaussDBValues values) {
        sb.append("VALUES ");
        for (int i = 0; i < values.getRows().size(); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append("(");
            visit(values.getRows().get(i));
            sb.append(")");
        }
    }

    public void visit(GaussDBWithClause withClause) {
        sb.append(withClause.isRecursive() ? "WITH RECURSIVE " : "WITH ");
        sb.append(withClause.getAlias());
        sb.append(" AS (");
        visit(withClause.getExpression());
        sb.append(") ");
    }

    public void visit(GaussDBWithClauseGroup withClauseGroup) {
        sb.append("WITH ");
        for (int i = 0; i < withClauseGroup.getClauses().size(); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            GaussDBWithClause withClause = withClauseGroup.getClauses().get(i);
            sb.append(withClause.getAlias());
            sb.append(" AS (");
            visit(withClause.getExpression());
            sb.append(")");
        }
        sb.append(" ");
    }

    public void visit(GaussDBJoin join) {
        sb.append(join.getJoinType().getRepresentation());
        sb.append(" ");
        visit(join.getRight());
        if (join.getJoinType() != GaussDBJoin.JoinType.CROSS) {
            sb.append(" ON ");
            visit(join.getOnClause());
        }
    }

    public void visit(GaussDBExists exists) {
        if (exists.isNegated()) {
            sb.append("NOT ");
        }
        sb.append("EXISTS ");
        visit(exists.getSubquery());
    }

    public void visit(GaussDBExpressionBag expressionBag) {
        visit(expressionBag.getInnerExpression());
    }

    public void visit(GaussDBFunction function) {
        sb.append(function.getFunctionName());
        sb.append("(");
        visit(function.getArguments());
        sb.append(")");
    }

    public void visit(GaussDBExtractOperation operation) {
        sb.append("EXTRACT(");
        sb.append(operation.getField());
        sb.append(" FROM ");
        visit(operation.getExpression());
        sb.append(")");
    }

    public void visit(GaussDBInSubquery inSubquery) {
        sb.append("(");
        visit(inSubquery.getExpression());
        sb.append(")");
        if (inSubquery.isNegated()) {
            sb.append(" NOT");
        }
        sb.append(" IN ");
        if (inSubquery.getSubquery() instanceof GaussDBValues) {
            sb.append("(");
            visit(inSubquery.getSubquery());
            sb.append(")");
        } else {
            visit(inSubquery.getSubquery());
        }
    }

    public void visit(GaussDBAnyAllOperation operation) {
        sb.append("(");
        visit(operation.getLeft());
        sb.append(" ");
        sb.append(operation.getOperator().getRepresentation());
        sb.append(" ");
        sb.append(operation.getQuantifier());
        sb.append(" ");
        if (operation.getRight() instanceof GaussDBValues) {
            sb.append("(");
            visit(operation.getRight());
            sb.append(")");
        } else {
            visit(operation.getRight());
        }
        sb.append(")");
    }

    public void visit(GaussDBSelect select) {
        if (select.getWithClause() != null) {
            visit(select.getWithClause());
        }
        sb.append("SELECT ");
        if (select.isDistinct()) {
            sb.append("DISTINCT ");
        }
        if (select.getFetchColumns().isEmpty()) {
            sb.append("*");
        } else {
            visit(select.getFetchColumns());
        }
        if (!select.getFromList().isEmpty()) {
            sb.append(" FROM ");
            visit(select.getFromList());
        }
        for (GaussDBExpression join : select.getJoinList()) {
            sb.append(" ");
            visit((GaussDBJoin) join);
        }
        if (select.getWhereClause() != null) {
            sb.append(" WHERE ");
            visit(select.getWhereClause());
        }
        if (!select.getGroupByExpressions().isEmpty()) {
            sb.append(" GROUP BY ");
            visit(select.getGroupByExpressions());
        }
        if (select.getHavingClause() != null) {
            sb.append(" HAVING ");
            visit(select.getHavingClause());
        }
        if (!select.getOrderByClauses().isEmpty()) {
            sb.append(" ORDER BY ");
            visit(select.getOrderByClauses());
        }
        if (select.getLimitClause() != null) {
            sb.append(" LIMIT ");
            visit(select.getLimitClause());
        }
        if (select.getOffsetClause() != null) {
            sb.append(" OFFSET ");
            visit(select.getOffsetClause());
        }
    }

    public void visit(GaussDBColumnReference columnReference) {
        sb.append(columnReference.toString());
    }

    public void visit(GaussDBInOperation operation) {
        sb.append("(");
        visit(operation.getExpression());
        sb.append(")");
        if (operation.isNegated()) {
            sb.append(" NOT");
        }
        sb.append(" IN (");
        visit(operation.getList());
        sb.append(")");
    }

    public void visit(GaussDBBetweenOperation operation) {
        sb.append("(");
        visit(operation.getExpression());
        sb.append(")");
        if (operation.isNegated()) {
            sb.append(" NOT");
        }
        sb.append(" BETWEEN ");
        if (operation.isSymmetric()) {
            sb.append("SYMMETRIC ");
        }
        sb.append("(");
        visit(operation.getLeft());
        sb.append(") AND (");
        visit(operation.getRight());
        sb.append(")");
    }

    public void visit(GaussDBCaseOperation operation) {
        sb.append("CASE");
        for (int i = 0; i < operation.getConditions().size(); i++) {
            sb.append(" WHEN ");
            visit(operation.getConditions().get(i));
            sb.append(" THEN ");
            visit(operation.getResults().get(i));
        }
        if (operation.getElseExpression() != null) {
            sb.append(" ELSE ");
            visit(operation.getElseExpression());
        }
        sb.append(" END");
    }

    public void visit(GaussDBCastOperation operation) {
        sb.append("CAST(");
        visit(operation.getExpression());
        sb.append(" AS ");
        appendType(operation.getExpressionType());
        sb.append(")");
    }

    public void visit(GaussDBAggregate aggregate) {
        sb.append(aggregate.getFunction());
        sb.append("(");
        visit(aggregate.getArguments());
        sb.append(")");
    }

    public void visit(GaussDBWindowFunction windowFunction) {
        sb.append(windowFunction.getFunctionName());
        sb.append("(");
        if (windowFunction.getFilterClause() != null) {
            sb.append("CASE WHEN ");
            visit(windowFunction.getFilterClause());
            sb.append(" THEN ");
            if (windowFunction.getArguments().isEmpty()
                    || windowFunction.getArguments().get(0) instanceof GaussDBStar) {
                sb.append("1");
            } else {
                visit(windowFunction.getArguments());
            }
            sb.append(" END");
        } else {
            visit(windowFunction.getArguments());
        }
        sb.append(")");
        sb.append(" OVER (");
        if (!windowFunction.getPartitionBy().isEmpty()) {
            sb.append("PARTITION BY ");
            visit(windowFunction.getPartitionBy());
        }
        if (!windowFunction.getOrderBy().isEmpty()) {
            if (!windowFunction.getPartitionBy().isEmpty()) {
                sb.append(" ");
            }
            sb.append("ORDER BY ");
            visit(windowFunction.getOrderBy());
        }
        if (windowFunction.getFrame() != null) {
            if (!windowFunction.getPartitionBy().isEmpty() || !windowFunction.getOrderBy().isEmpty()) {
                sb.append(" ");
            }
            visit(windowFunction.getFrame());
        }
        sb.append(")");
    }

    private void visit(GaussDBWindowFrame frame) {
        sb.append(frame.getFrameType().getRepresentation());
        sb.append(" BETWEEN ");
        visit(frame.getStart());
        sb.append(" AND ");
        visit(frame.getEnd());
    }

    private void visit(GaussDBWindowFrame.Boundary boundary) {
        if (boundary.getOffset() != null) {
            visit(boundary.getOffset());
            sb.append(" ");
        }
        sb.append(boundary.getKind().getRepresentation());
    }

    public void visit(GaussDBSetOperation setOperation) {
        sb.append("(");
        visit(setOperation.getLeft());
        sb.append(") ");
        sb.append(setOperation.getOperator().getRepresentation());
        sb.append(" (");
        visit(setOperation.getRight());
        sb.append(")");
    }

    private void appendType(GaussDBDataType type) {
        switch (type) {
        case INT:
            sb.append("BIGINT");
            break;
        case BOOLEAN:
            sb.append("BOOLEAN");
            break;
        case TEXT:
            sb.append("TEXT");
            break;
        case DECIMAL:
            sb.append("NUMERIC");
            break;
        case FLOAT:
            sb.append("DOUBLE PRECISION");
            break;
        case REAL:
            sb.append("REAL");
            break;
        case DATE:
            sb.append("DATE");
            break;
        case TIMESTAMP:
            sb.append("TIMESTAMP");
            break;
        case TIMESTAMPTZ:
            sb.append("TIMESTAMPTZ");
            break;
        case TIME:
            sb.append("TIME");
            break;
        case INTERVAL:
            sb.append("INTERVAL");
            break;
        default:
            throw new AssertionError(type);
        }
    }

    public static String asString(GaussDBExpression expression) {
        GaussDBToStringVisitor visitor = new GaussDBToStringVisitor();
        visitor.visit(expression);
        return visitor.get();
    }

}
