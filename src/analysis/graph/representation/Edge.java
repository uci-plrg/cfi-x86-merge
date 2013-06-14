package analysis.graph.representation;

public class Edge {
	private Node toNode;
	private EdgeType edgeType;
	private int ordinal;
	
	// Add this filed for debugging reason, cause it provides
	// more information when debugging
	private Node fromNode;
	
	public Node getFromNode() {
		return fromNode;
	}
	
	public String toString() {
		return fromNode.getIndex() + "(" + fromNode.getHashHex() + ")" + "(" + edgeType + ")--" + ordinal + "-->" + toNode.getIndex() + "(" + toNode.getHashHex() + ")";
	}
	
	public Node getToNode() {
		return toNode;
	}
	
	public EdgeType getEdgeType() {
		return edgeType;
	}
	
	public int getOrdinal() {
		return ordinal;
	}

	public Edge(Node fromNode, Node toNode, EdgeType edgeType, int ordinal) {
		this.fromNode = fromNode;
		this.toNode = toNode;
		this.edgeType = edgeType;
		this.ordinal = ordinal;
	}

	public Edge(Node fromNode, Node toNode, int flag) {
		this.fromNode = fromNode;
		this.toNode = toNode;
		this.ordinal = flag % 256;
		edgeType = EdgeType.values()[flag / 256];
	}

	public boolean equals(Object o) {
		if (o == null)
			return false;
		if (o.getClass() != Edge.class)
			return false;
		Edge e = (Edge) o;
		if (e.fromNode.equals(fromNode) 
				&& e.toNode.equals(toNode)
				&& e.edgeType == edgeType
				&& e.ordinal == ordinal)
			return true;
		return false;

	}

	public int hashCode() {
		return fromNode.hashCode() << 5 ^ toNode.hashCode();
	}
}
