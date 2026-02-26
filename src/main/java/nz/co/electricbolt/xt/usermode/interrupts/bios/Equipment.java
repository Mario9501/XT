package nz.co.electricbolt.xt.usermode.interrupts.bios;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.Interrupt;
import nz.co.electricbolt.xt.usermode.util.Trace;

/**
 * BIOS INT 11h - Equipment determination.
 * Returns the equipment word in AX describing installed hardware.
 */
public class Equipment {

    @Interrupt(interrupt = 0x11, description = "Get equipment list")
    public void getEquipmentList(final CPU cpu, final Trace trace) {
        // Return equipment word in AX.
        // Bits 4-5 = 10b: 80x25 CGA color display.
        // Bit 0 = 1: at least one floppy drive installed.
        // Bits 6-7 = 00: one floppy drive (if bit 0 = 1).
        // Bits 9-11 = 001: one serial port.
        // AX = 0x0221
        cpu.getReg().AX.setValue((short) 0x0221);
    }
}
