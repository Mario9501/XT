package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.cpu.SegOfs;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.*;
import nz.co.electricbolt.xt.usermode.util.DirectoryTranslation;
import nz.co.electricbolt.xt.usermode.util.MemoryUtil;
import nz.co.electricbolt.xt.usermode.util.Trace;

public class Misc {

    @Interrupt(function = 0x19, description = "Get current default drive")
    public void getCurrentDrive(final CPU cpu) {
        // Always report C: as current drive (drive 2 = C:).
        cpu.getReg().AL.setValue((byte) 0x02);
    }

    @Interrupt(function = 0x0E, description = "Select disk")
    public void selectDisk(final CPU cpu, final @DL byte driveNumber) {
        // Ignore the drive selection, always stay on C:.
        // Return number of logical drives in AL (report 3: A, B, C).
        cpu.getReg().AL.setValue((byte) 0x03);
    }

    @Interrupt(function = 0x3B, description = "Set current directory")
    public void setCurrentDirectory(final CPU cpu, final Trace trace, @ASCIZ @DS @DX String path) {
        // Accept but ignore - we always use the -c directory as root.
        trace.interrupt("Set current directory: " + path + " (ignored)");
        cpu.getReg().flags.setCarry(false);
    }

    @Interrupt(function = 0x47, description = "Get current directory")
    public void getCurrentDirectory(final CPU cpu, final Trace trace, final @DL byte driveNumber,
                                    final @DS @SI SegOfs buffer) {
        // Return root directory "\" as current directory.
        // DS:SI points to 64-byte buffer for ASCIZ path (without drive letter and leading backslash).
        trace.interrupt("Get current directory for drive " + driveNumber);
        // Write empty string (root directory) - no leading backslash per DOS convention.
        cpu.getMemory().setByte(buffer, (byte) 0x00);
        cpu.getReg().flags.setCarry(false);
    }

    @Interrupt(function = 0x29, description = "Parse filename into FCB")
    public void parseFilename(final CPU cpu, final Trace trace, final @ASCIZ @DS @SI String filename,
                              final @ES @DI SegOfs fcbAddress, final @AL byte parseFlags) {
        // Parse a filename string at DS:SI into a File Control Block at ES:DI.
        // This is a legacy DOS function used by some compilers.
        trace.interrupt("Parsing filename: " + filename);

        // Write drive number (0 = default).
        cpu.getMemory().setByte(fcbAddress, (byte) 0x00);

        // Parse filename and extension from the string.
        String name = filename.trim();
        // Skip drive letter if present.
        int start = 0;
        if (name.length() >= 2 && name.charAt(1) == ':') {
            byte drive = (byte) (Character.toUpperCase(name.charAt(0)) - 'A' + 1);
            cpu.getMemory().setByte(fcbAddress, drive);
            start = 2;
        }
        // Skip leading path separators.
        int lastSep = name.lastIndexOf('\\');
        if (lastSep >= start) {
            start = lastSep + 1;
        }
        lastSep = name.lastIndexOf('/');
        if (lastSep >= start) {
            start = lastSep + 1;
        }
        name = name.substring(start).toUpperCase();

        // Split into name (8 chars) and extension (3 chars), space-padded.
        String baseName;
        String ext;
        int dotIdx = name.indexOf('.');
        if (dotIdx >= 0) {
            baseName = name.substring(0, dotIdx);
            ext = name.substring(dotIdx + 1);
        } else {
            baseName = name;
            ext = "";
        }

        // Write 8-byte filename at FCB+01h, space-padded.
        for (int i = 0; i < 8; i++) {
            byte c = (i < baseName.length()) ? (byte) baseName.charAt(i) : (byte) ' ';
            SegOfs addr = new SegOfs(cpu.getReg().ES, (short) (cpu.getReg().DI.getValue() + 0x01 + i));
            cpu.getMemory().setByte(addr, c);
        }
        // Write 3-byte extension at FCB+09h, space-padded.
        for (int i = 0; i < 3; i++) {
            byte c = (i < ext.length()) ? (byte) ext.charAt(i) : (byte) ' ';
            SegOfs addr = new SegOfs(cpu.getReg().ES, (short) (cpu.getReg().DI.getValue() + 0x09 + i));
            cpu.getMemory().setByte(addr, c);
        }

        // Advance DS:SI past the parsed portion.
        cpu.getReg().SI.setValue((short) (cpu.getReg().SI.getValue() + filename.length()));

        // AL = 0x00 if no wildcard characters, 0x01 if wildcards present.
        boolean hasWildcard = name.contains("*") || name.contains("?");
        cpu.getReg().AL.setValue(hasWildcard ? (byte) 0x01 : (byte) 0x00);
    }

    @Interrupt(function = 0x62, description = "Get PSP address")
    public void getPSPAddress(final CPU cpu) {
        // Return the PSP segment in BX. PSP is always at 0x0090.
        cpu.getReg().BX.setValue((short) 0x0090);
    }

    @Interrupt(function = 0x63, subfunction = 0x00, description = "Get lead byte table")
    public void getLeadByteTable(final CPU cpu) {
        // Return pointer to empty DBCS lead byte table in DS:SI.
        // The table is a list of byte-range pairs, terminated by 0x0000.
        // For US/English, the table is just 0x0000 (no DBCS).
        // Point to a known zero location in the BIOS data area.
        cpu.getReg().DS.setValue((short) 0x0040);
        cpu.getReg().SI.setValue((short) 0x00F0); // Points to a zero word in BDA.
        cpu.getReg().flags.setCarry(false);
    }

    @Interrupt(function = 0x33, subfunction = 0x01, description = "Set extended break checking state")
    public void setExtendedBreakChecking(final CPU cpu, final @DL boolean state) {
        // Do nothing, as the user can always terminate the app using ^C.
    }

    @Interrupt(function = 0x37, subfunction = 0x00, description = "Get switch character")
    public void getSwitchCharacter(final CPU cpu) {
        cpu.getReg().AL.setValue((byte) 0x00);
        cpu.getReg().DL.setValue((byte) '/');
    }

    @Interrupt(function = 0x30, description = "Get DOS version")
    public void getDOSVersion(final CPU cpu) {
        cpu.getReg().AX.setValue((short) 0x1606); // Report version DOS 6.22
        cpu.getReg().BX.setValue((short) 0x0);
        cpu.getReg().CX.setValue((short) 0x0);
    }

    @Interrupt(function = 0x38, description = "Get country specific information")
    public void getCountryInformation(final CPU cpu, @DS @DX SegOfs address) {
        MemoryUtil.fill(cpu.getMemory(), address, (short) 0x29, (byte) 0x00);

        // 00h WORD Date format. 0=USA.

        // 02h 5 BYTEs ASCIZ currency symbol string.
        address = new SegOfs(cpu.getReg().DS, (short) (cpu.getReg().DX.getValue() + 0x02));
        cpu.getMemory().writeByte(address, (byte) '$');

        // 07h 2 BYTEs ASCIZ thousands separator.
        address = new SegOfs(cpu.getReg().DS, (short) (cpu.getReg().DX.getValue() + 0x07));
        cpu.getMemory().writeByte(address, (byte) ',');

        // 09h 2 BYTEs ASCIZ decimal separator.
        address = new SegOfs(cpu.getReg().DS, (short) (cpu.getReg().DX.getValue() + 0x09));
        cpu.getMemory().writeByte(address, (byte) '.');

        // 0Bh 2 BYTEs ASCIZ date separator.
        address = new SegOfs(cpu.getReg().DS, (short) (cpu.getReg().DX.getValue() + 0x0B));
        cpu.getMemory().writeByte(address, (byte) '/');

        // 0Dh 2 BYTEs ASCIZ time separator.
        address = new SegOfs(cpu.getReg().DS, (short) (cpu.getReg().DX.getValue() + 0x0D));
        cpu.getMemory().writeByte(address, (byte) ':');

        // 0Fh BYTE currency format. 0=Currency symbol precedes value.

        // 10h 2 BYTE Number of digits.
        address = new SegOfs(cpu.getReg().DS, (short) (cpu.getReg().DX.getValue() + 0x10));
        cpu.getMemory().writeByte(address, (byte) 0x02);

        // 11h BYTE time format. 0=12 hour clock.

        // 12h DWORD address of case map routine (FAR call, AL = character to map to uppercase [>= 80h])

        // 16h ASCIZ data-list separator.
        address = new SegOfs(cpu.getReg().DS, (short) (cpu.getReg().DX.getValue() + 0x16));
        cpu.getMemory().writeByte(address, (byte) ',');

        // 18h 10 BYTEs reserved.
    }
}
