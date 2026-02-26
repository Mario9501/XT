package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.CPU;
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
 * This is a stub implementation that correctly skips encoded FP bytes but does not
 * actually perform floating-point operations.
 */
public class FPEmulation {

    /**
     * Calculates how many bytes to skip based on the modrm byte at seg:ofs.
     * Returns 1 (modrm) + displacement size (0, 1, or 2 bytes).
     */
    private int modrmSkipBytes(final CPU cpu, final short seg, final short ofs) {
        final byte modrm = cpu.getMemory().readByte(new SegOfs(seg, ofs));
        final int mod = (modrm >> 6) & 0x03;
        final int rm = modrm & 0x07;

        return switch (mod) {
            case 0 -> (rm == 6) ? 3 : 1;   // no disp (except rm=110 -> 2-byte disp)
            case 1 -> 2;                     // 1-byte displacement
            case 2 -> 3;                     // 2-byte displacement
            case 3 -> 1;                     // register operand, no displacement
            default -> 1;
        };
    }

    /**
     * Adjusts the return IP on the stack to skip past encoded FP instruction bytes.
     * Stack layout at SP: IP (return), CS, FLAGS.
     */
    private void adjustReturnIP(final CPU cpu, final int skipCount) {
        final short sp = cpu.getReg().SP.getValue();
        final short ss = cpu.getReg().SS.getValue();
        final short retIP = cpu.getMemory().readWord(new SegOfs(ss, sp));
        cpu.getMemory().setWord(new SegOfs(ss, sp), (short) (retIP + skipCount));
    }

    /**
     * Gets the return CS:IP from the stack without modifying it.
     */
    private short getReturnCS(final CPU cpu) {
        final short sp = cpu.getReg().SP.getValue();
        return cpu.getMemory().readWord(new SegOfs(cpu.getReg().SS.getValue(), (short) (sp + 2)));
    }

    private short getReturnIP(final CPU cpu) {
        return cpu.getMemory().readWord(new SegOfs(cpu.getReg().SS.getValue(), cpu.getReg().SP.getValue()));
    }

    // INT 34h-3Bh: ESC D8-DF instructions. Skip modrm + displacement bytes.
    @Interrupt(interrupt = 0x34, description = "FP emulation ESC D8")
    public void fpESC_D8(final CPU cpu, final Trace trace) {
        final int skip = modrmSkipBytes(cpu, getReturnCS(cpu), getReturnIP(cpu));
        adjustReturnIP(cpu, skip);
    }

    @Interrupt(interrupt = 0x35, description = "FP emulation ESC D9")
    public void fpESC_D9(final CPU cpu, final Trace trace) {
        final int skip = modrmSkipBytes(cpu, getReturnCS(cpu), getReturnIP(cpu));
        adjustReturnIP(cpu, skip);
    }

    @Interrupt(interrupt = 0x36, description = "FP emulation ESC DA")
    public void fpESC_DA(final CPU cpu, final Trace trace) {
        final int skip = modrmSkipBytes(cpu, getReturnCS(cpu), getReturnIP(cpu));
        adjustReturnIP(cpu, skip);
    }

    @Interrupt(interrupt = 0x37, description = "FP emulation ESC DB")
    public void fpESC_DB(final CPU cpu, final Trace trace) {
        final int skip = modrmSkipBytes(cpu, getReturnCS(cpu), getReturnIP(cpu));
        adjustReturnIP(cpu, skip);
    }

    @Interrupt(interrupt = 0x38, description = "FP emulation ESC DC")
    public void fpESC_DC(final CPU cpu, final Trace trace) {
        final int skip = modrmSkipBytes(cpu, getReturnCS(cpu), getReturnIP(cpu));
        adjustReturnIP(cpu, skip);
    }

    @Interrupt(interrupt = 0x39, description = "FP emulation ESC DD")
    public void fpESC_DD(final CPU cpu, final Trace trace) {
        final int skip = modrmSkipBytes(cpu, getReturnCS(cpu), getReturnIP(cpu));
        adjustReturnIP(cpu, skip);
    }

    @Interrupt(interrupt = 0x3A, description = "FP emulation ESC DE")
    public void fpESC_DE(final CPU cpu, final Trace trace) {
        final int skip = modrmSkipBytes(cpu, getReturnCS(cpu), getReturnIP(cpu));
        adjustReturnIP(cpu, skip);
    }

    @Interrupt(interrupt = 0x3B, description = "FP emulation ESC DF")
    public void fpESC_DF(final CPU cpu, final Trace trace) {
        final int skip = modrmSkipBytes(cpu, getReturnCS(cpu), getReturnIP(cpu));
        adjustReturnIP(cpu, skip);
    }

    // INT 3Ch: Segment override + FP instruction. Skip ESC byte + modrm + displacement.
    @Interrupt(interrupt = 0x3C, description = "FP emulation segment override")
    public void fpSegOverride(final CPU cpu, final Trace trace) {
        // Skip 1 (ESC byte) + modrm + displacement.
        final short retCS = getReturnCS(cpu);
        final short retIP = getReturnIP(cpu);
        final int skip = 1 + modrmSkipBytes(cpu, retCS, (short) (retIP + 1));
        adjustReturnIP(cpu, skip);
    }

    // INT 3Dh: FWAIT - no extra bytes to skip.
    @Interrupt(interrupt = 0x3D, description = "FP emulation FWAIT")
    public void fpFWAIT(final CPU cpu, final Trace trace) {
        // FWAIT has no operand bytes after the INT instruction.
    }
}
