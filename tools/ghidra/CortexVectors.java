// Creates functions at every Thumb vector of the table at the image base (pre-analysis helper).
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.Function;
import java.math.BigInteger;

public class CortexVectors extends GhidraScript {
    @Override
    public void run() throws Exception {
        Address base = currentProgram.getMinAddress();
        Register tmode = currentProgram.getLanguage().getRegister("TMode");
        int made = 0;
        for (int i = 1; i < 48; i++) { // word 0 is the initial stack pointer
            long v = currentProgram.getMemory().getInt(base.add(4L * i)) & 0xFFFFFFFFL;
            if ((v & 1) == 0 || v < 0x1000) continue;
            Address target = toAddr(v & ~1L);
            if (!currentProgram.getMemory().contains(target)) continue;
            currentProgram.getProgramContext().setValue(tmode, target, target, BigInteger.ONE);
            disassemble(target);
            Function f = createFunction(target, "vec_" + i);
            if (f != null) made++;
        }
        println("CortexVectors: created " + made + " functions");
    }
}
