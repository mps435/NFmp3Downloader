package com.mps;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class NativeFolderDialog {

    public static String show(String title) {
        String safeTitle = (title != null && !title.isEmpty()) ? title : "Select Folder";
        String script = buildScript(safeTitle, true, null);
        return executePowerShell(script);
    }

    public static String chooseFile(String title) {
        String safeTitle = (title != null && !title.isEmpty()) ? title : "Select Image";
        String filter = "Images (*.jpg;*.png;*.jpeg;*.webp)|*.jpg;*.png;*.jpeg;*.webp";
        String script = buildScript(safeTitle, false, filter);
        return executePowerShell(script);
    }

    private static String buildScript(String title, boolean isFolderPicker, String filter) {
        StringBuilder sb = new StringBuilder();
        sb.append("$OutputEncoding = [Console]::OutputEncoding = [System.Text.Encoding]::UTF8; ");
        boolean isHebrew = title.chars().anyMatch(c -> c >= 0x0590 && c <= 0x05FF);

        if (isHebrew) {
            sb.append("[System.Threading.Thread]::CurrentThread.CurrentUICulture = 'he-IL'; ");
        }

        sb.append("Add-Type -AssemblyName System.Windows.Forms; ");
        sb.append("$d = New-Object System.Windows.Forms.OpenFileDialog; ");
        sb.append("$d.Title = '").append(title).append("'; ");

        if (isFolderPicker) {
            sb.append("$d.FileName = 'Folder Selection'; ");
            sb.append("$d.ValidateNames = $false; ");
            sb.append("$d.CheckFileExists = $false; ");
            sb.append("$d.CheckPathExists = $true; ");
            sb.append("$d.Filter = 'Folders|`n'; ");
        } else {
            sb.append("$d.Filter = '").append(filter).append("'; ");
        }

        sb.append("$dummy = New-Object System.Windows.Forms.Form; ");
        sb.append("$dummy.TopMost = $true; ");
        sb.append("$dummy.TopLevel = $true; ");
        sb.append("$dummy.ShowInTaskbar = $false; ");
        sb.append("$dummy.Opacity = 0; ");
        sb.append("$dummy.StartPosition = 'CenterScreen'; ");
        sb.append("$dummy.Show(); ");
        sb.append("$dummy.Activate(); ");

        sb.append("if ($d.ShowDialog($dummy) -eq [System.Windows.Forms.DialogResult]::OK) { ");
        if (isFolderPicker) {
            sb.append("   Write-Output ([System.IO.Path]::GetDirectoryName($d.FileName)); ");
        } else {
            sb.append("   Write-Output $d.FileName; ");
        }
        sb.append("} else { ");
        sb.append("   Write-Output 'CANCELLED'; ");
        sb.append("} ");
        sb.append("$dummy.Dispose();");

        return sb.toString();
    }

    private static String executePowerShell(String script) {
        try {
            byte[] scriptBytes = script.getBytes(StandardCharsets.UTF_16LE);
            String encodedScript = Base64.getEncoder().encodeToString(scriptBytes);

            ProcessBuilder builder = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Sta", "-EncodedCommand", encodedScript
            );

            builder.redirectErrorStream(true);
            Process process = builder.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            );

            String resultPath = null;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#< CLIXML") || trimmed.contains("<Objs Version") || trimmed.isEmpty()) {
                    continue;
                }
                if ("CANCELLED".equals(trimmed)) {
                    return null;
                }
                resultPath = trimmed;
            }

            process.waitFor();

            if (resultPath != null && (resultPath.contains(":\\") || resultPath.startsWith("\\") || resultPath.startsWith("/"))) {
                return resultPath;
            }

        } catch (Exception e) {
            System.err.println("Dialog Error: " + e.getMessage());
        }
        return null;
    }
}
