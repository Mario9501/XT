package nz.co.electricbolt.xt.usermode.interrupts.bios;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.*;
import nz.co.electricbolt.xt.usermode.util.Trace;

/**
 * Stub handlers for BIOS INT 10h video services.
 * Since XT is a command-line emulator without a display buffer, these are no-ops
 * that prevent crashes when programs use BIOS video calls.
 */
public class Video {

    @Interrupt(interrupt = 0x10, function = 0x00, description = "Set video mode")
    public void setVideoMode(final CPU cpu, final Trace trace) {
        // No-op. AL contains the requested mode.
    }

    @Interrupt(interrupt = 0x10, function = 0x01, description = "Set cursor shape")
    public void setCursorShape(final CPU cpu, final Trace trace) {
        // No-op. CH=start line, CL=end line.
    }

    @Interrupt(interrupt = 0x10, function = 0x02, description = "Set cursor position")
    public void setCursorPosition(final CPU cpu, final Trace trace) {
        // No-op. BH=page, DH=row, DL=column.
    }

    @Interrupt(interrupt = 0x10, function = 0x03, description = "Get cursor position and shape")
    public void getCursorPosition(final CPU cpu, final Trace trace) {
        // Return cursor at 0,0, block cursor shape.
        cpu.getReg().DH.setValue((byte) 0x00); // row
        cpu.getReg().DL.setValue((byte) 0x00); // column
        cpu.getReg().CH.setValue((byte) 0x06); // cursor start line
        cpu.getReg().CL.setValue((byte) 0x07); // cursor end line
    }

    @Interrupt(interrupt = 0x10, function = 0x05, description = "Select active display page")
    public void selectDisplayPage(final CPU cpu, final Trace trace) {
        // No-op. AL=page number.
    }

    @Interrupt(interrupt = 0x10, function = 0x06, description = "Scroll window up")
    public void scrollWindowUp(final CPU cpu, final Trace trace) {
        // No-op. AL=lines to scroll (0=clear), BH=attribute for blank lines.
    }

    @Interrupt(interrupt = 0x10, function = 0x07, description = "Scroll window down")
    public void scrollWindowDown(final CPU cpu, final Trace trace) {
        // No-op. AL=lines to scroll (0=clear), BH=attribute for blank lines.
    }

    @Interrupt(interrupt = 0x10, function = 0x08, description = "Read character and attribute")
    public void readCharAndAttribute(final CPU cpu, final Trace trace) {
        // Return space with normal attribute.
        cpu.getReg().AH.setValue((byte) 0x07); // attribute
        cpu.getReg().AL.setValue((byte) 0x20); // space character
    }

    @Interrupt(interrupt = 0x10, function = 0x09, description = "Write character and attribute")
    public void writeCharAndAttribute(final CPU cpu, final Trace trace) {
        // No-op. AL=char, BH=page, BL=attribute, CX=count.
    }

    @Interrupt(interrupt = 0x10, function = 0x0E, description = "Teletype output")
    public void teletypeOutput(final CPU cpu, final Trace trace) {
        // Write character to stdout for basic TTY output.
        final byte ch = cpu.getReg().AL.getValue();
        if (ch != 0) {
            System.out.print((char)(ch & 0xFF));
        }
    }

    @Interrupt(interrupt = 0x10, function = 0x0F, description = "Get current video mode")
    public void getVideoMode(final CPU cpu, final Trace trace) {
        // Report 80x25 text mode (mode 3).
        cpu.getReg().AH.setValue((byte) 80);   // columns
        cpu.getReg().AL.setValue((byte) 0x03); // mode 3 = 80x25 color text
        cpu.getReg().BH.setValue((byte) 0x00); // active page
    }

    @Interrupt(interrupt = 0x10, function = 0x12, description = "EGA alternate select / video subsystem config")
    public void egaAlternateSelect(final CPU cpu, final Trace trace) {
        // BL=10h on entry means "get EGA info". If BL stays 10h on return,
        // the caller knows EGA is not present. Since XT emulates a basic
        // CGA-class display, we leave BL unchanged (no-op).
    }

    @Interrupt(interrupt = 0x10, function = 0x1A, description = "VGA display combination code")
    public void vgaDisplayCombination(final CPU cpu, final Trace trace) {
        // AL returns 1Ah if VGA BIOS present. Since XT emulates a basic CGA
        // display, return AL=00h to indicate this function is not supported.
        // This causes video detection code to fall through to older methods.
        cpu.getReg().AL.setValue((byte) 0x00);
    }
}
