package analysis.graph;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;

import utils.AnalysisUtil;

public class ExecutionGraph {
	static public class Node {
		private long tag, hash;
		// Identify the same hash code with different tags
		public int hashOrdinal;

		private ArrayList<Edge> edges;
		private int isVisited;
		// For temporal usage
		private int isTmpVisited;
		// Index in the ArrayList<Node>, in order to search the
		// node in constant time
		private int index;

		// This records which graph the node belongs to
		// 0 represents from both
		// 1 represents from graph1
		// 2 represents from graph2
		int fromWhichGraph = 0;

		public Node(Node anotherNode) {
			this(anotherNode.tag, anotherNode.hash, anotherNode.hashOrdinal);
			// FIXME: Not a deep copy yet, because we have edges...
		}

		public Node(long tag, long hash) {
			this(tag, hash, 0);
		}

		public Node(long tag, long hash, int hashOrdinal) {
			this.tag = tag;
			this.hash = hash;
			this.hashOrdinal = hashOrdinal;
			edges = new ArrayList<Edge>();
			isVisited = 0;
		}

		public Node(long tag) {
			this.tag = tag;
			edges = new ArrayList<Edge>();
			isVisited = 0;
		}

		/**
		 * In a single execution, tag is the only identifier for the node
		 */
		public boolean equals(Object o) {
			if (o.getClass() != Node.class) {
				return false;
			}
			Node node = (Node) o;
			if (node.tag != tag)
				return false;
			else
				return true;
		}

		public int hashCode() {
			return ((Long) tag).hashCode() << 5 ^ ((Long) hash).hashCode()
					^ hashOrdinal;
		}
	}

	public static class Edge {
		Node node;
		boolean isDirect;
		int ordinal;

		public Edge(Node node, boolean isDirect, int ordinal) {
			this.node = node;
			this.isDirect = isDirect;
			this.ordinal = ordinal;
		}

		public Edge(Node node, int flag) {
			this.node = node;
			this.ordinal = flag % 256;
			isDirect = flag / 256 == 1;
		}
	}

	// the edges of the graph comes with an ordinal
	private HashMap<Node, HashMap<Node, Integer>> adjacentList;

	private String runDirName;

	private String progName;

	private int pid;

	private static boolean hasConflict = false;

	// nodes in an array in the read order from file
	private ArrayList<Node> nodes;

	private HashMap<Long, Node> hashLookupTable;

	// Map from hash to ArrayList<Node>,
	// which also helps to find out the hash collisions
	private HashMap<Long, ArrayList<Node>> hash2Nodes;

	private HashSet<Long> blockHash;

	public void setBlockHash(String fileName) {
		blockHash = AnalysisUtil.getSetFromPath(fileName);
	}

	// if false, it means that the file doesn't exist or is in wrong format
	private boolean isValidGraph = true;

	public ExecutionGraph(ExecutionGraph anotherGraph) {
		this.runDirName = anotherGraph.runDirName;
		this.progName = anotherGraph.progName;
		// Copy the nodes, lookup table and hash2Nodes mapping
		// all at once
		nodes = new ArrayList<Node>(anotherGraph.nodes.size());
		hashLookupTable = new HashMap<Long, Node>();
		hash2Nodes = new HashMap<Long, ArrayList<Node>>();
		for (int i = 0; i < anotherGraph.nodes.size(); i++) {
			Node anotherNode = anotherGraph.nodes.get(i), thisNode = new Node(
					anotherNode);
			nodes.add(thisNode);
			// Copy the lookup table
			hashLookupTable.put(thisNode.tag, thisNode);
			// Copy the hash2Nodes
			if (hash2Nodes.get(thisNode.hash) == null) {
				hash2Nodes.put(thisNode.hash, new ArrayList());
			}
			if (!hash2Nodes.get(thisNode.hash).contains(thisNode)) {
				hash2Nodes.get(thisNode.hash).add(thisNode);
			}
		}

		// Copy the adjacentList
		adjacentList = new HashMap<Node, HashMap<Node, Integer>>();
		for (Node fromNode : anotherGraph.adjacentList.keySet()) {
			Node thisFromNode = hashLookupTable.get(fromNode.tag);
			HashMap<Node, Integer> map = new HashMap<Node, Integer>();

			for (Node toNode : anotherGraph.adjacentList.get(fromNode).keySet()) {
				Node thisToNode = hashLookupTable.get(toNode.tag);
				int edgeFlag = anotherGraph.adjacentList.get(fromNode).get(
						toNode);
				map.put(thisToNode, edgeFlag);
			}
			adjacentList.put(thisFromNode, map);
		}

		if (isSameGraph(this, anotherGraph))
			System.out.println("Graph copying error!");
	}

	public ExecutionGraph() {
		adjacentList = new HashMap<Node, HashMap<Node, Integer>>();
		hash2Nodes = new HashMap<Long, ArrayList<Node>>();
	}

	public ExecutionGraph(ArrayList<String> tagFiles,
			ArrayList<String> lookupFiles) {
		adjacentList = new HashMap<Node, HashMap<Node, Integer>>();
		hash2Nodes = new HashMap<Long, ArrayList<Node>>();

		this.progName = AnalysisUtil.getProgName(tagFiles.get(0));
		this.pid = AnalysisUtil.getPidFromFileName(tagFiles.get(0));
		readGraphLookup(lookupFiles);
		readGraph(tagFiles);
		if (!isValidGraph) {
			System.out.println("Pid " + pid + " is not a valid graph!");
		}
	}

	public String getProgName() {
		return progName;
	}

	private void setProgName(String progName) {
		this.progName = progName;
	}

	/**
	 * Use tag as an identifier, just a simple function to check the correctness
	 * of copying a graph
	 * 
	 * @param graph1
	 * @param graph2
	 * @return
	 */
	private static boolean isSameGraph(ExecutionGraph graph1,
			ExecutionGraph graph2) {
		if (graph1.nodes.size() != graph2.nodes.size())
			return false;
		if (!graph1.nodes.equals(graph2.nodes))
			return false;
		if (graph1.adjacentList.equals(graph2.adjacentList))
			return false;
		return true;
	}

	/**
	 * try to merge two graphs !!! Seems that every two graphs can be merged, so
	 * maybe there should be a way to evaluate how much the two graphs conflict
	 * One case is unmergeable: two direct branch nodes with same hash value but
	 * have different branch targets (Seems wired!!)
	 * 
	 * ####42696542a8bb5822 I am doing a trick here: programs in x86/linux seems
	 * to enter their main function after a very similar dynamic-loading
	 * process, at the end of which there is a indirect branch which jumps to
	 * the real main blocks. In the environment of this machine, the hash value
	 * of that 'final block' is 0x1d84443b9bf8a6b3. ####
	 * 
	 * FIXME Something's wrong here, the block that finally jumps to main is
	 * 0x4f1f7a5c30ae8622, and the previously found node is actually from the
	 * constructor of the program (__libc_csu_init). Things might get wrong
	 * here!!!
	 * 
	 * @param otherGraph
	 */
	private static final long specialHash = new BigInteger("4f1f7a5c30ae8622",
			16).longValue();
	private static final long beginHash = 0x5eee92;

	private static Node getCorrespondingNode(ExecutionGraph graph1,
			ExecutionGraph graph2, Node node2,
			HashMap<Long, Node> newNodesFromGraph2) {
		// Get all the nodes that have the same hash from graph1
		ArrayList<Node> nodes = graph1.hash2Nodes.get(node2.hash);
		// New hash code in graph2
		if (nodes == null || nodes.size() == 0)
			return null;
		for (int i = 0; i < nodes.size(); i++) {
			Node node1 = nodes.get(i);
			int res = getContextSimilarity(graph1, node1, graph2, node2, 3);
			if (res == 1) {
				return node1;
				// FIXME
				// We just consider the first node that we think have similar
				// context as the same node
			} else if (res == -1) {
				hasConflict = true;
			}
		}

		// This should be a newly added node from graph2
		// Use a trick here, trace the added nodes from graph2
		// by its tag from graph2
		return newNodesFromGraph2.get(node2.tag);
	}

	/**
	 * The merge algorithm here is trivial and probably we need to modify it
	 * sooner and later!!!
	 * 
	 * @param graph1
	 * @param graph2
	 * @return
	 */
	public static ExecutionGraph mergeGraph(ExecutionGraph graph1,
			ExecutionGraph graph2) {

		// Merge based on the similarity of the first node ---- sanity check!
		// FIXME: For some executions, the first node does not necessary locate
		// in the first position!!!
		if (graph1.nodes.get(0).hash != graph2.nodes.get(0).hash) {
			System.out
					.println("First node not the same, so wired and I can't merge...");
			return null;
		}
		// Checkout if first main block equals to each other
		ArrayList<Node> mainBlocks1 = graph1.hash2Nodes
				.get(ExecutionGraph.specialHash), mainBlocks2 = graph2.hash2Nodes
				.get(ExecutionGraph.specialHash);
		if (mainBlocks1.size() == 1 && mainBlocks2.size() == 1) {
			if (mainBlocks1.get(0).hash != mainBlocks2.get(0).hash) {
				System.out.println("First block not the same, not mergeable!");
				return null;
			}
		} else {
			System.out
					.println("Important message: more than one block to hash has the same hash!!!");
		}

		// Before merge, reset fromWhichGraph filed to be 1
		for (int i = 0; i < graph1.nodes.size(); i++) {
			graph1.nodes.get(i).fromWhichGraph = 1;
		}
		// Newly added nodes from graph2
		HashMap<Long, Node> newNodesFromGraph2 = new HashMap<Long, Node>();

		// Need a queue to do a BFS on one of the graph
		Queue<Node> bfsQueue = new LinkedList<Node>();
		bfsQueue.add(graph2.nodes.get(0));

		while (bfsQueue.size() > 0 && !hasConflict) {
			Node curNode = bfsQueue.remove();
			ArrayList<Edge> edges = curNode.edges;
			curNode.isVisited = 1;

			// Get the counterpart from graph1
			// In most cases, node1 should not be null
			if (curNode.hash == new BigInteger("3343f09ada0", 16).longValue()) {
				System.out.println("Stop!");
			}
			Node node1 = getCorrespondingNode(graph1, graph2, curNode,
					newNodesFromGraph2);
			if (node1 == null) {
				node1 = new Node(curNode);
				// Don't forget to update the 'nodes' list, 'hash2Nodes'
				// table and the info in the node itself
				newNodesFromGraph2.put(curNode.tag, node1);
				graph1.nodes.add(node1);
				node1.index = graph1.nodes.size() - 1;
				node1.fromWhichGraph = 2;
				if (graph1.hash2Nodes.get(node1.hash) == null) {
					graph1.hash2Nodes.put(node1.hash, new ArrayList<Node>());
				}
				graph1.hash2Nodes.get(node1.hash).add(node1);
			}
			// If the node is not from 2, then it must owned by both graphs
			if (node1.fromWhichGraph != 2)
				node1.fromWhichGraph = 0;
			
			for (int i = 0; i < edges.size(); i++) {
				if (edges.get(i).node.isVisited == 0) {
					Node nextNode = edges.get(i).node, nextNode1 = getCorrespondingNode(
							graph1, graph2, nextNode, newNodesFromGraph2);
//					if (nextNode.hash == new BigInteger("3343f09ada0", 16).longValue()) {
//						System.out.println("Stop!");
//					}
					bfsQueue.add(nextNode);
					if (node1.fromWhichGraph == 2) {
						if (nextNode1 == null) {
							nextNode1 = new Node(nextNode);
							// Don't forget to update the 'nodes' list,
							// 'hash2Nodes'
							// table and the info in the node itself
							newNodesFromGraph2.put(nextNode.tag, nextNode);
							graph1.nodes.add(nextNode1);
							nextNode1.index = graph1.nodes.size() - 1;
							nextNode1.fromWhichGraph = 2;
							if (graph1.hash2Nodes.get(nextNode1.hash) == null) {
								graph1.hash2Nodes.put(nextNode1.hash,
										new ArrayList<Node>());
							}
							graph1.hash2Nodes.get(nextNode1.hash)
									.add(nextNode1);
						}
						// One more thing: update the edges field!!
						Edge e = new Edge(nextNode1, edges.get(i).isDirect,
								edges.get(i).ordinal);
						node1.edges.add(e);
					} else { // node1 is a node already in graph1
						if (nextNode1 == null) {
							nextNode1 = new Node(nextNode);
							// Don't forget to update the 'nodes' list,
							// 'hash2Nodes'
							// table and the info in the node itself
							newNodesFromGraph2.put(nextNode.tag, nextNode);
							graph1.nodes.add(nextNode1);
							nextNode1.index = graph1.nodes.size() - 1;
							nextNode1.fromWhichGraph = 2;
							if (graph1.hash2Nodes.get(nextNode1.hash) == null) {
								graph1.hash2Nodes.put(nextNode1.hash,
										new ArrayList<Node>());
							}
							graph1.hash2Nodes.get(nextNode1.hash)
									.add(nextNode1);
							
							// One more thing: update the edges field!!
							// Don't forget!!!
							Edge e = new Edge(nextNode1, edges.get(i).isDirect,
									edges.get(i).ordinal);
							node1.edges.add(e);
						} else {
							nextNode1.isVisited = 1;
							nextNode1.fromWhichGraph = 0;
						}
					}
				}
			}
		}
		if (!hasConflict) {
			System.out.println("Awesome! The two graphs merge!!");
			graph1.dumpGraph("graph-files/merge.dot");
			System.out.println(newNodesFromGraph2.size());
			for (long newHash : newNodesFromGraph2.keySet()) {
				System.out.println(Long.toHexString(newHash));
			}
		}

		return null;
	}

	// Search the nearby context to check the similarity of the
	// node1 and node2
	// Depth is how deep the query should try, by default depth == 3
	// Return val: 0 means not the same context, 1 means similar context,
	// -1 means potential conflict (not the same program!!!)
	// TODO: Only when we are sure that the two nodes to be compared is
	// the exact same node can we say for sure that returning -1 really
	// means conflict happens
	private static int getContextSimilarity(ExecutionGraph graph1, Node node1,
			ExecutionGraph graph2, Node node2, int depth) {
		if (depth <= 0)
			return 1;
		ArrayList<Edge> edges1 = node1.edges, edges2 = node2.edges;
		// One node does not have any outgoing edges!!
		// Might FIXME. Not enough information, what should I do?
		// Just think that they are still similar...
		if (edges1.size() == 0 || edges2.size() == 0) {
			if (node1.fromWhichGraph == 2)
				return 0;
			else
				return 1;
		}
			
		if (edges1.get(0).isDirect && edges2.get(0).isDirect) {
			for (int i = 0; i < edges1.size(); i++) {
				for (int j = 0; j < edges2.size(); j++) {
					if (edges1.get(i).ordinal == edges2.get(j).ordinal) {
						if (edges1.get(i).node.hash != edges2.get(j).node.hash) {
							return 0;
						} else {
							int match = getContextSimilarity(graph1,
									edges1.get(i).node, graph2,
									edges2.get(j).node, depth - 1);
							if (match == 0)
								return 0;
						}
					}
				}
			}
		} else if (!edges1.get(0).isDirect && !edges2.get(0).isDirect) {
			// Too many indirect jumps... simply think that they are
			// in very similar context
			if (edges1.size() == 1 && edges2.size() > 3 && edges1.size() > 3
					&& edges2.size() == 1)
				return 0;
			for (int i = 0; i < edges1.size(); i++) {
				for (int j = 0; j < edges2.size(); j++) {
					if (edges1.get(i).ordinal != edges2.get(j).ordinal) {
						// should be both 0
						return 0;
					}
					if (edges1.get(i).node.hash == edges2.get(j).node.hash) {
						return getContextSimilarity(graph1, node1, graph2,
								node2, depth - 1);
					} else {

					}

				}
			}
		} else { // Similar context requires the branch type be the same!!
					// ** Assumption **
			return -1;
		}
		return 1;
	}

	public void dumpHashCollision() {
		System.out.println(progName + "." + pid + " -> hash collision:");
		for (long hash : hash2Nodes.keySet()) {
			ArrayList<Node> nodes = hash2Nodes.get(hash);
			if (nodes.size() > 1) {
				boolean isAllDifferent = true;
				if (nodes.get(0).hash == 0xff) {
					System.out.println("Stop!");
					// for (int i = 0; i < nodes.size(); i++) {
					//
					// }
				}
				int count = 0;
				for (int i = 0; i < nodes.size(); i++) {
					for (int j = i + 1; j < nodes.size(); j++) {
						// if (1 == getContextSimilarity(this, nodes.get(i),
						// this, nodes.get(j), 5)) {
						// isAllDifferent = false;
						// count++;
						// }
					}
				}
				if (!isAllDifferent) {
					System.out.println(Long.toHexString(nodes.get(0).hash)
							+ " happens " + nodes.size() + " times.");
				}
			}
		}
		System.out.println();
	}

	public long outputFirstMain() {
		Node n = null;
		long firstMainHash = -1;
		for (int i = 0; i < nodes.size(); i++) {
			if (nodes.get(i).hash == specialHash) {
				n = nodes.get(i);
				if (adjacentList.get(n).size() > 1) {
					System.out.println("More than one target!");
					return adjacentList.get(n).size();
				} else {
					for (Node node : adjacentList.get(n).keySet()) {
						firstMainHash = node.hash;
						// System.out.println(Long.toHexString(firstMainHash));
					}
				}
				break;
			}
		}
		return firstMainHash;
	}

	public void dumpGraph(String fileName) {
		File file = new File(fileName);
		if (!file.exists()) {
			try {
				file.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		file = new File(fileName + ".node");
		if (!file.exists()) {
			try {
				file.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		PrintWriter pwDotFile = null, pwNodeFile = null;

		try {
			pwDotFile = new PrintWriter(fileName);
			pwNodeFile = new PrintWriter(fileName + ".node");

			for (int i = 0; i < nodes.size(); i++) {
				pwNodeFile.println(Long.toHexString(nodes.get(i).hash));
			}

			pwDotFile.println("digraph runGraph {");
			long firstMainBlock = outputFirstMain();
			pwDotFile.println("# First main block: "
					+ Long.toHexString(firstMainBlock));
			for (int i = 0; i < nodes.size(); i++) {
				// pw.println("node_" + Long.toHexString(nodes.get(i).hash));
				pwDotFile.println(i + "[label=\""
						+ Long.toHexString(nodes.get(i).hash) + "\"]");

				ArrayList<Edge> edges = nodes.get(i).edges;
				for (Edge e : edges) {
					String branchType;
					if (e.isDirect) {
						branchType = "d";
					} else {
						branchType = "i";
					}

					pwDotFile.println(i + "->" + e.node.index + "[label=\""
							+ branchType + "_" + e.ordinal + "\"]");
				}
				// HashMap<Node, Integer> edges =
				// adjacentList.get(nodes.get(i));
				// for (Node node : edges.keySet()) {
				// int flag = edges.get(node);
				// int ordinal = flag % 256;
				// String branchType;
				// if (flag / 256 == 1) {
				// branchType = "d";
				// } else {
				// branchType = "i";
				// }
				// // pw.println("node_" + Long.toHexString(nodes.get(i).hash)
				// // + "->"
				// // + "node_" + Long.toHexString(node.hash) + "[label=\""
				// // + branchType + "_" + ordinal + "_" +
				// // Long.toHexString(node.tag) + "\"]");
				// pwDotFile.println("node_" +
				// Long.toHexString(nodes.get(i).tag)
				// + "->" + "node_" + Long.toHexString(node.tag)
				// + "[label=\"" + branchType + "_" + ordinal + "\"]");
				//
				// }
			}

			pwDotFile.print("}");
			pwDotFile.flush();

			pwNodeFile.flush();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		if (pwDotFile != null)
			pwDotFile.close();
		if (pwNodeFile != null)
			pwNodeFile.close();

	}

	public boolean isValidGraph() {
		return this.isValidGraph;
	}

	private void readGraphLookup(ArrayList<String> lookupFiles) {
		hashLookupTable = new HashMap<Long, Node>();
		FileInputStream fileIn = null;
		DataInputStream dataIn = null;

		for (int i = 0; i < lookupFiles.size(); i++) {
			String lookupFile = lookupFiles.get(i);
			// if (lookupFile.indexOf("ld") == -1)
			// continue;
			try {
				fileIn = new FileInputStream(lookupFile);
				dataIn = new DataInputStream(fileIn);
				long tag = 0, hash = 0;
				while (true) {
					// the tag and hash here is already a big-endian value
					long tagOriginal = AnalysisUtil
							.reverseForLittleEndian(dataIn.readLong());
					tag = getTagEffectiveValue(tagOriginal);

					if (tagOriginal != tag) {
						System.out.println("Tag more than 6 bytes");
						System.out.println(Long.toHexString(tagOriginal)
								+ " : " + Long.toHexString(tag));
					}

					hash = AnalysisUtil.reverseForLittleEndian(dataIn
							.readLong());
					// Tags don't duplicate in lookup file
					if (hashLookupTable.containsKey(tag)) {
						if (hashLookupTable.get(tag).hash != hash) {
							isValidGraph = false;
							System.out
									.println(Long.toHexString(tag)
											+ " -> "
											+ Long.toHexString(hashLookupTable
													.get(tag).hash) + ":"
											+ Long.toHexString(hash) + "  "
											+ lookupFile);
						}
					}
					Node node = new Node(tag, hash);
					hashLookupTable.put(tag, node);

					// Add it the the hash2Nodes mapping
					if (hash2Nodes.get(hash) == null) {
						hash2Nodes.put(hash, new ArrayList<Node>());
					}
					if (!hash2Nodes.get(hash).contains(node)) {
						hash2Nodes.get(hash).add(node);
					}
				}
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (EOFException e) {

			} catch (IOException e) {
				e.printStackTrace();
			}
			if (dataIn != null) {
				try {
					dataIn.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	public void readGraph(ArrayList<String> tagFiles)
			throws NullPointerException {

		nodes = new ArrayList<Node>();
		for (int i = 0; i < tagFiles.size(); i++) {
			String tagFile = tagFiles.get(i);
			// if (tagFile.indexOf("ld") == -1)
			// continue;
			File file = new File(tagFile);
			// V <= E / 2 + 1
			FileInputStream fileIn = null;
			DataInputStream dataIn = null;
			// to track how many tags does not exist in lookup file
			HashSet<Long> hashesNotInLookup = new HashSet<Long>();
			try {
				fileIn = new FileInputStream(file);
				dataIn = new DataInputStream(fileIn);
				while (true) {
					long tag1 = AnalysisUtil.reverseForLittleEndian(dataIn
							.readLong());
					int flag = getEdgeFlag(tag1);
					tag1 = getTagEffectiveValue(tag1);
					long tag2Original = AnalysisUtil
							.reverseForLittleEndian(dataIn.readLong());
					long tag2 = getTagEffectiveValue(tag2Original);
					if (tag2 != tag2Original) {
						System.out.println("Something wrong about the tag");
						// System.out.println(Long.toHexString(tag2Original) +
						// " : "
						// + Long.toHexString(tag2));
					}

					Node node1 = hashLookupTable.get(tag1), node2 = hashLookupTable
							.get(tag2);
					// double check if tag1 and tag2 exist in the lookup file
					if (node1 == null) {
						// System.out.println(Long.toHexString(tag1) +
						// " is not in lookup file");
						hashesNotInLookup.add(tag1);
					}
					if (node2 == null) {
						// System.out.println(Long.toHexString(tag2) +
						// " is not in lookup file");
						hashesNotInLookup.add(tag2);
					}
					if (node1 == null || node2 == null)
						continue;

					// also put the nodes into the adjacentList if they are not
					// stored yet
					// add node to an array, which is in their seen order in the
					// file
					if (!adjacentList.containsKey(node1)) {
						adjacentList.put(node1, new HashMap<Node, Integer>());
						nodes.add(node1);
						// Important!! Don't forget to update the index
						node1.index = nodes.size() - 1;
					}
					if (!adjacentList.containsKey(node2)) {
						adjacentList.put(node2, new HashMap<Node, Integer>());
						nodes.add(node2);
						// Important!! Don't forget to update the index
						node2.index = nodes.size() - 1;
					}

					HashMap<Node, Integer> edges;
					edges = adjacentList.get(node1);
					// Also update the ArrayList<Edge> of node
					if (!edges.containsKey(node2)) {
						edges.put(node2, flag);
						node1.edges.add(new Edge(node2, flag));
					}
				}
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (EOFException e) {
				// System.out.println("Finish reading the file: " + fileName);
			} catch (IOException e) {
				e.printStackTrace();
			}
			if (hashesNotInLookup.size() != 0) {
				isValidGraph = false;
				System.out.println(hashesNotInLookup.size()
						+ " tag doesn't exist in lookup file -> " + tagFile);
				// for (long l : hashesNotInLookup) {
				// System.out.println(Long.toHexString(l));
				// }
			}

			if (dataIn != null) {
				try {
					dataIn.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		// since V will never change once the graph is created
		nodes.trimToSize();

	}

	// Return the highest two bytes
	public static int getEdgeFlag(long tag) {
		return new Long(tag >>> 48).intValue();
	}

	public static boolean isDirectBranch(long tag) {
		int flag = new Long(tag >>> 48).intValue();
		if (flag / 256 == 1) {
			return true;
		} else {
			return false;
		}
	}

	// get the lower 6 byte of the tag, which is a long integer
	public static long getTagEffectiveValue(long tag) {
		Long res = tag << 16 >>> 16;
		return res;
	}

	public static ArrayList<ExecutionGraph> buildGraphsFromRunDir(String dir) {
		ArrayList<ExecutionGraph> graphs = new ArrayList<ExecutionGraph>();

		File dirFile = new File(dir);
		String[] fileNames = dirFile.list();
		HashMap<Integer, ArrayList<String>> pid2LookupFiles = new HashMap<Integer, ArrayList<String>>(), pid2TagFiles = new HashMap<Integer, ArrayList<String>>();

		for (int i = 0; i < fileNames.length; i++) {
			int pid = AnalysisUtil.getPidFromFileName(fileNames[i]);
			if (pid == 0)
				continue;
			if (pid2LookupFiles.get(pid) == null) {
				pid2LookupFiles.put(pid, new ArrayList<String>());
				pid2TagFiles.put(pid, new ArrayList<String>());
			}
			if (fileNames[i].indexOf("bb-graph-hash.") != -1) {
				pid2LookupFiles.get(pid).add(dir + "/" + fileNames[i]);
			} else if (fileNames[i].indexOf("bb-graph.") != -1) {
				pid2TagFiles.get(pid).add(dir + "/" + fileNames[i]);
			}
		}

		// Build the graphs
		for (int pid : pid2LookupFiles.keySet()) {

			ArrayList<String> lookupFiles = pid2LookupFiles.get(pid), tagFiles = pid2TagFiles
					.get(pid);

			String possibleProgName = AnalysisUtil.getProgName(lookupFiles
					.get(0));
			ExecutionGraph graph = new ExecutionGraph();
			graph.progName = possibleProgName;
			graph.pid = pid;
			graph.readGraphLookup(lookupFiles);
			graph.readGraph(tagFiles);
			if (!graph.isValidGraph) {
				System.out.println("Pid " + pid + " is not a valid graph!");
			}
			// graph.dumpGraph("graph-files/" + possibleProgName + "." + pid +
			// ".dot");
			graphs.add(graph);
		}

		return graphs;
	}

	public static void main(String[] argvs) {
		ArrayList<ExecutionGraph> graphs = buildGraphsFromRunDir(argvs[0]);

		for (int i = 0; i < graphs.size(); i++) {
			ExecutionGraph graph = graphs.get(i);
			if (!graph.isValidGraph()) {
				System.out.print("This is a wrong graph!");
			}
			graph.dumpGraph("graph-files/" + graph.progName + "." + graph.pid
					+ ".dot");
		}
		mergeGraph(graphs.get(0), graphs.get(1));

	}
}
