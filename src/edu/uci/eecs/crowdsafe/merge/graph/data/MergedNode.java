package edu.uci.eecs.crowdsafe.merge.graph.data;

import edu.uci.eecs.crowdsafe.common.data.graph.Edge;
import edu.uci.eecs.crowdsafe.common.data.graph.EdgeSet;
import edu.uci.eecs.crowdsafe.common.data.graph.EdgeType;
import edu.uci.eecs.crowdsafe.common.data.graph.MetaNodeType;
import edu.uci.eecs.crowdsafe.common.data.graph.Node;

public class MergedNode extends Node<MergedNode> {
	public static class Key implements Node.Key {
		public final long hash;
		public final int instanceId;

		public Key(long hash, int instanceId) {
			this.hash = hash;
			this.instanceId = instanceId;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + (int) (hash ^ (hash >>> 32));
			result = prime * result + instanceId;
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			Key other = (Key) obj;
			if (hash != other.hash)
				return false;
			if (instanceId != other.instanceId)
				return false;
			return true;
		}
	}

	private final Key key;

	private final MetaNodeType type;

	private final EdgeSet<MergedNode> edges = new EdgeSet<MergedNode>();

	private Edge<MergedNode> continuationEdge;

	MergedNode(long hash, int instanceId, MetaNodeType type) {
		this.key = new Key(hash, instanceId);
		this.type = type;
	}

	@Override
	public MergedNode.Key getKey() {
		return key;
	}

	public MetaNodeType getType() {
		return type;
	}

	@Override
	public long getHash() {
		return key.hash;
	}

	public void addIncomingEdge(Edge<MergedNode> e) {
		edges.addEdge(EdgeSet.Direction.INCOMING, e);
	}

	public void addOutgoingEdge(Edge<MergedNode> e) {
		if (e.getEdgeType() == EdgeType.CALL_CONTINUATION) {
			if (continuationEdge != null)
				throw new IllegalStateException(
						"Cannot add multiple call continuation edges!");
			continuationEdge = e;
		} else {
			edges.addEdge(EdgeSet.Direction.OUTGOING, e);
		}
	}
}
