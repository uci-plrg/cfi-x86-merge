package edu.uci.plrg.cfi.x86.merge.graph.main;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import edu.uci.plrg.cfi.common.log.Log;
import edu.uci.plrg.cfi.common.log.LogFile;
import edu.uci.plrg.cfi.common.util.ArgumentStack;
import edu.uci.plrg.cfi.common.util.OptionArgumentMap;
import edu.uci.plrg.cfi.x86.graph.data.application.ApplicationModule;
import edu.uci.plrg.cfi.x86.graph.data.graph.ModuleGraph;
import edu.uci.plrg.cfi.x86.graph.data.graph.modular.ModuleNode;
import edu.uci.plrg.cfi.x86.graph.main.CommonMergeOptions;
import edu.uci.plrg.cfi.x86.merge.graph.GraphMergeCandidate;
import edu.uci.plrg.cfi.x86.merge.graph.hash.HashMergeDebugLog;
import edu.uci.plrg.cfi.x86.merge.graph.report.ModuleEventFrequencies;
import edu.uci.plrg.cfi.x86.merge.graph.report.ProgramEventFrequencies;

public class DatasetStatisticsExporter {

	private static final OptionArgumentMap.StringOption datasetOption = OptionArgumentMap.createStringOption('d');
	private static final OptionArgumentMap.StringOption logFilenameOption = OptionArgumentMap.createStringOption('l',
			"stats-exporter.log"); // or the app name?
	private static final OptionArgumentMap.StringOption exportFilenameOption = OptionArgumentMap.createStringOption(
			'f', "dataset.statistics.properties"); // or the app name?

	private final CommonMergeOptions options;

	private long start;

	private final HashMergeDebugLog debugLog = new HashMergeDebugLog();

	private final Map<ApplicationModule, Properties> moduleProperties = new HashMap<ApplicationModule, Properties>();
	private final ProgramEventFrequencies programEventFrequencies = new ProgramEventFrequencies();
	Properties moduleStatistics = new Properties();

	public DatasetStatisticsExporter(CommonMergeOptions options) {
		this.options = options;
	}

	void run(ArgumentStack args, int iterationCount) {
		try {
			options.parseOptions();

			File logFile = LogFile.create(logFilenameOption.getValue(), LogFile.CollisionMode.OVERWRITE,
					LogFile.NoSuchPathMode.SKIP);
			if (logFile != null)
				Log.addOutput(logFile);

			File statisticsFile = LogFile.create(exportFilenameOption.getValue(), LogFile.CollisionMode.AVOID,
					LogFile.NoSuchPathMode.ERROR);
			System.out.println("Generating statistics file " + statisticsFile.getAbsolutePath());

			String datasetPath = datasetOption.getValue();

			if (args.size() > 0)
				Log.log("Ignoring %d extraneous command-line arguments", args.size());

			Log.log("Exporting dataset statistics for dataset %s", datasetPath);

			options.initializeGraphEnvironment();

			start = System.currentTimeMillis();

			extractProperties(loadMergeCandidate(datasetPath));

			FileOutputStream out = new FileOutputStream(statisticsFile);
			moduleStatistics.save(out, "");
		} catch (Log.OutputException e) {
			e.printStackTrace();
		} catch (Throwable t) {
			Log.log("\t@@@@ Dataset statistics export failed with %s @@@@", t.getClass().getSimpleName());
			Log.log(t);
			System.err.println(String.format("!! Dataset statistics export failed with %s !!", t.getClass()
					.getSimpleName()));
		}
	}

	private void extractProperties(GraphMergeCandidate dataset) throws IOException {
		int moduleId = 0;
		UUID mainId = null;
		List<ModuleGraph<ModuleNode<?>>> anonymousGraphs = new ArrayList<ModuleGraph<ModuleNode<?>>>();
		Map<ApplicationModule, ModuleEventFrequencies> moduleEventMap = new HashMap<ApplicationModule, ModuleEventFrequencies>();
		for (ApplicationModule cluster : dataset.getRepresentedModules()) {
			if (cluster.isAnonymous) {
				anonymousGraphs.add((ModuleGraph<ModuleNode<?>>) dataset.getModuleGraph(cluster));
				continue;
			}

			ModuleGraph<ModuleNode<?>> graph = (ModuleGraph<ModuleNode<?>>) dataset.getModuleGraph(cluster);
			ModuleEventFrequencies moduleEventFrequencies = new ModuleEventFrequencies(moduleId++);
			moduleEventMap.put(cluster, moduleEventFrequencies);
			moduleEventFrequencies.extractStatistics(graph, programEventFrequencies);
			programEventFrequencies.countMetadataEvents(graph.metadata);
			moduleStatistics.setProperty(cluster.name, String.valueOf(moduleEventFrequencies.moduleId));
			System.gc();

			if (graph.metadata.isMain() && graph.metadata.getRootSequence() != null)
				mainId = graph.metadata.getRootSequence().getHeadExecution().id;
		}

		Log.log("Dataset has %d anonymous modules", anonymousGraphs.size());
		if (!anonymousGraphs.isEmpty()) {
			/**
			 * <pre> 
			AnonymousModuleSet anonymousModuleParser = new AnonymousModuleSet(dataset);
			anonymousModuleParser.installSubgraphs(anonymousGraphs);
			anonymousModuleParser.analyzeModules();

			for (ModuleAnonymousGraphs.OwnerKey owner : anonymousModuleParser.getModuleOwners()) {
				ModuleAnonymousGraphs module = anonymousModuleParser.getModule(owner);
				ModuleEventFrequencies moduleFrequencies = moduleEventMap.get(module.owningModule);
				Log.log("Extract stats for anonymous module owned by %s with %d subgraphs", owner.module.filename,
						module.subgraphs.size());
				moduleFrequencies.extractStatistics(module, programEventFrequencies);
			}
			 */
		}

		for (ModuleEventFrequencies moduleEventFrequencies : moduleEventMap.values()) {
			moduleEventFrequencies.exportTo(moduleEventFrequencies.moduleId, moduleStatistics, mainId);
		}
		programEventFrequencies.exportTo(moduleStatistics);
	}

	private GraphMergeCandidate loadMergeCandidate(String path) throws IOException {
		File directory = new File(path);
		if (!(directory.exists() && directory.isDirectory())) {
			Log.log("Illegal argument '" + directory + "'; no such directory.");
			printUsageAndExit();
		}

		GraphMergeCandidate candidate = new GraphMergeCandidate.Modular(directory, debugLog);
		candidate.loadData();
		return candidate;
	}

	private void printUsageAndExit() {
		System.out.println("Usage:");
		System.out.println(String.format("%s: -e <execution-graph> -d <dataset> -f <report-file>",
				DatasetStatisticsExporter.class.getSimpleName()));
		System.exit(1);
	}

	public static void main(String[] args) {
		ArgumentStack stack = new ArgumentStack(args);
		DatasetStatisticsExporter main = new DatasetStatisticsExporter(new CommonMergeOptions(stack,
				CommonMergeOptions.crowdSafeCommonDir, datasetOption, logFilenameOption, exportFilenameOption));
		main.run(stack, 1);
		main.toString();
	}
}
