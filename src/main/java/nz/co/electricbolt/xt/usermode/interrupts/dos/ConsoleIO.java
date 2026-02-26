package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.*;

import java.io.IOException;

public class ConsoleIO {

    @Interrupt(function = 0x02, description = "Write character to standard output")
    public void writeCharacter(final CPU cpu, final @DL char c) {
        System.out.print(c);
    }

    @Interrupt(function = 0x06, description = "Direct console output")
    public void directConsoleOutput(final CPU cpu, final @DL byte character) {
        if ((character & 0xFF) != 0xFF) {
            // Output character.
            System.out.print((char) (character & 0xFF));
        } else {
            // Input request - report no character available.
            cpu.getReg().flags.setZero(true);
            cpu.getReg().AL.setValue((byte) 0x00);
        }
    }

    @Interrupt(function = 0x09, description = "Write string to standard output")
    public void writeString(final CPU cpu, final @ASCIZ(terminationChar = '$') @DS @DX String s) {
        System.out.print(s);
        cpu.getReg().AL.setValue((byte) '$');
    }

    @Interrupt(function = 0x01, description = "Read character with echo")
    public void readCharacterWithEcho(final CPU cpu) {
        // Blocking read from stdin, echoed to stdout.
        try {
            final int ch = System.in.read();
            cpu.getReg().AL.setValue((byte) (ch == -1 ? 0x0D : ch));
            if (ch != -1) {
                System.out.print((char) ch);
            }
        } catch (final IOException e) {
            cpu.getReg().AL.setValue((byte) 0x0D);
        }
    }

    @Interrupt(function = 0x08, description = "Console input without echo")
    public void consoleInputWithoutEcho(final CPU cpu) {
        // Blocking read from stdin, no echo.
        try {
            final int ch = System.in.read();
            cpu.getReg().AL.setValue((byte) (ch == -1 ? 0x0D : ch));
        } catch (final IOException e) {
            cpu.getReg().AL.setValue((byte) 0x0D);
        }
    }

    @Interrupt(function = 0x07, description = "Direct console input without echo")
    public void directConsoleInputWithoutEcho(final CPU cpu) {
        // Blocking read from stdin, no echo, no Ctrl-C check.
        try {
            final int ch = System.in.read();
            cpu.getReg().AL.setValue((byte) (ch == -1 ? 0x0D : ch));
        } catch (final IOException e) {
            cpu.getReg().AL.setValue((byte) 0x0D);
        }
    }

    @Interrupt(function = 0x0B, description = "Check standard input status")
    public void checkInputStatus(final CPU cpu) {
        // AL = 0x00 if no character available, 0xFF if character available.
        // Report no input available.
        cpu.getReg().AL.setValue((byte) 0x00);
    }
}
