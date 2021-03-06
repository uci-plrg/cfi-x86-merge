package edu.uci.plrg.cfi.x86.merge.graph.hash;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import edu.uci.plrg.cfi.common.exception.WrongEdgeTypeException;
import edu.uci.plrg.cfi.common.log.Log;
import edu.uci.plrg.cfi.x86.graph.data.graph.Edge;
import edu.uci.plrg.cfi.x86.graph.data.graph.ModuleGraph;
import edu.uci.plrg.cfi.x86.graph.data.graph.Node;
import edu.uci.plrg.cfi.x86.graph.data.graph.OrdinalEdgeList;
import edu.uci.plrg.cfi.x86.graph.data.graph.modular.ModuleNode;
import edu.uci.plrg.cfi.x86.merge.exception.MergedFailedException;
import edu.uci.plrg.cfi.x86.merge.graph.hash.HashNodeMatch.MatchType;

/**
 * <p>
 * This class abstracts an object that can match two ExecutionGraph. To initialize these two graphs, pass two
 * well-constructed ExecutionGraph and call the mergeGraph() method. It will merge the two graphs and construct a new
 * merged graph, which you can get it by calling the getMergedGraph() method.
 * </p>
 * 
 * <p>
 * It has been found that both in Linux and Windows, the real entry block of code in the main module comes from an
 * indirect branch of some certain C library (linux) or system libraries (ntdll.dll in Windows). Our current approach
 * treats the indirect edges as half speculation, which in this case means all programs will match if we don't know the
 * entry block. Therefore, we assume that we will know a list of rarely changed entry block and they can be provided as
 * part of the configuration.
 * </p>
 * 
 * <p>
 * Programs in x86/linux seems to enter their main function after a very similar dynamic-loading process, at the end of
 * which there is a indirect branch which jumps to the real main blocks. In the environment of this machine, the hash
 * value of that 'final block' is 0x1d84443b9bf8a6b3. ####
 * </p>
 * 
 * <p>
 * Date: 4:21pm (PST), 06/20/2013 We are trying new approach, which will set up a threshold for the matching up of
 * speculation. Intuitively, the threshold for indirect speculation can be less than pure speculation because it indeed
 * has more information and confidence.
 * </p>
 * 
 * <p>
 * Besides, in any speculation when there is many candidates with the same, high score, the current merging just does
 * not match any of them yet.
 * </p>
 * 
 * <p>
 * To use the current matching approach for the ModuleGraph, we extends the GraphMerger to ModuleGraphMerger, and
 * overrides its mergeGraph() method. At the same time, this class contains a ModuleGraphMerger subclass which matches
 * the ModuleGraphs.
 * </p>
 * 
 */
class HashMergeEngine {

	private final HashMergeSession session;
	final HashMatchEngine matcher;

	public static final long specialHash = new BigInteger("4f1f7a5c30ae8622", 16).longValue();
	private static final long beginHash = 0x5eee92;

	public HashMergeEngine(HashMergeSession session) {
		this.session = session;
		matcher = new HashMatchEngine(session);
	}

	protected void addUnmatchedNode2Queue(Node<?> rightNode) {
		if (rightNode == null) {
			throw new NullPointerException("There is a bug here!");
		}

		if (session.right.visitedAsUnmatched.contains(rightNode))
			return;

		session.debugLog.debugCheck(rightNode);
		session.right.visitedAsUnmatched.add(rightNode);
		session.matchState.enqueueUnmatch(rightNode);
	}

	public void mergeGraph() {
		session.initializeMerge();
		findCommonSubgraphs();
	}

	private void findCommonSubgraphs() throws WrongEdgeTypeException {
		while ((session.matchState.hasMatches() || session.matchState.hasIndirectEdges() || session.matchState
				.hasUnmatches()) && !session.hasConflict) {
			if (session.matchState.hasMatches()) {
				extendMatchedPairs();
			} else if (session.matchState.hasIndirectEdges()) {
				speculateIndirectBranches();
			} else {
				exploreHeuristicMatch();
			}
		}
	}

	private void extendMatchedPairs() {
		HashNodeMatch pairNode = session.matchState.dequeueMatch();

		Node<?> leftNode = pairNode.getLeftNode();
		Node<?> rightNode = pairNode.getRightNode();
		session.debugLog.debugCheck(leftNode);
		session.debugLog.debugCheck(rightNode);

		OrdinalEdgeList<?> rightEdges = rightNode.getOutgoingEdges();
		try {
			for (Edge<? extends Node<?>> rightEdge : rightEdges) {
				if (session.right.visitedEdges.contains(rightEdge))
					continue;

				// Find out the next matched node
				// Prioritize direct edge and call continuation edge
				Node<?> leftChild;
				boolean isDirectMatchCandidate = false;
				switch (rightEdge.getEdgeType()) {
					case INDIRECT:
					case UNEXPECTED_RETURN:
					case GENCODE_PERM:
					case GENCODE_WRITE:
						if (!rightEdge.isModuleEntry())
							break;
					case DIRECT:
					case CALL_CONTINUATION:
					case EXCEPTION_CONTINUATION:
						isDirectMatchCandidate = true;
						break;
				}

				// if (rightEdge.isClusterEntry())
				// Log.log("Cluster entry %S is direct match candidate? %b", rightEdge, isDirectMatchCandidate);

				if (isDirectMatchCandidate) {
					session.statistics.tryDirectMatch();

					leftChild = matcher.getCorrespondingDirectChildNode(leftNode, rightEdge);
					session.right.visitedEdges.add(rightEdge);

					if (leftChild != null) {
						if (session.matchedNodes.containsLeftKey(leftChild.getKey()))
							continue;

						session.matchState.enqueueMatch(new HashNodeMatch(leftChild, rightEdge.getToNode(),
								MatchType.DIRECT_BRANCH));

						// Update matched relationship
						if (!session.matchedNodes.hasPair(leftChild.getKey(), rightEdge.getToNode().getKey())) {
							if (!session.matchedNodes.addPair(leftChild, rightEdge.getToNode(),
									session.getScore(leftChild))) {
								session.contextRecord.fail("Already matched %s", rightNode);
								return;
							}
						}
					} else {
						// if (rightEdge.isClusterEntry())
						// Log.log("Cluster entry didn't have a direct match");

						addUnmatchedNode2Queue(rightEdge.getToNode());
					}
				} else {
					// Add the indirect node to the queue
					// to delay its matching
					if (!session.matchedNodes.containsRightKey(rightEdge.getToNode().getKey())) {
						session.matchState.enqueueIndirectEdge(new HashEdgePair(leftNode, rightEdge, rightNode));
					}
				}
			}
		} finally {
			rightEdges.release();
		}
	}

	private void speculateIndirectBranches() {
		HashEdgePair nodeEdgePair = session.matchState.dequeueIndirectEdge();
		Node<?> leftParentNode = nodeEdgePair.getLeftParentNode();
		Edge<? extends Node<?>> rightEdge = nodeEdgePair.getRightEdge();
		session.debugLog.debugCheck(leftParentNode);
		session.debugLog.debugCheck(rightEdge.getToNode());
		session.right.visitedEdges.add(rightEdge);

		Node<?> leftChild = matcher.getCorrespondingIndirectChildNode(leftParentNode, rightEdge);
		if (leftChild != null) {
			session.matchState.enqueueMatch(new HashNodeMatch(leftChild, rightEdge.getToNode(),
					MatchType.INDIRECT_BRANCH));

			// Update matched relationship
			if (!session.matchedNodes.hasPair(leftChild.getKey(), rightEdge.getToNode().getKey())) {
				session.matchedNodes.addPair(leftChild, rightEdge.getToNode(), session.getScore(leftChild));
			}
		} else {
			addUnmatchedNode2Queue(rightEdge.getToNode());
		}
	}

	private void exploreHeuristicMatch() {
		Node<?> rightNode = session.matchState.dequeueUnmatch();

		session.debugLog.debugCheck(rightNode);

		Node<?> leftChild = matcher.matchByHashThenContext(rightNode);
		if (leftChild != null) {
			session.matchState.enqueueMatch(new HashNodeMatch(leftChild, rightNode, MatchType.HEURISTIC));
		} else {
			// Simply push unvisited neighbors to unmatchedQueue
			OrdinalEdgeList<?> edgeList = rightNode.getOutgoingEdges();
			try {
				for (int k = 0; k < edgeList.size(); k++) {
					Edge<? extends Node<?>> rightEdge = rightNode.getOutgoingEdges().get(k);
					if (session.right.visitedEdges.contains(rightEdge))
						continue;

					addUnmatchedNode2Queue(rightEdge.getToNode());
				}
			} finally {
				edgeList.release();
			}
		}
	}

	protected void buildMergedGraph() {
		Map<Node<?>, ModuleNode<?>> leftNode2MergedNode = new HashMap<Node<?>, ModuleNode<?>>();

		// Copy nodes from left
		for (Node<?> leftNode : session.left.module.getAllNodes()) {
			session.debugLog.debugCheck(leftNode);
			ModuleNode<?> mergedNode = session.mergedGraphBuilder.addNode(leftNode.getHash(), leftNode.getModule(),
					leftNode.getRelativeTag(), leftNode.getType());
			leftNode2MergedNode.put(leftNode, mergedNode);
			session.debugLog.nodeMergedFromLeft(leftNode);
		}

		// Copy edges from left
		// Traverse edges by outgoing edges
		for (Node<? extends Node<?>> leftNode : session.left.module.getAllNodes()) {
			OrdinalEdgeList<?> leftEdges = leftNode.getOutgoingEdges();
			try {
				session.debugLog.mergingEdgesFromLeft(leftNode);
				for (Edge<? extends Node<?>> leftEdge : leftEdges) {
					ModuleNode<?> mergedFromNode = session.mergedGraphBuilder.graph.getNode(leftNode2MergedNode.get(
							leftNode).getKey());
					ModuleNode<?> mergedToNode = session.mergedGraphBuilder.graph.getNode(leftNode2MergedNode.get(
							leftEdge.getToNode()).getKey());
					Edge<ModuleNode<?>> mergedEdge = new Edge<ModuleNode<?>>(mergedFromNode, mergedToNode,
							leftEdge.getEdgeType(), leftEdge.getOrdinal());
					mergedFromNode.addOutgoingEdge(mergedEdge);
					mergedToNode.addIncomingEdge(mergedEdge);
					session.debugLog.edgeMergedFromLeft(leftEdge);
				}
			} finally {
				leftEdges.release();
			}
		}

		// Copy nodes from right
		Map<Node<?>, ModuleNode<?>> rightNode2MergedNode = new HashMap<Node<?>, ModuleNode<?>>();
		for (Node<?> rightNode : session.right.module.getAllNodes()) {
			if (session.isMutuallyUnreachable(rightNode))
				continue;

			if (!session.matchedNodes.containsRightKey(rightNode.getKey())) {
				ModuleNode<?> mergedNode = session.mergedGraphBuilder.addNode(rightNode.getHash(),
						rightNode.getModule(), rightNode.getRelativeTag(), rightNode.getType());
				// TODO: fails to add the corresponding edges when creating a new version of a node
				rightNode2MergedNode.put(rightNode, mergedNode);
				session.debugLog.nodeMergedFromRight(rightNode);
			}
		}

		addEdgesFromRight(session.right.module, leftNode2MergedNode, rightNode2MergedNode);
	}

	private void addEdgesFromRight(ModuleGraph<? extends Node<?>> right,
			Map<Node<?>, ModuleNode<?>> leftNode2MergedNode, Map<Node<?>, ModuleNode<?>> rightNode2MergedNode) {

		// Merge edges from right
		// Traverse edges in right by outgoing edges
		for (Node<? extends Node<?>> rightFromNode : right.getAllNodes()) {
			if (session.isMutuallyUnreachable(rightFromNode))
				continue;

			session.debugLog.mergingEdgesFromRight(rightFromNode);
			// New fromNode and toNode in the merged graph
			Edge<ModuleNode<?>> mergedEdge;
			OrdinalEdgeList<?> rightEdges = rightFromNode.getOutgoingEdges();
			try {
				for (Edge<? extends Node<?>> rightEdge : rightEdges) {

					mergedEdge = null;
					Node<?> rightToNode = rightEdge.getToNode();

					if (session.isMutuallyUnreachable(rightToNode))
						continue;

					ModuleNode<?> mergedFromNode = leftNode2MergedNode.get(session.left.module
							.getNode(session.matchedNodes.getMatchByRightKey(rightFromNode.getKey())));
					ModuleNode<?> mergedToNode = leftNode2MergedNode.get(session.left.module
							.getNode(session.matchedNodes.getMatchByRightKey(rightToNode.getKey())));
					// rightNode2MergedNode.get(rightToNode .getKey());
					if ((mergedFromNode != null) && (mergedToNode != null)) {
						// Both are shared nodes, need to check if there are
						// conflicts again!
						Edge<? extends ModuleNode<?>> alreadyMergedEdge = null;
						OrdinalEdgeList<? extends ModuleNode<?>> mergedEdges = mergedFromNode.getOutgoingEdges();
						try {
							for (Edge<? extends ModuleNode<?>> mergedFromEdge : mergedEdges) {
								if (mergedFromEdge.getToNode().getKey().equals(mergedToNode.getKey())) {
									alreadyMergedEdge = mergedFromEdge;
									break;
								}
							}
						} finally {
							mergedEdges.release();
						}
						if ((alreadyMergedEdge == null)
								|| ((alreadyMergedEdge.isDirect() && rightEdge.isContinuation()) || (alreadyMergedEdge
										.isContinuation() && rightEdge.isDirect()))) {
							mergedEdge = new Edge<ModuleNode<?>>(mergedFromNode, mergedToNode,
									rightEdge.getEdgeType(), rightEdge.getOrdinal());
						} else {
							if (alreadyMergedEdge.getEdgeType() != rightEdge.getEdgeType()
									|| alreadyMergedEdge.getOrdinal() != rightEdge.getOrdinal()) {
								throw new MergedFailedException(
										"Edge from %s to %s was merged with type %s and ordinal %d, but has type %s and ordinal %d in the right graph",
										rightEdge.getFromNode(), rightEdge.getToNode(),
										alreadyMergedEdge.getEdgeType(), alreadyMergedEdge.getOrdinal(), rightEdge
												.getEdgeType(), rightEdge.getOrdinal());
							}
							continue;
						}
					} else if (mergedFromNode != null) {
						// First node is a shared node
						mergedToNode = rightNode2MergedNode.get(rightToNode);
						mergedEdge = new Edge<ModuleNode<?>>(mergedFromNode, mergedToNode, rightEdge.getEdgeType(),
								rightEdge.getOrdinal());
					} else if (mergedToNode != null) {
						// Second node is a shared node
						mergedFromNode = rightNode2MergedNode.get(rightFromNode);
						mergedEdge = new Edge<ModuleNode<?>>(mergedFromNode, mergedToNode, rightEdge.getEdgeType(),
								rightEdge.getOrdinal());
					} else {
						// Both are new nodes from G2
						mergedFromNode = rightNode2MergedNode.get(rightFromNode);
						mergedToNode = rightNode2MergedNode.get(rightToNode);
						mergedEdge = new Edge<ModuleNode<?>>(mergedFromNode, mergedToNode, rightEdge.getEdgeType(),
								rightEdge.getOrdinal());
					}

					mergedFromNode.addOutgoingEdge(mergedEdge);

					if (mergedToNode == null) {
						Log.log(String.format("Error: merged node %s cannot be found", rightToNode));
						continue;
					}

					mergedToNode.addIncomingEdge(mergedEdge);
				}
			} finally {
				rightEdges.release();
			}
		}
	}

	/**
	 * By cheating (knowing the normalized tags), we can evaluate how good the matching is. It considers mismatching
	 * (nodes should not be matched have been matched) and unmatching (nodes should be matched have not been matched).
	 */
	protected void evaluateMatching() {

	}

	/**
	 * <pre>
	private Node getMainBlock(ProcessExecutionGraph graph) {
		// Checkout if the first main block equals to each other
		NodeList preMainBlocks = graph
				.getNodesByHash(ModuleGraphMerger.specialHash);
		if (preMainBlocks == null) {
			return null;
		}
		if (preMainBlocks.size() == 1) {
			Node preMainNode = preMainBlocks.get(0);
			for (int i = 0; i < preMainNode.getOutgoingEdges().size(); i++) {
				if (preMainNode.getOutgoingEdges().get(i).getEdgeType() == EdgeType.INDIRECT) {
					return preMainNode.getOutgoingEdges().get(i).getToNode();
				}
			}
		} else if (preMainBlocks.size() == 0) {
			Log.log("Important message: can't find the first main block!!!");
		} else {
			Log.log("Important message: more than one block to hash has the same hash!!!");
		}

		return null;
	}
	 */
}
