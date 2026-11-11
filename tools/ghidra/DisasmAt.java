// Pre-analysis helper: disassembles in Thumb mode and creates functions at the addresses given as script arguments.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import java.math.BigInteger;

public class DisasmAt extends GhidraScript {
    @Override
    public void run() throws Exception {
        Register tmode = currentProgram.getLanguage().getRegister("TMode");
        for (String a : getScriptArgs()) {
            long v = Long.parseLong(a, 16) & ~1L;
            Address t = toAddr(v);
            currentProgram.getProgramContext().setValue(tmode, t, t, BigInteger.ONE);
            disassemble(t);
            createFunction(t, "entry_" + a);
        }
    }
}
