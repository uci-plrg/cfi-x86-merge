package edu.uci.eecs.crowdsafe.merge.graph.report;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.uci.eecs.crowdsafe.graph.data.graph.Edge;
import edu.uci.eecs.crowdsafe.graph.data.graph.EdgeType;
import edu.uci.eecs.crowdsafe.graph.data.graph.cluster.ClusterNode;

public class ExecutionReport {

	private static class RiskSorter implements Comparator<ReportEntry> {

		private static final RiskSorter INSTANCE = new RiskSorter();

		@Override
		public int compare(ReportEntry first, ReportEntry second) {
			int result = first.getRiskIndex() - second.getRiskIndex();
			if (result == 0)
				return result;
			
			long hashCompare = (first.hashCode() - second.hashCode());
			if (hashCompare > 0)
				return 1;
			else if (hashCompare < 0)
				return -1;
			else
				return 0;
		}
	}

	static String getModuleName(ClusterNode<?> node) {
		switch (node.getType()) {
			case CLUSTER_ENTRY:
				return "Module entry from ";
			case CLUSTER_EXIT:
				return "Module exit to ";
			case SINGLETON:
				return "JIT singleton ";
			case TRAMPOLINE:
				return "Dynamic standalone ";
			default:
				return node.getModule().unit.filename;
		}

	}

	static long getId(ClusterNode<?> node) {
		switch (node.getType()) {
			case CLUSTER_ENTRY:
			case CLUSTER_EXIT:
				// ideally show hash source: { <module>!export, <module>!callback, <module>!main }
				return node.getHash();
			default:
				return node.getRelativeTag();
		}
	}

	static boolean isReportedEdgeType(EdgeType type) {
		switch (type) {
			case INDIRECT:
			case UNEXPECTED_RETURN:
			case GENCODE_PERM:
			case GENCODE_WRITE:
				return true;
			case DIRECT:
			case CALL_CONTINUATION:
			case EXCEPTION_CONTINUATION:
				return false;
		}
		throw new IllegalArgumentException("Unknown EdgeType " + type);
	}

	private List<ReportEntry> entries = new ArrayList<ReportEntry>();
	private Set<Edge<ClusterNode<?>>> filteredEdges = new HashSet<Edge<ClusterNode<?>>>();

	private ModuleEventFrequencies currentModuleEventFrequencies = null;

	void setCurrentModuleEventFrequencies(ModuleEventFrequencies moduleEventFrequencies) {
		this.currentModuleEventFrequencies = moduleEventFrequencies;
	}

	public void sort(ProgramEventFrequencies programEventFrequencies) {
		for (ReportEntry entry : entries) {
			entry.setEventFrequencies(programEventFrequencies);
			entry.evaluateRisk();
		}

		Collections.sort(entries, RiskSorter.INSTANCE);
	}

	public void print(File outputFile) throws FileNotFoundException {
		PrintStream out = new PrintStream(outputFile);
		for (ReportEntry entry : entries) {
			if (entry instanceof NewEdgeReport && filteredEdges.contains(((NewEdgeReport) entry).edge))
				continue;
			entry.print(out);
			out.println();
		}
	}

	void addEntry(ReportEntry entry) {
		entries.add(entry);

		entry.setEventFrequencies(currentModuleEventFrequencies);
	}

	void filterEdgeReport(Edge<ClusterNode<?>> edge) {
		filteredEdges.add(edge);
	}
}
