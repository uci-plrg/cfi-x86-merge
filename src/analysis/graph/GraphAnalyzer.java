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
		// Getopt g = new Getopt("GraphAnalyzer", argvs, "xonf:d:t:m:");
		// int c;
		// // By default we will have numThreads number of threads
		// boolean append = true,
		// isAnalyzing = true,
		// error = false;
		// String dir4Files = null, dir4Runs = null,
		// recordFile = null, freqFile = null;
		// String dir4Hashset1 = null, dir4Hashset2 = null;
		// boolean isDistance = false;
		// while ((c = g.getopt()) != -1) {
		// switch (c) {
		// case 'o':
		// append = false;
		// break;
		// case 'f':
		// dir4Files = g.getOptarg();
		// if (dir4Files.startsWith("-"))
		// error = true;
		// break;
		// case 'd':
		// dir4Runs = g.getOptarg();
		// if (dir4Runs.startsWith("-"))
		// error = true;
		// break;
		// case 't':
		// numThreads = Integer.parseInt(g.getOptarg());
		// break;
		// case 'n':
		// isAnalyzing = false;
		// break;
		// case 'm':
		// freqFile = g.getOptarg();
		// break;
		// case 'x':
		// // this option is used to compute the distance of two sets
		// // it should work with -m option
		// isDistance = true;
		// break;
		// case '?':
		// error = true;
		// System.out.println("parse error for option: -" + (char)
		// g.getOptopt());
		// break;
		// default:
		// error = true;
		// break;
		// }
		// }

		pairComparison(argvs[0], true);
//		ExecutionGraph bigGraph = mergeOneGraph(argvs[0]);
	}
	
	public static ExecutionGraph mergeOneGraph(String dir) {
		ArrayList<String> runDirs = AnalysisUtil.getAllRunDirs(dir);
		
		ExecutionGraph bigGraph = ExecutionGraph.buildGraphsFromRunDir(
				runDirs.get(0)).get(0);
		bigGraph.setProgName("bigGraph");
		for (int i = 1; i < runDirs.size(); i++) {
			GraphMerger graphMerger = new GraphMerger(bigGraph,
					ExecutionGraph.buildGraphsFromRunDir(runDirs.get(i))
							.get(0));
			bigGraph = graphMerger.mergeGraph();
			GraphMergingInfo.dumpGraph(
					bigGraph,
					"graph-files/" + bigGraph.getProgName()
							+ bigGraph.getPid() + ".dot");
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
