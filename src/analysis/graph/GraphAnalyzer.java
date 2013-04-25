package analysis.graph;

import gnu.getopt.Getopt;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import utils.AnalysisUtil;

import analysis.graph.debug.DebugUtils;
import analysis.graph.representation.ExecutionGraph;

public class GraphAnalyzer {

	// The number of threads on a single machine, it's configurable
	private static int threadGroupSize = 32;

	// Counter of pair comparisons it has done
	private static int pairCnt;

	// The current thread counter
	private static int threadCnt;

	public static void main(String[] argvs) {
		Getopt g = new Getopt("GraphAnalyzer", argvs, "msd:t:");
		int c;
		// By default we will have numThreads number of threads
		boolean error = false, sameProg = false, merge = false;
		String runDirs = null;
		while ((c = g.getopt()) != -1) {
			switch (c) {
			case 'm':
				merge = true;
				break;
			case 's':
				sameProg = true;
				break;
			case 'd':
				runDirs = g.getOptarg();
				break;
			case 't':
				threadGroupSize = Integer.parseInt(g.getOptarg());
				break;
			case '?':
				error = true;
				System.out.println("parse error for option: -"
						+ (char) g.getOptopt());
				break;
			default:
				error = true;
				break;
			}
		}

		if (runDirs == null) {
			error = true;
		}
		
		if (error) {
			System.out.println("Parameter error, correct it first!");
			return;
		}
		
		if (merge) {
			ExecutionGraph bigGraph = mergeOneGraph(runDirs);
		} else {
			pairComparison(runDirs, sameProg);
		}
	}

	public static ExecutionGraph mergeOneGraph(String dir) {
		ArrayList<String> runDirs = AnalysisUtil.getAllRunDirs(dir);

		ExecutionGraph bigGraph = ExecutionGraph.buildGraphsFromRunDir(
				runDirs.get(0)).get(0);
		GraphMergingInfo.dumpGraph(bigGraph,
				"graph-files/" + bigGraph.getProgName() + bigGraph.getPid()
						+ ".dot");
		bigGraph.setProgName("bigGraph");
		for (int i = 1; i < runDirs.size(); i++) {
			ExecutionGraph graph = ExecutionGraph.buildGraphsFromRunDir(
					runDirs.get(i)).get(0);
			GraphMerger graphMerger = new GraphMerger(bigGraph, graph);
			ExecutionGraph tmpGraph = graphMerger.mergeGraph();
			if (tmpGraph != null) {
				int newNodeSize = tmpGraph.getNodes().size() - bigGraph.getNodes().size();
				bigGraph = tmpGraph;
				GraphMergingInfo.dumpGraph(bigGraph,
						"graph-files/" + bigGraph.getProgName() + bigGraph.getPid()
								+ ".dot");
				System.out.println("Added " + newNodeSize + " nodes to the bigGraph");
			}
		}
		return bigGraph;
	}

	public static void pairComparison(String dir, boolean runSameProgram) {
		ArrayList<String> runDirs = AnalysisUtil.getAllRunDirs(dir);

		GraphMerger[] mergers = new GraphMerger[threadGroupSize];
		threadCnt = 0;
		pairCnt = 0;

		for (int i = 0; i < runDirs.size(); i++) {
			for (int j = i + 1; j < runDirs.size(); j++) {
				String dirName1 = runDirs.get(i), dirName2 = runDirs.get(j);
				if (dirName1.indexOf("run") == -1
						|| dirName2.indexOf("run") == -1) {
					continue;
				}

				// Run the algorithm on different programs or the same programs
				if (AnalysisUtil.getProgNameFromPath(dirName1).equals(
						AnalysisUtil.getProgNameFromPath(dirName2))) {
					if (!runSameProgram) {
						continue;
					}
				} else {
					if (runSameProgram) {
						continue;
					}
				}
				ExecutionGraph g1 = ExecutionGraph.buildGraphsFromRunDir(
						dirName1).get(0), g2 = ExecutionGraph
						.buildGraphsFromRunDir(dirName2).get(0);

				// System.out.println("Current thread: " + threadCnt);
				mergers[threadCnt] = new GraphMerger(g1, g2);
				mergers[threadCnt].start();
				threadCnt++;
				pairCnt++;

				if (threadCnt == threadGroupSize) {
					threadCnt = 0;
					System.out.println(pairCnt + " pairs have been launched!");
					for (int k = 0; k < threadGroupSize; k++) {
						try {
							mergers[k].join();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}

			}
		}
	}
}
