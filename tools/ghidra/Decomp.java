// Decompiles the functions at the given addresses and the functions they call (depth 2).
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import java.io.FileWriter;
import java.util.LinkedHashSet;
import java.util.Set;

public class Decomp extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] a = getScriptArgs();
        String out = a[0];
        int depth = Integer.parseInt(a[1]);
        DecompInterface di = new DecompInterface();
        di.openProgram(currentProgram);
        Set<Function> done = new LinkedHashSet<>();
        Set<Function> frontier = new LinkedHashSet<>();
        for (int i = 2; i < a.length; i++) {
            Function f = getFunctionAt(toAddr(a[i]));
            if (f != null) frontier.add(f);
        }
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d <= depth; d++) {
            Set<Function> next = new LinkedHashSet<>();
            for (Function f : frontier) {
                if (!done.add(f)) continue;
                var r = di.decompileFunction(f, 60, monitor);
                sb.append("\n/* depth " + d + ": " + f.getName() + " @ " + f.getEntryPoint() + " */\n");
                sb.append(r.decompileCompleted() ? r.getDecompiledFunction().getC() : "decompile failed\n");
                next.addAll(f.getCalledFunctions(monitor));
            }
            frontier = next;
        }
        try (FileWriter w = new FileWriter(out)) { w.write(sb.toString()); }
        println("wrote " + out + " functions=" + done.size());
    }
}
