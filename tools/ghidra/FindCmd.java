// Finds Thumb instructions that compare/load the real-time command ids 0x69 / 0x6A and decompiles their functions.
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.scalar.Scalar;
import java.io.FileWriter;
import java.util.LinkedHashSet;
import java.util.Set;

public class FindCmd extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] a = getScriptArgs();
        String out = a.length > 0 ? a[0] : "/tmp/findcmd.txt";
        int functions = currentProgram.getFunctionManager().getFunctionCount();
        println("functions: " + functions);
        Set<Function> hits = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder("functions=" + functions + "\n");
        InstructionIterator it = currentProgram.getListing().getInstructions(true);
        while (it.hasNext()) {
            Instruction ins = it.next();
            String m = ins.getMnemonicString().toLowerCase();
            if (!(m.startsWith("cmp") || m.startsWith("mov") || m.startsWith("sub"))) continue;
            for (int i = 0; i < ins.getNumOperands(); i++) {
                for (Object o : ins.getOpObjects(i)) {
                    if (o instanceof Scalar) {
                        long v = ((Scalar) o).getUnsignedValue();
                        if (v == 0x69 || v == 0x6a) {
                            Function f = getFunctionContaining(ins.getAddress());
                            sb.append(ins.getAddress() + "  " + ins + "  in " + (f == null ? "?" : f.getName() + "@" + f.getEntryPoint()) + "\n");
                            if (f != null) hits.add(f);
                        }
                    }
                }
            }
        }
        DecompInterface di = new DecompInterface();
        di.openProgram(currentProgram);
        sb.append("\n=== decompiled hit functions ===\n");
        int n = 0;
        for (Function f : hits) {
            if (n++ > 40) break;
            DecompileResults r = di.decompileFunction(f, 60, monitor);
            sb.append("\n/* " + f.getName() + " @ " + f.getEntryPoint() + " */\n");
            sb.append(r.decompileCompleted() ? r.getDecompiledFunction().getC() : "decompile failed\n");
        }
        try (FileWriter w = new FileWriter(out)) { w.write(sb.toString()); }
        println("wrote " + out + " hits=" + hits.size());
    }
}
