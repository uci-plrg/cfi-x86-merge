package analysis.graph;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import utils.AnalysisUtil;

import analysis.graph.representation.Edge;
import analysis.graph.representation.EdgeType;
import analysis.graph.representation.ExecutionGraph;
import analysis.graph.representation.MatchedNodes;
import analysis.graph.representation.Node;

public class GraphMergingInfo {
	
	public final ExecutionGraph graph1, graph2;
	public final MatchedNodes matchedNodes;
	
	public GraphMergingInfo(ExecutionGraph graph1, ExecutionGraph graph2, MatchedNodes matchedNodes) {
		this.graph1 = graph1;
		this.graph2 = graph2;
		this.matchedNodes = matchedNodes;
	}

	public void dumpMatchedNodes() {
		for (int index1 : matchedNodes) {
			int index2 = matchedNodes.getByFirstIndex(index1);
			System.out.println(index1 + "<-->" + index2);
		}
	}

	synchronized public void outputMergedGraphInfo() {
		System.out.println("Comparison between " + graph1.getProgName() + graph1.getPid() + " & " + 
			graph2.getProgName() + graph2.getPid() + ":");
		
		System.out.println("Size of nodes in graph1: " + graph1.getNodes().size());
		System.out.println("Size of nodes in graph2: " + graph2.getNodes().size());
		HashSet<Long> interPairHashes = AnalysisUtil.intersection(
				graph1.getPairHashes(), graph2.getPairHashes()), interBlockHashes = AnalysisUtil
				.intersection(graph1.getBlockHashes(), graph2.getBlockHashes());
		HashSet<Long> totalPairHashes = new HashSet(graph1.getPairHashes());
		totalPairHashes.addAll(graph2.getPairHashes());
		HashSet<Long> totalBlockHashes = new HashSet(graph1.getBlockHashes());
		totalBlockHashes.addAll(graph2.getBlockHashes());
		System.out.println("Intersection ratio of block hashes: "
				+ (float) interBlockHashes.size() / totalBlockHashes.size());
		int totalNodeSize = graph1.getNodes().size() + graph2.getNodes().size()
				- matchedNodes.size();
		System.out.println("Merged nodes: " + matchedNodes.size());
		System.out.println("Merged nodes / G1 nodes: "
				+ (float) matchedNodes.size() / graph1.getNodes().size());
		System.out.println("Merged nodes / G2 nodes: "
				+ (float) matchedNodes.size() / graph2.getNodes().size());
		System.out.println("Merged nodes / all nodes: "
				+ (float) matchedNodes.size() / totalNodeSize);
		System.out.println();
	}

	public void dumpNodesRelationship(String fileName) {
		File file = new File(fileName);
		if (!file.exists()) {
			try {
				file.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		PrintWriter pwRelationFile = null;

		try {
			pwRelationFile = new PrintWriter(fileName + ".relation");
			for (int index1 : matchedNodes) {
				int index2 = matchedNodes.getByFirstIndex(index1);
				pwRelationFile.println(index1 + "->" + index2);
			}

			pwRelationFile.flush();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		if (pwRelationFile != null)
			pwRelationFile.close();
	}

	public static void dumpGraph(ExecutionGraph graph, String fileName) {
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

			for (int i = 0; i < graph.getNodes().size(); i++) {
				pwNodeFile.println(Long.toHexString(graph.getNodes().get(i)
						.getHash()));
			}

			pwDotFile.println("digraph runGraph {");
			long firstMainBlock = GraphInfo.outputFirstMain(graph);
			pwDotFile.println("# First main block: "
					+ Long.toHexString(firstMainBlock));
			for (int i = 0; i < graph.getNodes().size(); i++) {
				pwDotFile.println(i + "[label=\""
						+ Long.toHexString(graph.getNodes().get(i).getHash())
						+ "\"]");

				ArrayList<Edge> edges = graph.getNodes().get(i).getEdges();
				for (Edge e : edges) {
					String branchType;
					switch (e.getEdgeType()) {
					case Indirect:
						branchType = "i";
						break;
					case Direct:
						branchType = "d";
						break;
					case Call_Continuation:
						branchType = "c";
						break;
					case Unexpected_Return:
						branchType = "u";
						break;
					default:
						branchType = "";
						break;
					}

					pwDotFile.println(i + "->" + e.getNode().getIndex()
							+ "[label=\"" + branchType + "_" + e.getOrdinal()
							+ "\"]");
				}
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

	public static void dumpHashCollision(ExecutionGraph graph) {

	}
}