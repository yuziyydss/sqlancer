package sqlancer.gaussdb.ast;

public final class GaussDBWindowFrame {

    public enum FrameType {
        ROWS("ROWS"), RANGE("RANGE");

        private final String representation;

        FrameType(String representation) {
            this.representation = representation;
        }

        public String getRepresentation() {
            return representation;
        }
    }

    public enum BoundaryKind {
        UNBOUNDED_PRECEDING("UNBOUNDED PRECEDING"), PRECEDING("PRECEDING"), CURRENT_ROW("CURRENT ROW"),
        FOLLOWING("FOLLOWING"), UNBOUNDED_FOLLOWING("UNBOUNDED FOLLOWING");

        private final String representation;

        BoundaryKind(String representation) {
            this.representation = representation;
        }

        public String getRepresentation() {
            return representation;
        }
    }

    public static final class Boundary {

        private final BoundaryKind kind;
        private final GaussDBExpression offset;

        private Boundary(BoundaryKind kind, GaussDBExpression offset) {
            this.kind = kind;
            this.offset = offset;
        }

        public static Boundary unboundedPreceding() {
            return new Boundary(BoundaryKind.UNBOUNDED_PRECEDING, null);
        }

        public static Boundary preceding(GaussDBExpression offset) {
            return new Boundary(BoundaryKind.PRECEDING, offset);
        }

        public static Boundary currentRow() {
            return new Boundary(BoundaryKind.CURRENT_ROW, null);
        }

        public static Boundary following(GaussDBExpression offset) {
            return new Boundary(BoundaryKind.FOLLOWING, offset);
        }

        public static Boundary unboundedFollowing() {
            return new Boundary(BoundaryKind.UNBOUNDED_FOLLOWING, null);
        }

        public BoundaryKind getKind() {
            return kind;
        }

        public GaussDBExpression getOffset() {
            return offset;
        }
    }

    private final FrameType frameType;
    private final Boundary start;
    private final Boundary end;

    private GaussDBWindowFrame(FrameType frameType, Boundary start, Boundary end) {
        this.frameType = frameType;
        this.start = start;
        this.end = end;
    }

    public static GaussDBWindowFrame between(FrameType frameType, Boundary start, Boundary end) {
        return new GaussDBWindowFrame(frameType, start, end);
    }

    public FrameType getFrameType() {
        return frameType;
    }

    public Boundary getStart() {
        return start;
    }

    public Boundary getEnd() {
        return end;
    }
}
