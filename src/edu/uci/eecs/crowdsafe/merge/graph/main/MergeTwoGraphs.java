package edu.uci.eecs.crowdsafe.merge.graph.main;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import edu.uci.eecs.crowdsafe.common.data.dist.AutonomousSoftwareDistribution;
import edu.uci.eecs.crowdsafe.common.data.graph.execution.ModuleGraphCluster;
import edu.uci.eecs.crowdsafe.common.log.Log;
import edu.uci.eecs.crowdsafe.common.log.LogFile;
import edu.uci.eecs.crowdsafe.common.util.ArgumentStack;
import edu.uci.eecs.crowdsafe.common.util.OptionArgumentMap;
import edu.uci.eecs.crowdsafe.merge.graph.ClusterMergeSession;
import edu.uci.eecs.crowdsafe.merge.graph.MergeDebugLog;
import edu.uci.eecs.crowdsafe.merge.graph.GraphMergeResults;

public class MergeTwoGraphs {

	private static final OptionArgumentMap.StringOption logFilename = OptionArgumentMap.createStringOption('l');

	private final CommonMergeOptions options;
	private final MergeDebugLog debugLog = new MergeDebugLog();

	public MergeTwoGraphs(CommonMergeOptions options) {
		this.options = options;
	}

	void run(ArgumentStack args, int iterationCount) {
		boolean parsingArguments = true;

		try {
			options.parseOptions();

			String leftPath = args.pop();
			String rightPath = args.pop();

			File logFile = null;
			if (logFilename.getValue() != null) {
				logFile = LogFile.create(logFilename.getValue(), LogFile.CollisionMode.AVOID,
						LogFile.NoSuchPathMode.ERROR);
				Log.addOutput(logFile);
				System.out.println("Logging to " + logFile.getAbsolutePath());
			} else {
				System.out.println("Logging to system out");
				Log.addOutput(System.out);
			}

			parsingArguments = false;

			options.initializeMerge();

			GraphMergeCandidate leftCandidate = loadMergeCandidate(leftPath);
			GraphMergeCandidate rightCandidate = loadMergeCandidate(rightPath);

			if (iterationCount > 1)
				System.err
						.println(String.format(" *** Warning: entering loop of %d merge iterations!", iterationCount));

			for (int i = 0; i < iterationCount; i++) {
				merge(leftCandidate, rightCandidate, logFile);
			}
		} catch (Throwable t) {
			if (parsingArguments) {
				t.printStackTrace();
				printUsageAndExit();
			} else {
				Log.log("\t@@@@ Merge failed with %s @@@@", t.getClass().getSimpleName());
				Log.log(t);
				System.err.println(String.format("!! Merge failed with %s !!", t.getClass().getSimpleName()));
				t.printStackTrace();
			}
		}
	}

	void merge(GraphMergeCandidate leftData, GraphMergeCandidate rightData, File logFile) throws IOException {
		long mergeStart = System.currentTimeMillis();

		GraphMergeResults results = new GraphMergeResults(leftData.summarizeGraph(), rightData.summarizeGraph());

		for (AutonomousSoftwareDistribution leftCluster : leftData.getRepresentedClusters()) {
			if (!options.includeCluster(leftCluster))
				continue;

			ModuleGraphCluster rightGraph = rightData.getClusterGraph(leftCluster);
			if (rightGraph == null) {
				Log.log("Skipping cluster %s because it does not appear in the right side.", leftCluster.name);
				continue;
			}
			ModuleGraphCluster leftGraph = leftData.getClusterGraph(leftCluster);

			ClusterMergeSession.mergeTwoGraphs(leftGraph, rightGraph, results, debugLog);
		}

		Log.log("\nClusters merged in %f seconds.", ((System.currentTimeMillis() - mergeStart) / 1000.));

		if (logFile != null) {
			String resultsFilename = logFile.getName().substring(0, logFile.getName().lastIndexOf('.'));
			resultsFilename = String.format("%s.results.log", resultsFilename);
			String resultsPath = new File(logFile.getParentFile(), resultsFilename).getPath();
			File resultsFile = LogFile.create(resultsPath, LogFile.CollisionMode.ERROR, LogFile.NoSuchPathMode.ERROR);
			FileOutputStream out = new FileOutputStream(resultsFile);
			results.getResults().writeTo(out);
			out.flush();
			out.close();
		} else {
			Log.log("Results logging skipped.");
		}
	}

	private GraphMergeCandidate loadMergeCandidate(String path) throws IOException {
		GraphMergeCandidate candidate = null;
		switch (path.charAt(0)) {
			case 'c':
				candidate = new GraphMergeCandidate.Cluster(debugLog);
				break;
			case 'e':
				candidate = new GraphMergeCandidate.Execution(debugLog);
				break;
			default:
				throw new IllegalArgumentException(
						String.format(
								"Run directory is missing the graph type specifier:\n\t%s\nIt must be preceded by \"c:\" (for a cluster graph) or \"e:\" (for an execution graph).",
								path));
		}

		File directory = new File(path.substring(path.indexOf(':') + 1));
		if (!(directory.exists() && directory.isDirectory())) {
			Log.log("Illegal argument '" + directory + "'; no such directory.");
			printUsageAndExit();
		}

		candidate.loadData(directory);
		return candidate;
	}

	private void printUsageAndExit() {
		System.out
				.println(String
						.format("Usage: %s [ -c <cluster-name>,... ] [ -d <crowd-safe-common-dir> ] [ -l <log-dir> ] { c: | e: }<left-trace-dir> { c: | e: }<right-trace-dir> [<log-output>]",
								MergeTwoGraphs.class.getSimpleName()));
		System.exit(1);
	}

	public static void main(String[] args) {
		ArgumentStack stack = new ArgumentStack(args);
		MergeTwoGraphs main = new MergeTwoGraphs(new CommonMergeOptions(stack, logFilename));
		main.run(stack, 1);
	}
}