package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.BX;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.ES;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.Interrupt;
import nz.co.electricbolt.xt.usermode.util.Trace;

public class Memory {

    // Simple arena allocator for DOS memory blocks.
    // Start allocating above the program at segment 0x3000 (192KB into 1MB space).
    private short nextFreeSegment = 0x3000;

    @Interrupt(function = 0x48, description = "Allocate memory block")
    public void allocateMemoryBlock(final CPU cpu, final Trace trace, final @BX short paragraphs) {
        // BX = number of 16-byte paragraphs to allocate.
        final int requested = paragraphs & 0xFFFF;
        trace.interrupt("Allocating " + requested + " paragraphs at segment " + String.format("%04X", nextFreeSegment));
        cpu.getReg().flags.setCarry(false);
        cpu.getReg().AX.setValue(nextFreeSegment);
        nextFreeSegment = (short) ((nextFreeSegment & 0xFFFF) + requested);
    }

    @Interrupt(function = 0x49, description = "Free memory block")
    public void freeMemoryBlock(final CPU cpu, final Trace trace) {
        // ES = segment of block to free.
        // We don't track individual blocks, just succeed.
        trace.interrupt("Freeing memory block at segment " + String.format("%04X", cpu.getReg().ES.getValue()));
        cpu.getReg().flags.setCarry(false);
    }

    @Interrupt(function = 0x4A, description = "Resize memory block")
    public void resizeMemoryBlock(final CPU cpu) {
        // We don't support resizing memory blocks, as we already allocate the entire address space to the app, so
        // we just return success.
        cpu.getReg().flags.setCarry(false);
    }
}
