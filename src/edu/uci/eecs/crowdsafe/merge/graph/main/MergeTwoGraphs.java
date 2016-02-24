package edu.uci.eecs.crowdsafe.merge.graph.main;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import edu.uci.eecs.crowdsafe.common.log.Log;
import edu.uci.eecs.crowdsafe.common.log.LogFile;
import edu.uci.eecs.crowdsafe.common.util.ArgumentStack;
import edu.uci.eecs.crowdsafe.common.util.OptionArgumentMap;
import edu.uci.eecs.crowdsafe.graph.data.application.ApplicationModule;
import edu.uci.eecs.crowdsafe.graph.data.graph.ModuleGraph;
import edu.uci.eecs.crowdsafe.graph.data.graph.modular.ApplicationGraph;
import edu.uci.eecs.crowdsafe.graph.data.graph.modular.ModuleNode;
import edu.uci.eecs.crowdsafe.graph.data.graph.modular.writer.ModuleGraphWriter;
import edu.uci.eecs.crowdsafe.graph.io.modular.ModularTraceDataSink;
import edu.uci.eecs.crowdsafe.graph.io.modular.ModularTraceDirectory;
import edu.uci.eecs.crowdsafe.graph.main.CommonMergeOptions;
import edu.uci.eecs.crowdsafe.merge.graph.GraphMergeCandidate;
import edu.uci.eecs.crowdsafe.merge.graph.GraphMergeStrategy;
import edu.uci.eecs.crowdsafe.merge.graph.MergeResults;
import edu.uci.eecs.crowdsafe.merge.graph.hash.HashMergeAnalysis;
import edu.uci.eecs.crowdsafe.merge.graph.hash.HashMergeDebugLog;
import edu.uci.eecs.crowdsafe.merge.graph.hash.HashMergeSession;
import edu.uci.eecs.crowdsafe.merge.graph.tag.TagMergeResults;
import edu.uci.eecs.crowdsafe.merge.graph.tag.TagMergeSession;

public class MergeTwoGraphs {

	public interface MergeCompletion {
		void mergeCompleted(ApplicationGraph mergedGraph) throws IOException;
	}

	public static class WriteCompletedGraphs implements MergeCompletion {
		private final ModularTraceDataSink dataSink;
		private final String filenameFormat;

		public WriteCompletedGraphs(ModularTraceDataSink dataSink, String filenameFormat) {
			this.dataSink = dataSink;
			this.filenameFormat = filenameFormat;
		}

		@Override
		public void mergeCompleted(ApplicationGraph mergedGraph) throws IOException {
			dataSink.addModule(mergedGraph.graph.module, filenameFormat);
			ModuleGraphWriter writer = new ModuleGraphWriter(mergedGraph.graph, dataSink);
			writer.writeGraph();
		}
	}

	public static class IgnoreMergeCompletion implements MergeCompletion {
		@Override
		public void mergeCompleted(ApplicationGraph mergedGraph) throws IOException {
		}
	}

	static String getCorrespondingResultsFilename(File logFile) {
		String resultsFilename = logFile.getName().substring(0, logFile.getName().lastIndexOf('.'));
		return String.format("%s.results.dat", resultsFilename);
	}

	static String getCorrespondingDynamicResultsFilename(File logFile) {
		String resultsFilename = logFile.getName().substring(0, logFile.getName().lastIndexOf('.'));
		return String.format("%s.dynamic.log", resultsFilename);
	}

	private static final OptionArgumentMap.StringOption logFilenameOption = OptionArgumentMap.createStringOption('l',
			"merge.log");
	private static final OptionArgumentMap.StringOption strategyOption = OptionArgumentMap.createStringOption('s',
			GraphMergeStrategy.TAG.id);
	private static final OptionArgumentMap.StringOption nameOption = OptionArgumentMap.createStringOption('n');
	private static final OptionArgumentMap.StringOption outputOption = OptionArgumentMap.createStringOption('o');
	private static final OptionArgumentMap.StringOption inPlaceOption = OptionArgumentMap.createStringOption('i');
	private static final OptionArgumentMap.BooleanOption verboseOption = OptionArgumentMap.createBooleanOption('v');

	private final CommonMergeOptions options;
	private final HashMergeDebugLog debugLog = new HashMergeDebugLog();

	public MergeTwoGraphs(CommonMergeOptions options) {
		this.options = options;
	}

	void run(ArgumentStack args) {
		boolean parsingArguments = true;

		try {
			options.parseOptions();

			if (verboseOption.getValue())
				Log.addOutput(System.out);

			File logFile = null;
			if (logFilenameOption.getValue() != null) {
				logFile = LogFile.create(logFilenameOption.getValue(), LogFile.CollisionMode.AVOID,
						LogFile.NoSuchPathMode.ERROR);
				Log.addOutput(logFile);
				System.out.println("Logging to " + logFile.getAbsolutePath());
			} else if (!verboseOption.hasValue()) {
				System.out.println("Logging to system out");
				Log.addOutput(System.out);
			}

			if (outputOption.hasValue() != nameOption.hasValue()) {
				Log.log("Options 'name' (-n) and 'output' (-o) may only be used together. Exiting now.");
				System.exit(1);
			}

			String leftPath = args.pop();
			String rightPath;

			if (inPlaceOption.hasValue()) {
				if (outputOption.hasValue()) {
					Log.log("Options 'in-place' (-i) and 'output' (-o) cannot be used together. Exiting now.");
					System.exit(1);
				}
				if (nameOption.hasValue()) {
					Log.log("Option 'name' (-n) cannot be used with 'in-place' (-i). Exiting now.");
					System.exit(1);
				}
				if (inPlaceOption.getValue().startsWith("c:") || inPlaceOption.getValue().startsWith("e:")) {
					Log.log("Option 'in-place' (-i) does not accept a file format specifier. It is only compatible with the cluster file format. Exiting now.");
					System.exit(1);
				}

				rightPath = "c:" + inPlaceOption.getValue();
			} else {
				rightPath = args.pop();
			}

			GraphMergeStrategy strategy = GraphMergeStrategy.forId(strategyOption.getValue());
			if (strategy == null)
				throw new IllegalArgumentException("Unknown merge strategy " + strategyOption.getValue());

			if (args.size() > 0)
				Log.log("Ignoring %d extraneous command-line arguments", args.size());

			parsingArguments = false;

			Log.log("Merge two graphs:\n\tLeft: %s\n\tRight: %s", leftPath, rightPath);

			options.initializeGraphEnvironment();

			GraphMergeCandidate leftCandidate = loadMergeCandidate(leftPath);
			GraphMergeCandidate rightCandidate = (leftPath.equals(rightPath) ? leftCandidate
					: loadMergeCandidate(rightPath));

			MergeCompletion completion = new IgnoreMergeCompletion();
			File outputDir = null;
			if (outputOption.hasValue())
				outputDir = new File(outputOption.getValue());
			else if (inPlaceOption.hasValue())
				outputDir = new File(inPlaceOption.getValue());
			if (outputDir != null) {
				outputDir.mkdir();

				ModularTraceDataSink dataSink = new ModularTraceDirectory(outputDir);
				String filenameFormat = "%s.%%s.%%s.%%s";
				if (nameOption.hasValue()) {
					filenameFormat = String.format(filenameFormat, nameOption.getValue());
				} else {
					filenameFormat = String.format(filenameFormat, rightCandidate.parseTraceName());
				}
				completion = new WriteCompletedGraphs(dataSink, filenameFormat);
			}

			merge(leftCandidate, rightCandidate, strategy, logFile, completion);

		} catch (Log.OutputException e) {
			e.printStackTrace();
		} catch (Throwable t) {
			if (parsingArguments) {
				t.printStackTrace();
				printUsageAndExit();
			} else {
				Log.log("\t@@@@ Merge failed with %s @@@@", t.getClass().getSimpleName());
				Log.log(t);
				System.err.println(String.format("!! Merge failed with %s !!", t.getClass().getSimpleName()));
			}
		}
	}

	@SuppressWarnings("unchecked")
	void merge(GraphMergeCandidate leftData, GraphMergeCandidate rightData, GraphMergeStrategy strategy, File logFile,
			MergeCompletion completion) throws IOException {
		long mergeStart = System.currentTimeMillis();

		MergeResults results;
		switch (strategy) {
			case HASH:
				results = new HashMergeAnalysis();
				break;
			case TAG:
				results = new TagMergeResults();
				break;
			default:
				throw new IllegalArgumentException("Unknown merge strategy " + strategy);
		}

		List<ModuleGraph<ModuleNode<?>>> leftAnonymousGraphs = new ArrayList<ModuleGraph<ModuleNode<?>>>();
		List<ModuleGraph<ModuleNode<?>>> rightAnonymousGraphs = new ArrayList<ModuleGraph<ModuleNode<?>>>();

		// cs-todo: this can be multi-threaded for large training datasets
		for (ApplicationModule leftCluster : leftData.getRepresentedModules()) {
			if (!options.includeModule(leftCluster))
				continue;

			if ((strategy == GraphMergeStrategy.TAG) && leftCluster.isAnonymous) {
				// cast is ok because tag merge only works on cluster graphs
				leftAnonymousGraphs.add((ModuleGraph<ModuleNode<?>>) leftData.getModuleGraph(leftCluster));
				// leftData.summarizeCluster(leftCluster);
				continue;
			}

			Log.log("\n > Loading cluster %s < \n", leftCluster.name);

			ApplicationGraph mergedGraph = null;
			ModuleGraph<?> leftGraph = leftData.getModuleGraph(leftCluster);
			ModuleGraph<?> rightGraph = rightData.getModuleGraph(leftCluster);
			if (rightGraph == null) {
				// leftGraph.logUnknownSuspiciousUIB();
				if (strategy == GraphMergeStrategy.TAG) {
					Log.log("Copying left cluster %s because it does not appear in the right side.", leftCluster.name);
					// leftData.summarizeCluster(leftCluster);
					mergedGraph = new ApplicationGraph((ModuleGraph<ModuleNode<?>>) leftGraph);
				} else {
					Log.log("Skipping left cluster %s because it does not appear in the right side and has incompatible format with the merge data.",
							leftCluster.name);
					continue;
				}
			} else {
				/**
				 * <pre> Not needed for unit mode
				if (!rightGraph.isCompatible(leftGraph)) {
					Log.log("Warning: skipping cluster %s because its module's versions are not compatible with the right side",
							leftCluster.name);
					continue;
				}
				 */

				switch (strategy) {
					case HASH:
						mergedGraph = HashMergeSession.mergeTwoGraphs(leftGraph, rightGraph,
								(HashMergeAnalysis) results, new HashMergeSession.DefaultEvaluator(), debugLog);
						break;
					case TAG:
						mergedGraph = TagMergeSession.mergeTwoGraphs(leftGraph,
								(ModuleGraph<ModuleNode<?>>) rightGraph, (TagMergeResults) results);
						break;
					default:
						throw new IllegalArgumentException("Unknown merge strategy " + strategy);
				}

				/**
				 * <pre>
				if (!mergedGraph.graph.metadata.isEmpty()) {
					for (ClusterMetadataSequence sequence : mergedGraph.graph.metadata.sequences.values()) {
						Log.log("Merged metadata sequence of %d executions | is root? %b", sequence.executions.size(),
								sequence.isRoot());
						for (ClusterMetadataExecution execution : sequence.executions) {
							Log.log("\tExecution has %d uibp and %d intervals", execution.uibs.size(),
									execution.getIntervalCount());
						}
					}
				}
				 */

				// leftData.summarizeCluster(leftCluster);
				// rightData.summarizeCluster(leftCluster);

				Log.log("Checking reachability on the merged graph.");
				mergedGraph.graph.resetAnalysis();
				mergedGraph.graph.analyzeGraph(true);
			}

			completion.mergeCompleted(mergedGraph);
			System.gc();
		}

		if ((strategy == GraphMergeStrategy.TAG) && (rightData != leftData)) {
			for (ApplicationModule rightCluster : rightData.getRepresentedModules()) {
				if (options.includeModule(rightCluster) && !leftData.getRepresentedModules().contains(rightCluster)) {
					Log.log("Copying right cluster %s because it does not appear in the left side.", rightCluster.name);

					if (rightCluster.isAnonymous) {
						// cast is ok because tag merge only works on cluster graphs
						rightAnonymousGraphs.add((ModuleGraph<ModuleNode<?>>) rightData.getModuleGraph(rightCluster));
					} else {
						ModuleGraph<ModuleNode<?>> rightGraph = (ModuleGraph<ModuleNode<?>>) rightData
								.getModuleGraph(rightCluster);
						completion.mergeCompleted(new ApplicationGraph(rightGraph));
					}
					// rightData.summarizeCluster(rightCluster);
				}
			}
		}

		if (strategy == GraphMergeStrategy.TAG) {
			HashMergeAnalysis anonymousResults = new HashMergeAnalysis();

			if (rightData != leftData) {
				for (ApplicationModule rightCluster : rightData.getRepresentedModules()) {
					if (!options.includeModule(rightCluster))
						continue;

					if (rightCluster.isAnonymous) {
						// cast is ok because tag merge only works on cluster graphs
						rightAnonymousGraphs.add((ModuleGraph<ModuleNode<?>>) rightData.getModuleGraph(rightCluster));
						// rightData.summarizeCluster(rightCluster);
					}
				}
			}
			// AnonymousGraphMergeEngine anonymousMerge = new AnonymousGraphMergeEngine(leftData, rightData, debugLog);
			// ApplicationGraph anonymousGraph = anonymousMerge.createAnonymousGraph(leftAnonymousGraphs,
			// rightAnonymousGraphs);
			// completion.mergeCompleted(anonymousGraph);

			// if (logFile != null)
			// writeResults(anonymousResults, getCorrespondingDynamicResultsFilename(logFile), logFile);
		}

		Log.log("Summarizing both left and right now.");
		results.setGraphSummaries(leftData.summarizeGraph(), rightData.summarizeGraph());

		Log.log("\nClusters merged in %f seconds.", ((System.currentTimeMillis() - mergeStart) / 1000.));

		if (logFile != null) {
			writeResults(results, getCorrespondingResultsFilename(logFile), logFile);
		} else {
			Log.log("Results logging skipped.");
		}
	}

	private GraphMergeCandidate loadMergeCandidate(String path) throws IOException {
		File directory = new File(path.substring(path.indexOf(':') + 1));
		if (!(directory.exists() && directory.isDirectory())) {
			Log.log("Illegal argument '" + directory + "'; no such directory.");
			printUsageAndExit();
		}

		GraphMergeCandidate candidate = null;
		switch (path.charAt(0)) {
			case 'c':
				candidate = new GraphMergeCandidate.Modular(directory, debugLog);
				break;
			case 'e':
				candidate = new GraphMergeCandidate.Execution(directory, debugLog);
				break;
			default:
				throw new IllegalArgumentException(
						String.format(
								"Run directory is missing the graph type specifier:\n\t%s\nIt must be preceded by \"c:\" (for a cluster graph) or \"e:\" (for an execution graph).",
								path));
		}

		candidate.loadData();
		return candidate;
	}

	private void writeResults(MergeResults results, String resultsFilename, File logFile) throws IOException {
		String resultsPath = new File(logFile.getParentFile(), resultsFilename).getPath();
		File resultsFile = LogFile.create(resultsPath, LogFile.CollisionMode.ERROR, LogFile.NoSuchPathMode.ERROR);
		FileOutputStream out = new FileOutputStream(resultsFile);
		out.write(results.getStrategy().getMessageType().id);
		results.getResults().writeTo(out);
		out.flush();
		out.close();
	}

	private void printUsageAndExit() {
		System.out.println("Usage:");
		System.out
				.println(String
						.format("Merge: %s [ -c <cluster-name>,... ] [ -d <crowd-safe-common-dir> ] [ -l <log-dir> ]\n\t{ c: | e: }<left-trace-dir> { c: | e: }<right-trace-dir>",
								MergeTwoGraphs.class.getSimpleName()));
		System.out
				.println(String
						.format("with output: %s [  ] [ -l <log-dir> ]\n\t-n <output-name> -o <output-dir> { c: | e: }<left-trace-dir> { c: | e: }<right-trace-dir>",
								MergeTwoGraphs.class.getSimpleName()));
		System.out
				.println(String
						.format("in-place: %s [ -c <cluster-name>,... ] [ -d <crowd-safe-common-dir> ] [ -l <log-dir> ]\n\t-i { c: | e: }<in-place-trace-dir> { c: | e: }<other-trace-dir>",
								MergeTwoGraphs.class.getSimpleName()));
		System.out.println("-s { hash | tag } (merge strategy)");
		System.out.println("-c <cluster-name>,... (include only these clusters)");
		System.out.println("-x <cluster-name>,... (exclude these clusters)");
		System.out.println("-d <crowd-safe-common-dir>");
		System.exit(1);
	}

	public static void main(String[] args) {
		ArgumentStack stack = new ArgumentStack(args);
		MergeTwoGraphs main = new MergeTwoGraphs(new CommonMergeOptions(stack, CommonMergeOptions.crowdSafeCommonDir,
				CommonMergeOptions.restrictedModuleOption, CommonMergeOptions.unitModuleOption,
				CommonMergeOptions.excludeModuleOption, logFilenameOption, nameOption, strategyOption, outputOption,
				inPlaceOption, verboseOption));
		main.run(stack);

		main.toString();
	}
}
