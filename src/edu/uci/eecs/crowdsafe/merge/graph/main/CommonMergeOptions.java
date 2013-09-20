package edu.uci.eecs.crowdsafe.merge.graph.main;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import edu.uci.eecs.crowdsafe.common.config.CrowdSafeConfiguration;
import edu.uci.eecs.crowdsafe.common.data.dist.AutonomousSoftwareDistribution;
import edu.uci.eecs.crowdsafe.common.data.dist.ConfiguredSoftwareDistributions;
import edu.uci.eecs.crowdsafe.common.util.ArgumentStack;
import edu.uci.eecs.crowdsafe.common.util.OptionArgumentMap;

class CommonMergeOptions {

	private final OptionArgumentMap.StringOption crowdSafeCommonDir = OptionArgumentMap.createStringOption('d');
	private final OptionArgumentMap.StringOption restrictedClusterOption = OptionArgumentMap.createStringOption('c');

	private final OptionArgumentMap map;

	public final Set<AutonomousSoftwareDistribution> clusterMergeSet = new HashSet<AutonomousSoftwareDistribution>();

	CommonMergeOptions(ArgumentStack args, OptionArgumentMap.Option<?>... options) {
		List<OptionArgumentMap.Option<?>> allOptions = new ArrayList<OptionArgumentMap.Option<?>>();
		for (OptionArgumentMap.Option<?> option : options) {
			allOptions.add(option);
		}
		allOptions.add(crowdSafeCommonDir);
		allOptions.add(restrictedClusterOption);
		map = new OptionArgumentMap(args, allOptions);
	}

	void parseOptions() {
		map.parseOptions();
	}

	void initializeMerge() {
		CrowdSafeConfiguration.initialize(EnumSet.of(CrowdSafeConfiguration.Environment.CROWD_SAFE_COMMON_DIR));
		if (crowdSafeCommonDir.getValue() == null) {
			ConfiguredSoftwareDistributions.initialize();
		} else {
			ConfiguredSoftwareDistributions.initialize(new File(crowdSafeCommonDir.getValue()));
		}

		if (restrictedClusterOption.getValue() == null) {
			clusterMergeSet.addAll(ConfiguredSoftwareDistributions.getInstance().distributions.values());
		} else {
			StringTokenizer clusterNames = new StringTokenizer(restrictedClusterOption.getValue(), ",");
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
	}

	boolean includeCluster(AutonomousSoftwareDistribution cluster) {
		return clusterMergeSet.contains(cluster);
	}
}
