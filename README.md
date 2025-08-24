# NFmp3Downloader

A simple desktop application for downloading YouTube videos as MP3 or MP4 files, built on the powerful **yt-dlp** command-line tool. The app is developed in Java with a local web UI.

This project is specifically designed with features to ensure smooth operation for **NetFree** users.

## Key Features

- **Download Single Videos or Entire Playlists**: Easily download any YouTube content.
- **High-Quality MP3 Conversion**: Converts videos to MP3 with embedded metadata and thumbnails.
- **Multiple MP4 Resolutions**: Download videos in various qualities, from 480p to the best available.
- **Bilingual UI**: Fully translated interface supporting both English and Hebrew.
- **Browser Integration**: Launch the application and initiate downloads directly from your browser using a custom protocol (`nfmp3downloader://`).
- **NetFree Compatibility**: 
    - All download commands bypass certificate validation (`--no-check-certificates`) to work seamlessly with NetFree's filtering.
    - Includes an automatic self-update mechanism for `yt-dlp` to resolve common format errors without manual intervention.

## Installation and Usage

1.  Download the latest installer from the [Releases page](https://github.com/mps435/NFmp3Downloader/releases).
2.  Install the application. The installer will register the necessary `nfmp3downloader://` protocol.
3.  A browser window will open with the UI. You can also launch it anytime from the Start Menu shortcut or by dragging the icon in the UI to your bookmarks bar.
4.  Paste a YouTube link and download.

---

## Building from Source

If you want to build the project yourself, follow these steps.

### Prerequisites

- **JDK 11** or newer (must include `jpackage`).
- **Apache Maven**.
- (For building the installer) **Inno Setup**.

### Build Steps

1.  **Get the Source Code:**
    The recommended way is to clone the repository using Git:
    ```bash
    git clone https://github.com/mps435/NFmp3Downloader.git
    cd NFmp3Downloader
    ```
    Alternatively, you can download the source code as a ZIP file from the main repository page (Code -> Download ZIP).

2.  **Binaries (yt-dlp & ffmpeg):**
    For a quick and easy build, this repository includes the required `yt-dlp.exe` and `ffmpeg.exe` binaries in the `src/main/resources/bin` directory.

   **However, for security reasons, you can download these files yourself from their official sources.** To do this:
- Delete the existing `.exe` files from `src/main/resources/bin`.
- Download the latest versions from the links below and place them in this directory.
        - **yt-dlp.exe**: [Official GitHub Releases](https://github.com/yt-dlp/yt-dlp/releases/latest) (download `yt-dlp.exe`)
        - **ffmpeg.exe**: [Gyan.dev](https://www.gyan.dev/ffmpeg/builds/) (the `ffmpeg-release-essentials.7z` build is recommended)

3.  **Build the Executable JAR using Maven:**
    This command will compile the code and package it into a single, executable "fat JAR" in the `target` directory.
    ```bash
    mvn package
    ```

4.  **Create the Self-Contained Application Image:**
    Use `jpackage` to bundle the JAR with a minimal Java runtime. This creates a directory (`release\NFmp3Downloader`) containing an `.exe` and all necessary files, making the application independent of any installed Java on the user's machine. Run the following command from the project's root directory:
    ```bash
    jpackage --type app-image --dest release --input target --name NFmp3Downloader --main-class com.mps.App --main-jar NFmp3Downloader-1.0-SNAPSHOT.jar --java-options "-Dfile.encoding=UTF-8" --icon src/main/resources/icon.ico --app-version 1.0.0 --vendor "mps" --copyright "Copyright (c) 2025 mps"
    ```

5.  **(Optional) Build the Windows Installer:**
    To create a user-friendly `setup.exe` that also registers the custom `nfmp3downloader://` protocol for browser integration, compile the `run.iss` script using Inno Setup. The script is configured to package the application image created in the previous step.

---

## Acknowledgements

- The core download functionality is powered by the excellent [yt-dlp](https://github.com/yt-dlp/yt-dlp) project.
- [FFmpeg](https://ffmpeg.org/) is used for audio extraction and format processing.
- The application icon (`icon.ico`) was created with the assistance of OpenAI's GPT model.

---

### **Disclaimer**
This tool is provided for educational purposes only. Downloading copyrighted content from YouTube may violate their Terms of Service and infringe on copyright laws in your country. Please respect the rights of content creators and use this tool responsibly. The developer assumes no liability for any misuse of this software.