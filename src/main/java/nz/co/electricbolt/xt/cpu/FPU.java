// FPU.java
// XT Copyright © 2025; Electric Bolt Limited.

package nz.co.electricbolt.xt.cpu;

/**
 * x87 FPU state and operations, using Java double arithmetic.
 *
 * The 8087 register file is a stack of 8 x 80-bit extended precision registers.
 * We approximate with Java doubles (64-bit IEEE 754), which is sufficient for
 * MS C 5.0's software FP emulation (which itself only uses 64-bit doubles).
 */
public class FPU {

    // Register stack (8 entries, indexed relative to top)
    private final double[] st = new double[8];

    // Stack top pointer (0-7, decrements on push, increments on pop)
    private int top = 0;

    // Status word: condition codes C0(bit8), C1(bit9), C2(bit10), C3(bit14), exception flags
    private short statusWord = 0;

    // Control word: exception masks, precision/rounding control (default 0x037F = all exceptions masked)
    private short controlWord = 0x037F;

    // Tag word: 2 bits per register (00=valid, 01=zero, 10=special, 11=empty)
    private short tagWord = (short) 0xFFFF; // all empty initially

    /**
     * FINIT - reset FPU to default state.
     */
    public void init() {
        for (int i = 0; i < 8; i++) {
            st[i] = 0.0;
        }
        top = 0;
        statusWord = 0;
        controlWord = 0x037F;
        tagWord = (short) 0xFFFF;
    }

    // ======================================================================
    // Stack operations
    // ======================================================================

    /**
     * Push a value onto the FPU stack. Decrements TOP, then stores value at ST(0).
     */
    public void push(double value) {
        top = (top - 1) & 7;
        st[top] = value;
        setTag(0, value);
    }

    /**
     * Pop the top value from the FPU stack. Reads ST(0), marks it empty, increments TOP.
     */
    public double pop() {
        double value = st[top];
        setTagRaw(top, 3); // empty
        st[top] = 0.0;
        top = (top + 1) & 7;
        return value;
    }

    /**
     * Get ST(i) where i=0 is the stack top.
     */
    public double getST(int i) {
        return st[(top + i) & 7];
    }

    /**
     * Set ST(i) where i=0 is the stack top.
     */
    public void setST(int i, double value) {
        int idx = (top + i) & 7;
        st[idx] = value;
        setTag(i, value);
    }

    /**
     * Get the physical register index for ST(i).
     */
    public int getPhysicalIndex(int i) {
        return (top + i) & 7;
    }

    /**
     * Decrement stack top pointer (FDECSTP).
     */
    public void decSTP() {
        top = (top - 1) & 7;
    }

    /**
     * Increment stack top pointer (FINCSTP).
     */
    public void incSTP() {
        top = (top + 1) & 7;
    }

    /**
     * Mark ST(i) as empty (FFREE).
     */
    public void free(int i) {
        int idx = (top + i) & 7;
        st[idx] = 0.0;
        setTagRaw(idx, 3);
    }

    // ======================================================================
    // Status word and condition codes
    // ======================================================================

    public short getStatusWord() {
        // Encode TOP into bits 13-11
        return (short) ((statusWord & 0xC7FF) | ((top & 7) << 11));
    }

    public void setStatusWord(short value) {
        statusWord = value;
        top = (value >> 11) & 7;
    }

    public short getControlWord() {
        return controlWord;
    }

    public void setControlWord(short value) {
        controlWord = value;
    }

    public short getTagWord() {
        return tagWord;
    }

    public void setTagWord(short value) {
        tagWord = value;
    }

    /**
     * Set condition code bits in status word for comparison results.
     * C3=bit14, C2=bit10, C1=bit9, C0=bit8.
     *
     * ST > operand:  C3=0 C2=0 C0=0
     * ST < operand:  C3=0 C2=0 C0=1
     * ST == operand: C3=1 C2=0 C0=0
     * Unordered:     C3=1 C2=1 C0=1
     */
    public void setCC(boolean c3, boolean c2, boolean c0) {
        statusWord = (short) (statusWord & ~0x4500); // clear C3, C2, C0
        if (c3) statusWord |= 0x4000;
        if (c2) statusWord |= 0x0400;
        if (c0) statusWord |= 0x0100;
    }

    /**
     * Set condition codes based on comparing a to b.
     */
    public void compare(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            setCC(true, true, true);   // unordered
        } else if (a > b) {
            setCC(false, false, false); // ST > source
        } else if (a < b) {
            setCC(false, false, true);  // ST < source
        } else {
            setCC(true, false, false);  // ST == source
        }
    }

    /**
     * Clear C1 flag (used by FXAM and others).
     */
    public void clearC1() {
        statusWord = (short) (statusWord & ~0x0200);
    }

    /**
     * Set C1 flag.
     */
    public void setC1() {
        statusWord = (short) (statusWord | 0x0200);
    }

    /**
     * Clear exception flags (FCLEX).
     */
    public void clearExceptions() {
        statusWord = (short) (statusWord & 0x7F00);
    }

    // ======================================================================
    // Tag word helpers
    // ======================================================================

    private void setTag(int stIndex, double value) {
        int phys = (top + stIndex) & 7;
        int tag;
        if (value == 0.0) {
            tag = 1; // zero
        } else if (Double.isNaN(value) || Double.isInfinite(value)) {
            tag = 2; // special
        } else {
            tag = 0; // valid
        }
        setTagRaw(phys, tag);
    }

    private void setTagRaw(int physIndex, int tag) {
        int shift = physIndex * 2;
        tagWord = (short) ((tagWord & ~(3 << shift)) | ((tag & 3) << shift));
    }

    // ======================================================================
    // Memory read/write helpers
    // ======================================================================

    /**
     * Read IEEE 754 single-precision float (4 bytes) from memory.
     */
    public static double readFloat32(Memory mem, SegOfs addr) {
        addr = addr.copy();
        int bits = mem.getByte(addr) & 0xFF;
        addr.increment();
        bits |= (mem.getByte(addr) & 0xFF) << 8;
        addr.increment();
        bits |= (mem.getByte(addr) & 0xFF) << 16;
        addr.increment();
        bits |= (mem.getByte(addr) & 0xFF) << 24;
        return Float.intBitsToFloat(bits);
    }

    /**
     * Read IEEE 754 double-precision float (8 bytes) from memory.
     */
    public static double readFloat64(Memory mem, SegOfs addr) {
        addr = addr.copy();
        long bits = 0;
        for (int i = 0; i < 8; i++) {
            bits |= ((long) (mem.getByte(addr) & 0xFF)) << (i * 8);
            addr.increment();
        }
        return Double.longBitsToDouble(bits);
    }

    /**
     * Write IEEE 754 single-precision float (4 bytes) to memory.
     */
    public static void writeFloat32(Memory mem, SegOfs addr, double value) {
        addr = addr.copy();
        int bits = Float.floatToRawIntBits((float) value);
        mem.setByte(addr, (byte) bits);
        addr.increment();
        mem.setByte(addr, (byte) (bits >> 8));
        addr.increment();
        mem.setByte(addr, (byte) (bits >> 16));
        addr.increment();
        mem.setByte(addr, (byte) (bits >> 24));
    }

    /**
     * Write IEEE 754 double-precision float (8 bytes) to memory.
     */
    public static void writeFloat64(Memory mem, SegOfs addr, double value) {
        addr = addr.copy();
        long bits = Double.doubleToRawLongBits(value);
        for (int i = 0; i < 8; i++) {
            mem.setByte(addr, (byte) (bits >> (i * 8)));
            addr.increment();
        }
    }

    /**
     * Read 16-bit signed integer from memory.
     */
    public static double readInt16(Memory mem, SegOfs addr) {
        short value = mem.getWord(addr);
        return (double) value;
    }

    /**
     * Read 32-bit signed integer from memory.
     */
    public static double readInt32(Memory mem, SegOfs addr) {
        addr = addr.copy();
        int value = mem.getByte(addr) & 0xFF;
        addr.increment();
        value |= (mem.getByte(addr) & 0xFF) << 8;
        addr.increment();
        value |= (mem.getByte(addr) & 0xFF) << 16;
        addr.increment();
        value |= (mem.getByte(addr) & 0xFF) << 24;
        return (double) value;
    }

    /**
     * Read 64-bit signed integer from memory.
     */
    public static double readInt64(Memory mem, SegOfs addr) {
        addr = addr.copy();
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= ((long) (mem.getByte(addr) & 0xFF)) << (i * 8);
            addr.increment();
        }
        return (double) value;
    }

    /**
     * Write 16-bit signed integer to memory (truncates toward zero).
     */
    public static void writeInt16(Memory mem, SegOfs addr, double value) {
        short intVal = (short) roundToInt(value);
        mem.setWord(addr, intVal);
    }

    /**
     * Write 32-bit signed integer to memory (truncates toward zero).
     */
    public static void writeInt32(Memory mem, SegOfs addr, double value) {
        addr = addr.copy();
        int intVal = (int) roundToInt(value);
        mem.setByte(addr, (byte) intVal);
        addr.increment();
        mem.setByte(addr, (byte) (intVal >> 8));
        addr.increment();
        mem.setByte(addr, (byte) (intVal >> 16));
        addr.increment();
        mem.setByte(addr, (byte) (intVal >> 24));
    }

    /**
     * Write 64-bit signed integer to memory.
     */
    public static void writeInt64(Memory mem, SegOfs addr, double value) {
        addr = addr.copy();
        long intVal = roundToInt(value);
        for (int i = 0; i < 8; i++) {
            mem.setByte(addr, (byte) (intVal >> (i * 8)));
            addr.increment();
        }
    }

    /**
     * Read 80-bit extended precision from memory. Approximated as double.
     * Format: 8-byte significand (with explicit integer bit), 2-byte exponent+sign.
     */
    public static double readFloat80(Memory mem, SegOfs addr) {
        addr = addr.copy();
        long significand = 0;
        for (int i = 0; i < 8; i++) {
            significand |= ((long) (mem.getByte(addr) & 0xFF)) << (i * 8);
            addr.increment();
        }
        int exponentSign = mem.getByte(addr) & 0xFF;
        addr.increment();
        exponentSign |= (mem.getByte(addr) & 0xFF) << 8;

        boolean sign = (exponentSign & 0x8000) != 0;
        int exponent = exponentSign & 0x7FFF;

        if (exponent == 0 && significand == 0) {
            return sign ? -0.0 : 0.0;
        }
        if (exponent == 0x7FFF) {
            if ((significand & 0x7FFFFFFFFFFFFFFFL) == 0) {
                return sign ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
            return Double.NaN;
        }

        // Convert unsigned significand to double (bit 63 is explicit integer bit, not a sign)
        double sig;
        if (significand >= 0) {
            sig = (double) significand;
        } else {
            // Unsigned conversion: split to avoid Java signed long overflow
            sig = (double) (significand >>> 1) * 2.0 + (double) (significand & 1L);
        }
        // value = (-1)^sign * significand * 2^(exponent - 16383 - 63)
        double result = sig * Math.pow(2.0, exponent - 16383 - 63);
        return sign ? -result : result;
    }

    /**
     * Write 80-bit extended precision to memory. Approximated from double.
     */
    public static void writeFloat80(Memory mem, SegOfs addr, double value) {
        addr = addr.copy();

        if (value == 0.0) {
            boolean sign = (Double.doubleToRawLongBits(value) & 0x8000000000000000L) != 0;
            for (int i = 0; i < 8; i++) {
                mem.setByte(addr, (byte) 0);
                addr.increment();
            }
            mem.setByte(addr, (byte) 0);
            addr.increment();
            mem.setByte(addr, (byte) (sign ? 0x80 : 0x00));
            return;
        }

        boolean sign = value < 0;
        if (sign) value = -value;

        if (Double.isInfinite(value)) {
            // Significand = 0x8000000000000000, exponent = 0x7FFF
            for (int i = 0; i < 8; i++) {
                mem.setByte(addr, (byte) (i == 7 ? 0x80 : 0x00));
                addr.increment();
            }
            mem.setByte(addr, (byte) 0xFF);
            addr.increment();
            mem.setByte(addr, (byte) (sign ? 0xFF : 0x7F));
            return;
        }

        if (Double.isNaN(value)) {
            for (int i = 0; i < 8; i++) {
                mem.setByte(addr, (byte) (i == 7 ? 0xC0 : 0x00));
                addr.increment();
            }
            mem.setByte(addr, (byte) 0xFF);
            addr.increment();
            mem.setByte(addr, (byte) 0x7F);
            return;
        }

        // Extract from double and convert to 80-bit format
        long dblBits = Double.doubleToRawLongBits(value);
        int dblExp = (int) ((dblBits >> 52) & 0x7FF);
        long dblMant = dblBits & 0x000FFFFFFFFFFFFFL;

        // Add implicit bit for normal numbers
        if (dblExp != 0) {
            dblMant |= 0x0010000000000000L;
        }

        // Double exponent bias is 1023, extended is 16383
        // Double mantissa is 53 bits (52 stored + 1 implicit), extended is 64 bits (63 stored + 1 explicit)
        int extExp = dblExp - 1023 + 16383;
        long extMant = dblMant << 11; // shift 52-bit mantissa to 63-bit position

        for (int i = 0; i < 8; i++) {
            mem.setByte(addr, (byte) (extMant >> (i * 8)));
            addr.increment();
        }
        int expSign = extExp & 0x7FFF;
        if (sign) expSign |= 0x8000;
        mem.setByte(addr, (byte) expSign);
        addr.increment();
        mem.setByte(addr, (byte) (expSign >> 8));
    }

    /**
     * Round double to long integer using current rounding mode.
     * Default rounding mode (control word bits 11-10 = 00) is round to nearest.
     */
    private static long roundToInt(double value) {
        return Math.round(value);
    }
}
