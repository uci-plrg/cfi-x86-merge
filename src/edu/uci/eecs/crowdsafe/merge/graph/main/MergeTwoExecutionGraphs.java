package edu.uci.eecs.crowdsafe.merge.graph.main;

import java.io.File;
import java.io.FileOutputStream;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;

import edu.uci.eecs.crowdsafe.common.config.CrowdSafeConfiguration;
import edu.uci.eecs.crowdsafe.common.data.dist.AutonomousSoftwareDistribution;
import edu.uci.eecs.crowdsafe.common.data.dist.ConfiguredSoftwareDistributions;
import edu.uci.eecs.crowdsafe.common.data.graph.execution.ModuleGraphCluster;
import edu.uci.eecs.crowdsafe.common.data.graph.execution.ProcessExecutionGraph;
import edu.uci.eecs.crowdsafe.common.data.graph.execution.loader.ProcessGraphLoadSession;
import edu.uci.eecs.crowdsafe.common.datasource.ProcessTraceDataSource;
import edu.uci.eecs.crowdsafe.common.datasource.ProcessTraceDirectory;
import edu.uci.eecs.crowdsafe.common.datasource.ProcessTraceStreamType;
import edu.uci.eecs.crowdsafe.common.log.Log;
import edu.uci.eecs.crowdsafe.common.log.LogFile;
import edu.uci.eecs.crowdsafe.merge.graph.ClusterMergeSession;
import edu.uci.eecs.crowdsafe.merge.graph.GraphMergeDebug;
import edu.uci.eecs.crowdsafe.merge.graph.GraphMergeResults;
import gnu.getopt.Getopt;

public class MergeTwoExecutionGraphs {

	private static final EnumSet<ProcessTraceStreamType> MERGE_FILE_TYPES = EnumSet.of(ProcessTraceStreamType.MODULE,
			ProcessTraceStreamType.GRAPH_HASH, ProcessTraceStreamType.MODULE_GRAPH,
			ProcessTraceStreamType.CROSS_MODULE_GRAPH);

	void run(String[] args, int iterationCount) {
		try {
			int optionArgCount = 0;
			String restrictedClusterArg = null;
			String crowdSafeCommonDir = null;
			try {
				Getopt options = new Getopt("", args, "c:d:");
				options.setOpterr(false);
				int c;
				while ((c = options.getopt()) != -1) {
					switch ((char) c) {
						case 'c':
							restrictedClusterArg = options.getOptarg();
							optionArgCount += 2;
							break;
						case 'd':
							crowdSafeCommonDir = options.getOptarg();
							optionArgCount += 2;
							break;
					}
				}
			} catch (Throwable t) {
				printUsageAndExit();
			}

			File logFile = null;
			if ((args.length - optionArgCount) > 2) {
				try {
					logFile = LogFile.create(args[optionArgCount + 2], LogFile.CollisionMode.AVOID,
							LogFile.NoSuchPathMode.ERROR);
					Log.addOutput(logFile);
					System.out.println("Logging to " + logFile.getName());
				} catch (LogFile.Exception e) {
					e.printStackTrace(System.err);
				}
			} else {
				Log.addOutput(System.out);
			}

			if ((args.length - optionArgCount) < 2) {
				Log.log("Illegal arguments: please specify the two run directories as relative or absolute paths.");
				printUsageAndExit();
			}

			CrowdSafeConfiguration.initialize(EnumSet.of(CrowdSafeConfiguration.Environment.CROWD_SAFE_COMMON_DIR));
			if (crowdSafeCommonDir == null) {
				ConfiguredSoftwareDistributions.initialize();
			} else {
				ConfiguredSoftwareDistributions.initialize(new File(crowdSafeCommonDir));
			}

			Set clusterMergeSet = new HashSet<AutonomousSoftwareDistribution>();
			if (restrictedClusterArg == null) {
				clusterMergeSet.addAll(ConfiguredSoftwareDistributions.getInstance().distributions.values());
			} else {
				StringTokenizer clusterNames = new StringTokenizer(restrictedClusterArg, ",");
				while (clusterNames.hasMoreTokens()) {
					String clusterName = clusterNames.nextToken();
					AutonomousSoftwareDistribution cluster = ConfiguredSoftwareDistributions.getInstance().distributions
							.get(clusterName);
					if (cluster == null) {
						throw new IllegalArgumentException(String.format(
								"Restricted cluster element %s cannot be found in cluster configuration directory %s.",
								clusterName, ConfiguredSoftwareDistributions.getInstance().configDir.getAbsolutePath()));
					}
					clusterMergeSet.add(cluster);
				}
			}

			File leftRun = new File(args[optionArgCount]);
			File rightRun = new File(args[optionArgCount + 1]);

			if (!(leftRun.exists() && leftRun.isDirectory())) {
				Log.log("Illegal argument '" + args[optionArgCount] + "'; no such directory.");
				printUsageAndExit();
			}
			if (!(rightRun.exists() && rightRun.isDirectory())) {
				Log.log("Illegal argument '" + args[optionArgCount + 1] + "'; no such directory.");
				printUsageAndExit();
			}

			ProcessTraceDataSource leftDataSource = new ProcessTraceDirectory(leftRun, MERGE_FILE_TYPES);
			ProcessTraceDataSource rightDataSource = new ProcessTraceDirectory(rightRun, MERGE_FILE_TYPES);

			long start = System.currentTimeMillis();
			GraphMergeDebug debugLog = new GraphMergeDebug();

			ProcessGraphLoadSession leftSession = new ProcessGraphLoadSession(leftDataSource);
			ProcessExecutionGraph leftGraph = leftSession.loadGraph(debugLog);

			ProcessGraphLoadSession rightSession = new ProcessGraphLoadSession(rightDataSource);
			ProcessExecutionGraph rightGraph = rightSession.loadGraph(debugLog);

			GraphMergeResults results = new GraphMergeResults(leftGraph, rightGraph);

			long merge = System.currentTimeMillis();
			Log.log("\nGraph loaded in %f seconds.", ((merge - start) / 1000.));

			for (int i = 0; i < iterationCount; i++) {
				System.out.println("Entering merge loop iteration");
				try {
					for (ModuleGraphCluster leftCluster : leftGraph.getAutonomousClusters()) {
						if (!clusterMergeSet.contains(leftCluster.distribution))
							continue;

						ModuleGraphCluster rightCluster = rightGraph.getModuleGraphCluster(leftCluster.distribution);
						if (rightCluster == null) {
							Log.log("Skipping cluster %s because it does not appear in the right side.",
									leftCluster.distribution.name);
							continue;
						}

						ClusterMergeSession session = new ClusterMergeSession(leftCluster, rightCluster, results,
								debugLog);
						session.mergeTwoGraphs();
					}
				} catch (Throwable t) {
					t.printStackTrace();
				}
			}

			Log.log("\nClusters merged in %f seconds.", ((System.currentTimeMillis() - merge) / 1000.));

			if (logFile != null) {
				String resultsFilename = logFile.getName().substring(0, logFile.getName().lastIndexOf('.'));
				resultsFilename = String.format("%s.results.log", resultsFilename);
				String resultsPath = new File(logFile.getParentFile(), resultsFilename).getPath();
				File resultsFile = LogFile.create(resultsPath, LogFile.CollisionMode.ERROR,
						LogFile.NoSuchPathMode.ERROR);
				FileOutputStream out = new FileOutputStream(resultsFile);
				results.getResults().writeTo(out);
				out.flush();
				out.close();
			} else {
				Log.log("Results logging skipped.");
			}
		} catch (Throwable t) {
			Log.log("\t@@@@ Merge failed with %s @@@@", t.getClass().getSimpleName());
			Log.log(t);
			System.err.println(String.format("!! Merge failed with %s !!", t.getClass().getSimpleName()));
		}
	}

	private void printUsageAndExit() {
		System.out
				.println(String
						.format("Usage: %s [ -c <cluster-name>,... ] [ -d <crowd-safe-common-dir> ] <left-trace-dir> <right-trace-dir> [<log-output>]",
								MergeTwoExecutionGraphs.class.getSimpleName()));
		System.exit(1);
	}

	public static void main(String[] args) {
		MergeTwoExecutionGraphs main = new MergeTwoExecutionGraphs();
		main.run(args, 1);
	}
}
