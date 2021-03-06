package edu.uci.plrg.cfi.x86.merge.graph.report;

import java.io.PrintStream;

import edu.uci.plrg.cfi.common.log.Log;
import edu.uci.plrg.cfi.common.util.RiskySystemCall;
import edu.uci.plrg.cfi.x86.graph.data.graph.modular.metadata.ModuleSSC;
import edu.uci.plrg.cfi.x86.merge.graph.report.ModuleEventFrequencies.ModulePropertyReader;
import edu.uci.plrg.cfi.x86.merge.graph.report.ProgramEventFrequencies.ProgramPropertyReader;

// 0. syscall category!
// 1. # SSC in the entire program having this sysnum
// [ 2. # SSC where suspicion is raised from this module ] // not really available since it's relatively new
public class SuspiciousSyscallReport implements ReportEntry {

	private final ModuleSSC ssc;

	private int sameSuspiciousSysnumCount = 0;
	private int riskIndex;

	SuspiciousSyscallReport(ModuleSSC ssc) {
		this.ssc = ssc;

		if (RiskySystemCall.sysnumMap.get(ssc.sysnum) == null)
			throw new IllegalArgumentException("Not reporting sysnum #" + ssc.sysnum);
	}

	@Override
	public void setEventFrequencies(ProgramPropertyReader programFrequencies, ModulePropertyReader moduleFrequencies) {
		sameSuspiciousSysnumCount = programFrequencies.getCount(ProgramEventFrequencies.SUSPICIOUS_SYSCALL
				+ ssc.sysnum);

		double riskScale = 1.0;
		if (sameSuspiciousSysnumCount > 0)
			riskScale = (1 / (double) sameSuspiciousSysnumCount);
		riskScale = Math.min(0.9, riskScale);
		riskIndex = (int) (riskScale * 1000.0);
	}

	@Override
	public int getRiskIndex() {
		return riskIndex;
	}

	@Override
	public void print(PrintStream out) {
		if (ssc.suspicionRaisingEdge == null) {
			out.format("Suspicious syscall #%d %s", ssc.sysnum, RiskySystemCall.sysnumMap.get(ssc.sysnum).name);
		} else {
			out.format("Suspicious syscall #%d %s. Stack suspicion raised by %s(0x%x) -%d-> %s(0x%x).", ssc.sysnum,
					RiskySystemCall.sysnumMap.get(ssc.sysnum).name,
					ExecutionReport.getModuleName(ssc.suspicionRaisingEdge.getFromNode()),
					ExecutionReport.getId(ssc.suspicionRaisingEdge.getFromNode()),
					ssc.suspicionRaisingEdge.getOrdinal(),
					ExecutionReport.getModuleName(ssc.suspicionRaisingEdge.getToNode()),
					ExecutionReport.getId(ssc.suspicionRaisingEdge.getToNode()));
		}
	}
}
