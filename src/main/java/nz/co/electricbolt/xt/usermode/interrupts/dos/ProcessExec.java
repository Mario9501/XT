package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.cpu.SegOfs;
import nz.co.electricbolt.xt.usermode.ErrorCode;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.*;
import nz.co.electricbolt.xt.usermode.util.DirectoryTranslation;
import nz.co.electricbolt.xt.usermode.util.Trace;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements INT 21h/4Bh (EXEC - Load and Execute Program).
 * Spawns a child xt process to run the requested DOS program.
 */
public class ProcessExec {

    private short lastExitCode = 0;

    @Interrupt(function = 0x4B, subfunction = 0x00, description = "EXEC - Load and execute program")
    public void execProgram(final CPU cpu, final Trace trace, final DirectoryTranslation directoryTranslation,
                            @ASCIZ @DS @DX String programName, final @ES @BX SegOfs paramBlock) {
        trace.interrupt("EXEC program: " + programName);

        final short bxVal = cpu.getReg().BX.getValue();
        final short esVal = cpu.getReg().ES.getValue();

        // Read EXEC parameter block at ES:BX.
        // +00h WORD  - environment segment (0 = inherit parent's)
        // +02h DWORD - pointer to command tail (ofs:seg)
        // +06h DWORD - pointer to first FCB
        // +0Ah DWORD - pointer to second FCB

        final short envSeg = cpu.getMemory().readWord(new SegOfs(esVal, bxVal));
        final short cmdTailOfs = cpu.getMemory().readWord(new SegOfs(esVal, (short) (bxVal + 0x02)));
        final short cmdTailSeg = cpu.getMemory().readWord(new SegOfs(esVal, (short) (bxVal + 0x04)));

        // Read the environment block.
        final Map<String, String> dosEnv = new HashMap<>();
        if (envSeg != 0) {
            short envOfs = 0;
            while (true) {
                byte b = cpu.getMemory().readByte(new SegOfs(envSeg, envOfs));
                if (b == 0) break;
                StringBuilder var = new StringBuilder();
                while (b != 0) {
                    var.append((char) (b & 0xFF));
                    envOfs++;
                    b = cpu.getMemory().readByte(new SegOfs(envSeg, envOfs));
                }
                envOfs++; // skip null terminator
                final String envVar = var.toString();
                final int eqIdx = envVar.indexOf('=');
                if (eqIdx > 0) {
                    dosEnv.put(envVar.substring(0, eqIdx), envVar.substring(eqIdx + 1));
                }
            }
            trace.interrupt("DOS environment: " + dosEnv);
        }

        // Read the command tail: first byte is length, then chars, terminated by 0x0D.
        final byte cmdLen = cpu.getMemory().readByte(new SegOfs(cmdTailSeg, cmdTailOfs));
        final StringBuilder cmdTail = new StringBuilder();
        for (int i = 0; i < (cmdLen & 0xFF); i++) {
            final byte b = cpu.getMemory().readByte(new SegOfs(cmdTailSeg, (short) (cmdTailOfs + 1 + i)));
            if (b == 0x0D) break;
            cmdTail.append((char) (b & 0xFF));
        }
        final String args = cmdTail.toString().trim();
        trace.interrupt("Command tail: \"" + args + "\"");

        // Resolve the program path.
        String hostProgram = directoryTranslation.emulatedPathToHostPath(programName.toUpperCase());

        // Check if the program file exists.
        if (!new File(hostProgram).exists()) {
            trace.interrupt("Program not found: " + hostProgram);
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue(ErrorCode.FileNotFound.errorCode);
            return;
        }

        // Find the xt.jar path (same jar we're running from).
        final String xtJarPath = ProcessExec.class.getProtectionDomain().getCodeSource().getLocation().getPath();

        // Extract the host working directory.
        String hostWorkDir = directoryTranslation.emulatedPathToHostPath("");

        // Build the child xt command.
        final List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(xtJarPath);
        command.add("run");
        command.add("-c");
        command.add(hostWorkDir);
        command.add(programName.toUpperCase());
        // Add command tail arguments.
        if (!args.isEmpty()) {
            for (final String arg : args.split("\\s+")) {
                if (!arg.isEmpty()) {
                    command.add(arg);
                }
            }
        }

        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();

        // Propagate DOS environment variables to the child xt process.
        // XT reads env vars from the environment block in memory, not from Java env,
        // so we pass them as XT_DOS_ENV_* Java system properties that the child can pick up.
        // Actually, the child xt process builds its own env block from scratch.
        // We need a way to pass the DOS env to the child.
        // Use a temp file approach: write env vars to a temp file, pass via system property.
        // Simpler approach: pass DOS env vars as Java system properties prefixed with XT_DOS_ENV_.
        final Map<String, String> javaEnv = pb.environment();
        for (final Map.Entry<String, String> entry : dosEnv.entrySet()) {
            javaEnv.put("XT_DOS_ENV_" + entry.getKey(), entry.getValue());
        }

        try {
            trace.interrupt("Spawning: " + String.join(" ", command));
            final Process process = pb.start();
            final int exitCode = process.waitFor();
            lastExitCode = (short) (exitCode & 0xFF);
            trace.interrupt("Child process exited with code " + exitCode);

            cpu.getReg().flags.setCarry(false);
        } catch (Exception e) {
            trace.interrupt("Failed to execute: " + e.getMessage());
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue(ErrorCode.FileNotFound.errorCode);
        }
    }

    @Interrupt(function = 0x4D, description = "Get return code of child process")
    public void getReturnCode(final CPU cpu, final Trace trace) {
        trace.interrupt("Returning child exit code: " + lastExitCode);
        cpu.getReg().AX.setValue(lastExitCode);
    }
}
