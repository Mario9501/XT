package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.cpu.FPU;
import nz.co.electricbolt.xt.cpu.Memory;
import nz.co.electricbolt.xt.cpu.SegOfs;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.Interrupt;
import nz.co.electricbolt.xt.usermode.util.Trace;

/**
 * Handles INT 34h-3Dh floating-point emulation interrupts used by the MS C runtime library.
 *
 * When a program is compiled without a math coprocessor, the linker replaces x87 FP instructions
 * with INT 34h-3Bh sequences. Each interrupt corresponds to an ESC opcode:
 *   INT 34h = D8, INT 35h = D9, ..., INT 3Bh = DF
 *
 * After the INT instruction (CD xx), the modrm byte and optional displacement bytes follow
 * at the return address on the stack. The handler must adjust the return IP to skip past them.
 *
 * INT 3Ch = segment override before FP instruction (ESC byte + modrm + displacement)
 * INT 3Dh = FWAIT (no extra bytes to skip)
 *
 * This implementation performs real x87 FPU arithmetic using Java double precision.
 */
public class FPEmulation {

    // Temporary debug tracing — set to false to disable
    private static final boolean TRACE = true;
    private static int traceCount = 0;

    private void trace(String msg, CPU cpu) {
        if (!TRACE) return;
        if (++traceCount > 10000) {
            if (traceCount == 10001) System.err.println("[FP] ... trace limit reached");
            return;
        }
        FPU fpu = cpu.getFpu();
        System.err.printf("[FP] %s  ST0=%.6g ST1=%.6g TOP=%d SW=%04X%n",
            msg, fpu.getST(0), fpu.getST(1),
            (fpu.getStatusWord() >> 11) & 7,
            fpu.getStatusWord() & 0xFFFF);
    }

    /* ====================================================================== */
    /* Decoded operand from ModRM byte                                        */
    /* ====================================================================== */

    private static class DecodedOperand {
        int mod;       // bits 7-6
        int reg;       // bits 5-3 (opcode extension)
        int rm;        // bits 2-0
        SegOfs addr;   // effective address (null when mod==3, register operand)
        int skipBytes; // total bytes to skip past (modrm + displacement)

        boolean isMemory() {
            return mod != 3;
        }
    }

    /* ====================================================================== */
    /* Stack helpers: return address access                                    */
    /* ====================================================================== */

    private short getReturnCS(final CPU cpu) {
        final short sp = cpu.getReg().SP.getValue();
        return cpu.getMemory().readWord(new SegOfs(cpu.getReg().SS.getValue(), (short) (sp + 2)));
    }

    private short getReturnIP(final CPU cpu) {
        return cpu.getMemory().readWord(new SegOfs(cpu.getReg().SS.getValue(), cpu.getReg().SP.getValue()));
    }

    private void adjustReturnIP(final CPU cpu, final int skipCount) {
        final short sp = cpu.getReg().SP.getValue();
        final short ss = cpu.getReg().SS.getValue();
        final short retIP = cpu.getMemory().readWord(new SegOfs(ss, sp));
        cpu.getMemory().setWord(new SegOfs(ss, sp), (short) (retIP + skipCount));
    }

    /* ====================================================================== */
    /* ModRM decoding (mirrors ModRegRM.effectiveAddress but reads from        */
    /* return address rather than instruction stream)                          */
    /* ====================================================================== */

    private DecodedOperand decodeModRM(final CPU cpu, final short retCS, final short retIP) {
        DecodedOperand op = new DecodedOperand();
        final Memory mem = cpu.getMemory();

        byte modrm = mem.getByte(new SegOfs(retCS, retIP));
        op.mod = (modrm >> 6) & 0x03;
        op.reg = (modrm >> 3) & 0x07;
        op.rm = modrm & 0x07;

        if (op.mod == 3) {
            // Register operand — rm selects ST(i)
            op.addr = null;
            op.skipBytes = 1;
            return op;
        }

        // Memory operand — compute effective address
        int displacement = 0;
        int extraBytes = 0;
        short segment;

        // Check for segment override from CPU state
        var segOverride = cpu.getSegmentOverride();

        if (op.mod == 0 && op.rm == 6) {
            // Direct address: 16-bit displacement, no base register
            byte lo = mem.getByte(new SegOfs(retCS, (short) (retIP + 1)));
            byte hi = mem.getByte(new SegOfs(retCS, (short) (retIP + 2)));
            displacement = (hi & 0xFF) << 8 | (lo & 0xFF);
            extraBytes = 2;
            segment = segOverride != null ? segOverride.getValue() : cpu.getReg().DS.getValue();
        } else {
            // Read displacement based on mod
            if (op.mod == 1) {
                byte disp8 = mem.getByte(new SegOfs(retCS, (short) (retIP + 1)));
                displacement = disp8; // sign-extended
                extraBytes = 1;
            } else if (op.mod == 2) {
                byte lo = mem.getByte(new SegOfs(retCS, (short) (retIP + 1)));
                byte hi = mem.getByte(new SegOfs(retCS, (short) (retIP + 2)));
                displacement = (short) ((hi & 0xFF) << 8 | (lo & 0xFF));
                extraBytes = 2;
            }

            // Add base+index register value
            displacement += switch (op.rm) {
                case 0 -> (cpu.getReg().BX.getValue() & 0xFFFF) + (cpu.getReg().SI.getValue() & 0xFFFF);
                case 1 -> (cpu.getReg().BX.getValue() & 0xFFFF) + (cpu.getReg().DI.getValue() & 0xFFFF);
                case 2 -> (cpu.getReg().BP.getValue() & 0xFFFF) + (cpu.getReg().SI.getValue() & 0xFFFF);
                case 3 -> (cpu.getReg().BP.getValue() & 0xFFFF) + (cpu.getReg().DI.getValue() & 0xFFFF);
                case 4 -> cpu.getReg().SI.getValue() & 0xFFFF;
                case 5 -> cpu.getReg().DI.getValue() & 0xFFFF;
                case 6 -> cpu.getReg().BP.getValue() & 0xFFFF;
                default -> cpu.getReg().BX.getValue() & 0xFFFF; // rm == 7
            };

            // Default segment: SS for BP-based addressing, DS otherwise
            if (segOverride != null) {
                segment = segOverride.getValue();
            } else if (op.rm == 2 || op.rm == 3 || op.rm == 6) {
                segment = cpu.getReg().SS.getValue();
            } else {
                segment = cpu.getReg().DS.getValue();
            }
        }

        op.addr = new SegOfs(segment, (short) (displacement & 0xFFFF));
        op.skipBytes = 1 + extraBytes; // 1 for modrm + displacement bytes
        return op;
    }

    /* ====================================================================== */
    /* INT 34h — ESC D8: float32 memory / ST(i) register arithmetic           */
    /* ====================================================================== */

    // @Interrupt(interrupt = 0x34, description = "FP emulation ESC D8")
    public void fpESC_D8(final CPU cpu, final Trace trace) {
        final short retCS = getReturnCS(cpu);
        final short retIP = getReturnIP(cpu);
        final DecodedOperand op = decodeModRM(cpu, retCS, retIP);
        final FPU fpu = cpu.getFpu();
        final Memory mem = cpu.getMemory();

        double operand;
        if (op.isMemory()) {
            operand = FPU.readFloat32(mem, op.addr);
        } else {
            operand = fpu.getST(op.rm);
        }

        String[] ops = {"FADD","FMUL","FCOM","FCOMP","FSUB","FSUBR","FDIV","FDIVR"};
        trace(String.format("D8 %s %s operand=%.6g", ops[op.reg],
            op.isMemory() ? "m32["+op.addr+"]" : "ST("+op.rm+")", operand), cpu);

        switch (op.reg) {
            case 0 -> fpu.setST(0, fpu.getST(0) + operand);         // FADD
            case 1 -> fpu.setST(0, fpu.getST(0) * operand);         // FMUL
            case 2 -> fpu.compare(fpu.getST(0), operand);            // FCOM
            case 3 -> {                                               // FCOMP
                fpu.compare(fpu.getST(0), operand);
                fpu.pop();
            }
            case 4 -> fpu.setST(0, fpu.getST(0) - operand);         // FSUB
            case 5 -> fpu.setST(0, operand - fpu.getST(0));          // FSUBR
            case 6 -> fpu.setST(0, fpu.getST(0) / operand);         // FDIV
            case 7 -> fpu.setST(0, operand / fpu.getST(0));          // FDIVR
        }

        trace(String.format("D8 %s -> result", ops[op.reg]), cpu);
        adjustReturnIP(cpu, op.skipBytes);
    }

    /* ====================================================================== */
    /* INT 35h — ESC D9: loads, stores, control, constants, transcendentals   */
    /* ====================================================================== */

    // @Interrupt(interrupt = 0x35, description = "FP emulation ESC D9")
    public void fpESC_D9(final CPU cpu, final Trace trace) {
        final short retCS = getReturnCS(cpu);
        final short retIP = getReturnIP(cpu);
        final DecodedOperand op = decodeModRM(cpu, retCS, retIP);
        final FPU fpu = cpu.getFpu();
        final Memory mem = cpu.getMemory();

        if (op.isMemory()) {
            String[] ops = {"FLD","?","FST","FSTP","FLDENV","FLDCW","FSTENV","FSTCW"};
            if (op.reg == 0) {
                double val = FPU.readFloat32(mem, op.addr);
                trace(String.format("D9 %s m32[%s] val=%.6g", ops[op.reg], op.addr, val), cpu);
            } else {
                trace(String.format("D9 %s m[%s]", ops[op.reg], op.addr), cpu);
            }
            switch (op.reg) {
                case 0 -> fpu.push(FPU.readFloat32(mem, op.addr));       // FLD m32
                case 2 -> FPU.writeFloat32(mem, op.addr, fpu.getST(0));  // FST m32
                case 3 -> FPU.writeFloat32(mem, op.addr, fpu.pop());     // FSTP m32
                case 4 -> loadEnv(cpu, fpu, mem, op.addr);               // FLDENV
                case 5 -> fpu.setControlWord(mem.getWord(op.addr));      // FLDCW m16
                case 6 -> storeEnv(cpu, fpu, mem, op.addr);              // FSTENV
                case 7 -> mem.setWord(op.addr, fpu.getControlWord());    // FSTCW m16
            }
        } else {
            String[] rmops4 = {"FCHS","FABS","?","?","FTST","FXAM","?","?"};
            String[] rmops5 = {"FLD1","FLDL2T","FLDL2E","FLDPI","FLDLG2","FLDLN2","FLDZ","?"};
            if (op.reg == 0) trace(String.format("D9 FLD ST(%d)", op.rm), cpu);
            else if (op.reg == 1) trace(String.format("D9 FXCH ST(%d)", op.rm), cpu);
            else if (op.reg == 4) trace(String.format("D9 %s", rmops4[op.rm]), cpu);
            else if (op.reg == 5) trace(String.format("D9 %s", rmops5[op.rm]), cpu);
            else if (op.reg == 6) trace(String.format("D9 reg6 rm=%d", op.rm), cpu);
            else if (op.reg == 7) trace(String.format("D9 reg7 rm=%d", op.rm), cpu);

            switch (op.reg) {
                case 0 -> fpu.push(fpu.getST(op.rm));                   // FLD ST(i)
                case 1 -> {                                              // FXCH ST(i)
                    double tmp = fpu.getST(0);
                    fpu.setST(0, fpu.getST(op.rm));
                    fpu.setST(op.rm, tmp);
                }
                case 2 -> {}                                             // FNOP (rm=0)
                case 4 -> execD9_reg4(fpu, op.rm);                      // FCHS/FABS/FTST/FXAM
                case 5 -> execD9_reg5_constants(fpu, op.rm);            // FLD1/FLDL2T/etc.
                case 6 -> execD9_reg6_transcendentals(fpu, op.rm);      // F2XM1/FYL2X/etc.
                case 7 -> execD9_reg7(fpu, op.rm);                      // FPREM/FSQRT/etc.
            }
        }

        trace("D9 -> done", cpu);
        adjustReturnIP(cpu, op.skipBytes);
    }

    private void execD9_reg4(final FPU fpu, final int rm) {
        switch (rm) {
            case 0 -> fpu.setST(0, -fpu.getST(0));      // FCHS
            case 1 -> fpu.setST(0, Math.abs(fpu.getST(0))); // FABS
            case 4 -> fpu.compare(fpu.getST(0), 0.0);   // FTST
            case 5 -> {                                   // FXAM
                double val = fpu.getST(0);
                fpu.clearC1();
                if (val < 0) fpu.setC1();
                if (Double.isNaN(val)) {
                    fpu.setCC(false, false, true);        // C3=0,C2=0,C0=1 NaN
                } else if (Double.isInfinite(val)) {
                    fpu.setCC(false, true, true);         // C3=0,C2=1,C0=1 Infinity
                } else if (val == 0.0) {
                    fpu.setCC(true, false, false);        // C3=1,C2=0,C0=0 Zero
                } else {
                    fpu.setCC(false, true, false);        // C3=0,C2=1,C0=0 Normal
                }
            }
        }
    }

    private void execD9_reg5_constants(final FPU fpu, final int rm) {
        switch (rm) {
            case 0 -> fpu.push(1.0);                                    // FLD1
            case 1 -> fpu.push(Math.log(10.0) / Math.log(2.0));   // FLDL2T = log2(10)
            case 2 -> fpu.push(1.0 / Math.log(2.0));                   // FLDL2E = log2(e)
            case 3 -> fpu.push(Math.PI);                                // FLDPI
            case 4 -> fpu.push(Math.log10(2.0));                       // FLDLG2 = log10(2)
            case 5 -> fpu.push(Math.log(2.0));                         // FLDLN2 = ln(2)
            case 6 -> fpu.push(0.0);                                   // FLDZ
        }
    }

    private void execD9_reg6_transcendentals(final FPU fpu, final int rm) {
        switch (rm) {
            case 0 -> fpu.setST(0, Math.pow(2.0, fpu.getST(0)) - 1.0); // F2XM1
            case 1 -> {                                                  // FYL2X: ST(1) * log2(ST(0))
                double x = fpu.pop();
                fpu.setST(0, fpu.getST(0) * (Math.log(x) / Math.log(2.0)));
            }
            case 2 -> {                                                  // FPTAN: tan(ST(0)), push 1.0
                fpu.setST(0, Math.tan(fpu.getST(0)));
                fpu.push(1.0);
            }
            case 3 -> {                                                  // FPATAN: ST(1) = atan2(ST(1), ST(0))
                double x = fpu.pop();
                fpu.setST(0, Math.atan2(fpu.getST(0), x));
            }
            case 4 -> {                                                  // FXTRACT: extract exponent and significand
                double val = fpu.getST(0);
                if (val == 0.0) {
                    fpu.setST(0, Double.NEGATIVE_INFINITY);
                    fpu.push(0.0);
                } else {
                    int exp = Math.getExponent(val);
                    double sig = val / Math.pow(2.0, exp);
                    fpu.setST(0, exp);
                    fpu.push(sig);
                }
            }
            case 6 -> fpu.decSTP();                                     // FDECSTP
            case 7 -> fpu.incSTP();                                     // FINCSTP
        }
    }

    private void execD9_reg7(final FPU fpu, final int rm) {
        switch (rm) {
            case 0 -> {                                                  // FPREM: ST(0) = ST(0) mod ST(1)
                double dividend = fpu.getST(0);
                double divisor = fpu.getST(1);
                fpu.setST(0, Math.IEEEremainder(dividend, divisor));
                // Approximate FPREM behavior: set C2=0 (reduction complete)
                fpu.setCC(false, false, false);
            }
            case 1 -> {                                                  // FYL2XP1: ST(1) * log2(ST(0) + 1)
                double x = fpu.pop();
                fpu.setST(0, fpu.getST(0) * (Math.log1p(x) / Math.log(2.0)));
            }
            case 2 -> fpu.setST(0, Math.sqrt(fpu.getST(0)));           // FSQRT
            case 4 -> fpu.setST(0, Math.rint(fpu.getST(0)));           // FRNDINT
            case 5 -> {                                                  // FSCALE: ST(0) = ST(0) * 2^trunc(ST(1))
                int scale = (int) fpu.getST(1);
                fpu.setST(0, fpu.getST(0) * Math.pow(2.0, scale));
            }
        }
    }

    /* ====================================================================== */
    /* INT 36h — ESC DA: 32-bit integer memory arithmetic                     */
    /* ====================================================================== */

    // @Interrupt(interrupt = 0x36, description = "FP emulation ESC DA")
    public void fpESC_DA(final CPU cpu, final Trace trace) {
        final short retCS = getReturnCS(cpu);
        final short retIP = getReturnIP(cpu);
        final DecodedOperand op = decodeModRM(cpu, retCS, retIP);
        final FPU fpu = cpu.getFpu();
        final Memory mem = cpu.getMemory();

        if (op.isMemory()) {
            double operand = FPU.readInt32(mem, op.addr);
            String[] ops = {"FIADD","FIMUL","FICOM","FICOMP","FISUB","FISUBR","FIDIV","FIDIVR"};
            trace(String.format("DA %s m32int[%s] val=%.6g", ops[op.reg], op.addr, operand), cpu);
            switch (op.reg) {
                case 0 -> fpu.setST(0, fpu.getST(0) + operand);     // FIADD
                case 1 -> fpu.setST(0, fpu.getST(0) * operand);     // FIMUL
                case 2 -> fpu.compare(fpu.getST(0), operand);        // FICOM
                case 3 -> {                                           // FICOMP
                    fpu.compare(fpu.getST(0), operand);
                    fpu.pop();
                }
                case 4 -> fpu.setST(0, fpu.getST(0) - operand);     // FISUB
                case 5 -> fpu.setST(0, operand - fpu.getST(0));      // FISUBR
                case 6 -> fpu.setST(0, fpu.getST(0) / operand);     // FIDIV
                case 7 -> fpu.setST(0, operand / fpu.getST(0));      // FIDIVR
            }
        }

        adjustReturnIP(cpu, op.skipBytes);
    }

    /* ====================================================================== */
    /* INT 37h — ESC DB: 32-bit int load/store, 80-bit, FINIT/FCLEX          */
    /* ====================================================================== */

    // @Interrupt(interrupt = 0x37, description = "FP emulation ESC DB")
    public void fpESC_DB(final CPU cpu, final Trace trace) {
        final short retCS = getReturnCS(cpu);
        final short retIP = getReturnIP(cpu);
        final DecodedOperand op = decodeModRM(cpu, retCS, retIP);
        final FPU fpu = cpu.getFpu();
        final Memory mem = cpu.getMemory();

        if (op.isMemory()) {
            String[] ops = {"FILD32","?","FIST32","FISTP32","?","FLD80","?","FSTP80"};
            if (op.reg == 0) {
                double val = FPU.readInt32(mem, op.addr);
                trace(String.format("DB %s m[%s] val=%.6g", ops[op.reg], op.addr, val), cpu);
            } else if (op.reg == 5 && TRACE) {
                // Dump raw 10 bytes for FLD80
                StringBuilder raw = new StringBuilder();
                SegOfs dumpAddr = op.addr.copy();
                for (int i = 0; i < 10; i++) {
                    if (i > 0) raw.append(' ');
                    raw.append(String.format("%02X", mem.getByte(dumpAddr) & 0xFF));
                    dumpAddr.increment();
                }
                double val = FPU.readFloat80(mem, op.addr);
                trace(String.format("DB %s m[%s] raw=[%s] val=%.17g", ops[op.reg], op.addr, raw.toString(), val), cpu);
            } else {
                trace(String.format("DB %s m[%s]", ops[op.reg], op.addr), cpu);
            }
            switch (op.reg) {
                case 0 -> fpu.push(FPU.readInt32(mem, op.addr));         // FILD m32int
                case 2 -> FPU.writeInt32(mem, op.addr, fpu.getST(0));    // FIST m32int
                case 3 -> FPU.writeInt32(mem, op.addr, fpu.pop());       // FISTP m32int
                case 5 -> fpu.push(FPU.readFloat80(mem, op.addr));       // FLD m80
                case 7 -> FPU.writeFloat80(mem, op.addr, fpu.pop());     // FSTP m80
            }
        } else {
            if (op.reg == 4 && op.rm == 3) trace("DB FINIT", cpu);
            else if (op.reg == 4 && op.rm == 2) trace("DB FCLEX", cpu);
            else trace(String.format("DB reg=%d rm=%d", op.reg, op.rm), cpu);
            switch (op.reg) {
                case 4 -> {
                    if (op.rm == 3) {
                        fpu.init();                                      // FINIT
                    } else if (op.rm == 2) {
                        fpu.clearExceptions();                           // FCLEX
                    }
                }
            }
        }

        trace("DB -> done", cpu);
        adjustReturnIP(cpu, op.skipBytes);
    }

    /* ====================================================================== */
    /* INT 38h — ESC DC: float64 memory / ST(i) reversed register arithmetic  */
    /* ====================================================================== */

    // @Interrupt(interrupt = 0x38, description = "FP emulation ESC DC")
    public void fpESC_DC(final CPU cpu, final Trace trace) {
        final short retCS = getReturnCS(cpu);
        final short retIP = getReturnIP(cpu);
        final DecodedOperand op = decodeModRM(cpu, retCS, retIP);
        final FPU fpu = cpu.getFpu();
        final Memory mem = cpu.getMemory();

        if (op.isMemory()) {
            double operand = FPU.readFloat64(mem, op.addr);
            String[] ops = {"FADD","FMUL","FCOM","FCOMP","FSUB","FSUBR","FDIV","FDIVR"};
            trace(String.format("DC %s m64[%s] val=%.6g", ops[op.reg], op.addr, operand), cpu);
            switch (op.reg) {
                case 0 -> fpu.setST(0, fpu.getST(0) + operand);     // FADD m64
                case 1 -> fpu.setST(0, fpu.getST(0) * operand);     // FMUL m64
                case 2 -> fpu.compare(fpu.getST(0), operand);        // FCOM m64
                case 3 -> {                                           // FCOMP m64
                    fpu.compare(fpu.getST(0), operand);
                    fpu.pop();
                }
                case 4 -> fpu.setST(0, fpu.getST(0) - operand);     // FSUB m64
                case 5 -> fpu.setST(0, operand - fpu.getST(0));      // FSUBR m64
                case 6 -> fpu.setST(0, fpu.getST(0) / operand);     // FDIV m64
                case 7 -> fpu.setST(0, operand / fpu.getST(0));      // FDIVR m64
            }
        } else {
            // Register forms: destination is ST(rm), source is ST(0)
            // Note: DC register forms reverse SUB/SUBR and DIV/DIVR directions
            double st0 = fpu.getST(0);
            double sti = fpu.getST(op.rm);
            switch (op.reg) {
                case 0 -> fpu.setST(op.rm, sti + st0);              // FADD ST(i), ST(0)
                case 1 -> fpu.setST(op.rm, sti * st0);              // FMUL ST(i), ST(0)
                case 4 -> fpu.setST(op.rm, st0 - sti);              // FSUBR ST(i), ST(0)
                case 5 -> fpu.setST(op.rm, sti - st0);              // FSUB ST(i), ST(0)
                case 6 -> fpu.setST(op.rm, st0 / sti);              // FDIVR ST(i), ST(0)
                case 7 -> fpu.setST(op.rm, sti / st0);              // FDIV ST(i), ST(0)
            }
        }

        adjustReturnIP(cpu, op.skipBytes);
    }

    /* ====================================================================== */
    /* INT 39h — ESC DD: float64 load/store, FSTSW, FFREE                    */
    /* ====================================================================== */

    // @Interrupt(interrupt = 0x39, description = "FP emulation ESC DD")
    public void fpESC_DD(final CPU cpu, final Trace trace) {
        final short retCS = getReturnCS(cpu);
        final short retIP = getReturnIP(cpu);
        final DecodedOperand op = decodeModRM(cpu, retCS, retIP);
        final FPU fpu = cpu.getFpu();
        final Memory mem = cpu.getMemory();

        if (op.isMemory()) {
            if (op.reg == 0) {
                double val = FPU.readFloat64(mem, op.addr);
                trace(String.format("DD FLD m64[%s] val=%.6g", op.addr, val), cpu);
            } else {
                String[] ops = {"FLD","?","FST","FSTP","?","?","?","FSTSW"};
                trace(String.format("DD %s m[%s]", ops[op.reg], op.addr), cpu);
            }
            switch (op.reg) {
                case 0 -> fpu.push(FPU.readFloat64(mem, op.addr));       // FLD m64
                case 2 -> FPU.writeFloat64(mem, op.addr, fpu.getST(0)); // FST m64
                case 3 -> FPU.writeFloat64(mem, op.addr, fpu.pop());    // FSTP m64
                case 7 -> mem.setWord(op.addr, fpu.getStatusWord());    // FSTSW m16
            }
        } else {
            switch (op.reg) {
                case 0 -> fpu.free(op.rm);                              // FFREE ST(i)
                case 2 -> fpu.setST(op.rm, fpu.getST(0));              // FST ST(i)
                case 3 -> {                                              // FSTP ST(i)
                    // Must store before pop: setST uses current TOP, pop advances TOP
                    double val = fpu.getST(0);
                    fpu.setST(op.rm, val);
                    fpu.pop();
                }
            }
        }

        adjustReturnIP(cpu, op.skipBytes);
    }

    /* ====================================================================== */
    /* INT 3Ah — ESC DE: 16-bit int memory / ST(i) pop arithmetic            */
    /* ====================================================================== */

    // @Interrupt(interrupt = 0x3A, description = "FP emulation ESC DE")
    public void fpESC_DE(final CPU cpu, final Trace trace) {
        final short retCS = getReturnCS(cpu);
        final short retIP = getReturnIP(cpu);
        final DecodedOperand op = decodeModRM(cpu, retCS, retIP);
        final FPU fpu = cpu.getFpu();
        final Memory mem = cpu.getMemory();

        if (op.isMemory()) {
            double operand = FPU.readInt16(mem, op.addr);
            String[] ops = {"FIADD","FIMUL","FICOM","FICOMP","FISUB","FISUBR","FIDIV","FIDIVR"};
            trace(String.format("DE %s m16int[%s] val=%.6g", ops[op.reg], op.addr, operand), cpu);
            switch (op.reg) {
                case 0 -> fpu.setST(0, fpu.getST(0) + operand);     // FIADD m16int
                case 1 -> fpu.setST(0, fpu.getST(0) * operand);     // FIMUL m16int
                case 2 -> fpu.compare(fpu.getST(0), operand);        // FICOM m16int
                case 3 -> {                                           // FICOMP m16int
                    fpu.compare(fpu.getST(0), operand);
                    fpu.pop();
                }
                case 4 -> fpu.setST(0, fpu.getST(0) - operand);     // FISUB m16int
                case 5 -> fpu.setST(0, operand - fpu.getST(0));      // FISUBR m16int
                case 6 -> fpu.setST(0, fpu.getST(0) / operand);     // FIDIV m16int
                case 7 -> fpu.setST(0, operand / fpu.getST(0));      // FIDIVR m16int
            }
        } else {
            // Register forms: pop variants
            double st0 = fpu.getST(0);
            double sti = fpu.getST(op.rm);
            String[] ops = {"FADDP","FMULP","?","FCOMPP","FSUBRP","FSUBP","FDIVRP","FDIVP"};
            trace(String.format("DE %s ST(%d),ST(0) st0=%.6g sti=%.6g", ops[op.reg], op.rm, st0, sti), cpu);
            switch (op.reg) {
                case 0 -> { fpu.setST(op.rm, sti + st0); fpu.pop(); }  // FADDP ST(i), ST(0)
                case 1 -> { fpu.setST(op.rm, sti * st0); fpu.pop(); }  // FMULP ST(i), ST(0)
                case 3 -> {                                              // FCOMPP (DE D9)
                    fpu.compare(fpu.getST(0), fpu.getST(1));
                    fpu.pop();
                    fpu.pop();
                }
                case 4 -> { fpu.setST(op.rm, st0 - sti); fpu.pop(); }  // FSUBRP ST(i), ST(0)
                case 5 -> { fpu.setST(op.rm, sti - st0); fpu.pop(); }  // FSUBP ST(i), ST(0)
                case 6 -> { fpu.setST(op.rm, st0 / sti); fpu.pop(); }  // FDIVRP ST(i), ST(0)
                case 7 -> { fpu.setST(op.rm, sti / st0); fpu.pop(); }  // FDIVP ST(i), ST(0)
            }
            trace(String.format("DE %s -> done", ops[op.reg]), cpu);
        }

        adjustReturnIP(cpu, op.skipBytes);
    }

    /* ====================================================================== */
    /* INT 3Bh — ESC DF: 16-bit int load/store, 64-bit int, FNSTSW AX       */
    /* ====================================================================== */

    // @Interrupt(interrupt = 0x3B, description = "FP emulation ESC DF")
    public void fpESC_DF(final CPU cpu, final Trace trace) {
        final short retCS = getReturnCS(cpu);
        final short retIP = getReturnIP(cpu);
        final DecodedOperand op = decodeModRM(cpu, retCS, retIP);
        final FPU fpu = cpu.getFpu();
        final Memory mem = cpu.getMemory();

        if (op.isMemory()) {
            String[] ops = {"FILD16","?","FIST16","FISTP16","?","FILD64","?","FISTP64"};
            if (op.reg == 0 || op.reg == 5) {
                double val = op.reg == 0 ? FPU.readInt16(mem, op.addr) : FPU.readInt64(mem, op.addr);
                trace(String.format("DF %s m[%s] val=%.6g", ops[op.reg], op.addr, val), cpu);
            } else {
                trace(String.format("DF %s m[%s]", ops[op.reg], op.addr), cpu);
            }
            switch (op.reg) {
                case 0 -> fpu.push(FPU.readInt16(mem, op.addr));         // FILD m16int
                case 2 -> FPU.writeInt16(mem, op.addr, fpu.getST(0));    // FIST m16int
                case 3 -> FPU.writeInt16(mem, op.addr, fpu.pop());       // FISTP m16int
                case 5 -> fpu.push(FPU.readInt64(mem, op.addr));         // FILD m64int
                case 7 -> FPU.writeInt64(mem, op.addr, fpu.pop());       // FISTP m64int
            }
        } else {
            // Register form: DF E0 = FNSTSW AX
            if (op.reg == 4 && op.rm == 0) {
                trace(String.format("DF FNSTSW AX sw=%04X", fpu.getStatusWord() & 0xFFFF), cpu);
                cpu.getReg().AX.setValue(fpu.getStatusWord());           // FNSTSW AX
            } else {
                trace(String.format("DF reg=%d rm=%d", op.reg, op.rm), cpu);
            }
        }

        adjustReturnIP(cpu, op.skipBytes);
    }

    /* ====================================================================== */
    /* INT 3Ch — Segment override + FP instruction                            */
    /* ====================================================================== */

    // INT 3Ch handler removed — let IVT handler (from FP library built into programs) run instead.
    // Our Java handlers for INT 34h-3Bh may be invoked by the IVT handler if it issues FP INTs.
    // @Interrupt(interrupt = 0x3C, description = "FP emulation segment override")
    public void fpSegOverride(final CPU cpu, final Trace trace) {
        final short retCS = getReturnCS(cpu);
        final short retIP = getReturnIP(cpu);
        final Memory mem = cpu.getMemory();

        // Byte at return IP is the ESC opcode (D8-DF)
        int escByte = mem.getByte(new SegOfs(retCS, retIP)) & 0xFF;

        // ModRM follows at retIP+1
        final DecodedOperand op = decodeModRM(cpu, retCS, (short) (retIP + 1));
        final FPU fpu = cpu.getFpu();

        // Trace: dump raw bytes and segment override state
        if (TRACE) {
            var segOvr = cpu.getSegmentOverride();
            String segStr = segOvr != null ? String.format("%04X", segOvr.getValue() & 0xFFFF) : "null";
            // Also dump 4 bytes BEFORE retIP to see the INT instruction
            StringBuilder pre = new StringBuilder();
            for (int i = -4; i < 0; i++) {
                if (i > -4) pre.append(' ');
                pre.append(String.format("%02X", mem.getByte(new SegOfs(retCS, (short)(retIP + i))) & 0xFF));
            }
            // Dump first 8 bytes at return address for debugging
            StringBuilder raw = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i > 0) raw.append(' ');
                raw.append(String.format("%02X", mem.getByte(new SegOfs(retCS, (short)(retIP + i))) & 0xFF));
            }
            trace(String.format("3C retCS:IP=%04X:%04X segOvr=%s esc=%02X pre=[%s] raw=[%s]",
                retCS & 0xFFFF, retIP & 0xFFFF,
                segStr, escByte, pre.toString(), raw.toString()), cpu);
        }

        // Dispatch based on ESC opcode
        if (op.isMemory()) {
            switch (escByte) {
                case 0xD8 -> execD8_memory(fpu, mem, op);
                case 0xD9 -> execD9_memory(cpu, fpu, mem, op);
                case 0xDA -> execDA_memory(fpu, mem, op);
                case 0xDB -> execDB_memory(fpu, mem, op);
                case 0xDC -> execDC_memory(fpu, mem, op);
                case 0xDD -> execDD_memory(cpu, fpu, mem, op);
                case 0xDE -> execDE_memory(fpu, mem, op);
                case 0xDF -> execDF_memory(cpu, fpu, mem, op);
            }
        }
        // Register forms shouldn't appear with segment override, but skip bytes regardless

        trace("3C -> done", cpu);
        adjustReturnIP(cpu, 1 + op.skipBytes); // 1 for ESC byte + modrm + displacement
    }

    /* ====================================================================== */
    /* INT 3Dh — FWAIT (no-op)                                                */
    /* ====================================================================== */

    // @Interrupt(interrupt = 0x3D, description = "FP emulation FWAIT")
    public void fpFWAIT(final CPU cpu, final Trace trace) {
        // FWAIT has no operand bytes after the INT instruction.
    }

    /* ====================================================================== */
    /* Segment override dispatch helpers (memory-only operations)              */
    /* ====================================================================== */

    private void execD8_memory(final FPU fpu, final Memory mem, final DecodedOperand op) {
        double operand = FPU.readFloat32(mem, op.addr);
        switch (op.reg) {
            case 0 -> fpu.setST(0, fpu.getST(0) + operand);
            case 1 -> fpu.setST(0, fpu.getST(0) * operand);
            case 2 -> fpu.compare(fpu.getST(0), operand);
            case 3 -> { fpu.compare(fpu.getST(0), operand); fpu.pop(); }
            case 4 -> fpu.setST(0, fpu.getST(0) - operand);
            case 5 -> fpu.setST(0, operand - fpu.getST(0));
            case 6 -> fpu.setST(0, fpu.getST(0) / operand);
            case 7 -> fpu.setST(0, operand / fpu.getST(0));
        }
    }

    private void execD9_memory(final CPU cpu, final FPU fpu, final Memory mem, final DecodedOperand op) {
        switch (op.reg) {
            case 0 -> fpu.push(FPU.readFloat32(mem, op.addr));
            case 2 -> FPU.writeFloat32(mem, op.addr, fpu.getST(0));
            case 3 -> FPU.writeFloat32(mem, op.addr, fpu.pop());
            case 4 -> loadEnv(cpu, fpu, mem, op.addr);
            case 5 -> fpu.setControlWord(mem.getWord(op.addr));
            case 6 -> storeEnv(cpu, fpu, mem, op.addr);
            case 7 -> mem.setWord(op.addr, fpu.getControlWord());
        }
    }

    private void execDA_memory(final FPU fpu, final Memory mem, final DecodedOperand op) {
        double operand = FPU.readInt32(mem, op.addr);
        switch (op.reg) {
            case 0 -> fpu.setST(0, fpu.getST(0) + operand);
            case 1 -> fpu.setST(0, fpu.getST(0) * operand);
            case 2 -> fpu.compare(fpu.getST(0), operand);
            case 3 -> { fpu.compare(fpu.getST(0), operand); fpu.pop(); }
            case 4 -> fpu.setST(0, fpu.getST(0) - operand);
            case 5 -> fpu.setST(0, operand - fpu.getST(0));
            case 6 -> fpu.setST(0, fpu.getST(0) / operand);
            case 7 -> fpu.setST(0, operand / fpu.getST(0));
        }
    }

    private void execDB_memory(final FPU fpu, final Memory mem, final DecodedOperand op) {
        switch (op.reg) {
            case 0 -> fpu.push(FPU.readInt32(mem, op.addr));
            case 2 -> FPU.writeInt32(mem, op.addr, fpu.getST(0));
            case 3 -> FPU.writeInt32(mem, op.addr, fpu.pop());
            case 5 -> fpu.push(FPU.readFloat80(mem, op.addr));
            case 7 -> FPU.writeFloat80(mem, op.addr, fpu.pop());
        }
    }

    private void execDC_memory(final FPU fpu, final Memory mem, final DecodedOperand op) {
        double operand = FPU.readFloat64(mem, op.addr);
        switch (op.reg) {
            case 0 -> fpu.setST(0, fpu.getST(0) + operand);
            case 1 -> fpu.setST(0, fpu.getST(0) * operand);
            case 2 -> fpu.compare(fpu.getST(0), operand);
            case 3 -> { fpu.compare(fpu.getST(0), operand); fpu.pop(); }
            case 4 -> fpu.setST(0, fpu.getST(0) - operand);
            case 5 -> fpu.setST(0, operand - fpu.getST(0));
            case 6 -> fpu.setST(0, fpu.getST(0) / operand);
            case 7 -> fpu.setST(0, operand / fpu.getST(0));
        }
    }

    private void execDD_memory(final CPU cpu, final FPU fpu, final Memory mem, final DecodedOperand op) {
        switch (op.reg) {
            case 0 -> fpu.push(FPU.readFloat64(mem, op.addr));
            case 2 -> FPU.writeFloat64(mem, op.addr, fpu.getST(0));
            case 3 -> FPU.writeFloat64(mem, op.addr, fpu.pop());
            case 7 -> mem.setWord(op.addr, fpu.getStatusWord());
        }
    }

    private void execDE_memory(final FPU fpu, final Memory mem, final DecodedOperand op) {
        double operand = FPU.readInt16(mem, op.addr);
        switch (op.reg) {
            case 0 -> fpu.setST(0, fpu.getST(0) + operand);
            case 1 -> fpu.setST(0, fpu.getST(0) * operand);
            case 2 -> fpu.compare(fpu.getST(0), operand);
            case 3 -> { fpu.compare(fpu.getST(0), operand); fpu.pop(); }
            case 4 -> fpu.setST(0, fpu.getST(0) - operand);
            case 5 -> fpu.setST(0, operand - fpu.getST(0));
            case 6 -> fpu.setST(0, fpu.getST(0) / operand);
            case 7 -> fpu.setST(0, operand / fpu.getST(0));
        }
    }

    private void execDF_memory(final CPU cpu, final FPU fpu, final Memory mem, final DecodedOperand op) {
        switch (op.reg) {
            case 0 -> fpu.push(FPU.readInt16(mem, op.addr));
            case 2 -> FPU.writeInt16(mem, op.addr, fpu.getST(0));
            case 3 -> FPU.writeInt16(mem, op.addr, fpu.pop());
            case 5 -> fpu.push(FPU.readInt64(mem, op.addr));
            case 7 -> FPU.writeInt64(mem, op.addr, fpu.pop());
        }
    }

    /* ====================================================================== */
    /* FLDENV / FSTENV — minimal 14-byte FPU environment                      */
    /* ====================================================================== */

    /**
     * Store 14-byte FPU environment: control word, status word, tag word,
     * IP offset, IP selector, operand offset, operand selector.
     */
    private void storeEnv(final CPU cpu, final FPU fpu, final Memory mem, SegOfs addr) {
        addr = addr.copy();
        mem.setWord(addr, fpu.getControlWord());
        addr.addOffset((short) 2);
        mem.setWord(addr, fpu.getStatusWord());
        addr.addOffset((short) 2);
        mem.setWord(addr, fpu.getTagWord());
        addr.addOffset((short) 2);
        mem.setWord(addr, (short) 0); // IP offset
        addr.addOffset((short) 2);
        mem.setWord(addr, (short) 0); // IP selector
        addr.addOffset((short) 2);
        mem.setWord(addr, (short) 0); // operand offset
        addr.addOffset((short) 2);
        mem.setWord(addr, (short) 0); // operand selector
    }

    /**
     * Load 14-byte FPU environment from memory.
     */
    private void loadEnv(final CPU cpu, final FPU fpu, final Memory mem, SegOfs addr) {
        addr = addr.copy();
        fpu.setControlWord(mem.getWord(addr));
        addr.addOffset((short) 2);
        fpu.setStatusWord(mem.getWord(addr));
        addr.addOffset((short) 2);
        fpu.setTagWord(mem.getWord(addr));
        // Remaining 8 bytes (IP/operand pointers) are ignored
    }
}
