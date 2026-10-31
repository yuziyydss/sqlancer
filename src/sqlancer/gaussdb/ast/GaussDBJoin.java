package sqlancer.gaussdb.ast;

import sqlancer.common.ast.newast.Join;
import sqlancer.gaussdb.GaussDBSchema.GaussDBColumn;
import sqlancer.gaussdb.GaussDBSchema.GaussDBTable;

public final class GaussDBJoin
        implements Join<GaussDBExpression, GaussDBTable, GaussDBColumn>, GaussDBExpression {

    public enum JoinType {
        INNER("JOIN"), LEFT("LEFT JOIN"), RIGHT("RIGHT JOIN"), FULL("FULL JOIN"), CROSS("CROSS JOIN");

        private final String representation;

        JoinType(String representation) {
            this.representation = representation;
        }

        public String getRepresentation() {
            return representation;
        }

        public static JoinType getRandom() {
            return sqlancer.Randomly.fromOptions(values());
        }
    }

    private final GaussDBExpression left;
    private final GaussDBExpression right;
    private final JoinType joinType;
    private GaussDBExpression onClause;

    public GaussDBJoin(GaussDBExpression left, GaussDBExpression right, JoinType joinType,
            GaussDBExpression onClause) {
        this.left = left;
        this.right = right;
        this.joinType = joinType;
        this.onClause = onClause;
    }

    public GaussDBExpression getLeft() {
        return left;
    }

    public GaussDBExpression getRight() {
        return right;
    }

    public JoinType getJoinType() {
        return joinType;
    }

    public GaussDBExpression getOnClause() {
        return onClause;
    }

    @Override
    public void setOnClause(GaussDBExpression onClause) {
        this.onClause = onClause;
    }

}
