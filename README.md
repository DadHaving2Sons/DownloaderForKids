# DownloaderForKids

A simple and robust Android application for downloading videos and audio from YouTube and other supported sites, designed for personal use.

## Features

- **Video & Audio Download**: Supports downloading video (mp4) or audio-only (m4a, mp3) formats.
- **Format Selection**: Analyzing the URL presents a list of available video resolutions and audio qualities.
- **Background Downloading**: Downloads continue in the background with a persistent notification showing progress.
- **Smart Library Updates**: Checks and updates the `yt-dlp` core library on a schedule (once every 24 hours) instead of every foreground launch.
- **Download-Safe Update Checks**: Skips update checks while downloads are in progress and runs the pending check after downloads finish.
- **Concurrent Downloads**: Supports multiple simultaneous downloads without conflict.

## Architecture

This project follows the **MVVM (Model-View-ViewModel)** architecture pattern to ensure separation of concerns and testability.

- **View**: `MainActivity` (handling UI via **ViewBinding**).
- **ViewModel**: `MainViewModel` (manages UI state, data fetching, and business logic using `LiveData`).
- **Model (Repository)**: `YoutubeRepository` (encapsulates interactions with `YoutubeDL` library).
- **Service**: `DownloadService` (handles long-running download tasks in the foreground).

## Tech Stack

- **Language**: Kotlin
- **Concurrency**: Kotlin Coroutines
- **AndroidX**:
    - Activity KTX
    - Lifecycle (ViewModel, LiveData)
    - DocumentFile (Storage Access Framework)
    - ConstraintLayout
- **Libraries**:
    - [youtubedl-android](https://github.com/yausername/youtubedl-android) (Wrapper for yt-dlp)
    - FFmpeg (for media processing)

## yt-dlp Update Policy

- The app attempts a `yt-dlp` update check at most once every 24 hours.
- If one or more downloads are running, the check is deferred.
- When all downloads are completed, a deferred check is automatically retried.
- Update results are shown to the user (updated, already up-to-date, or failed).

## Setup

1.  Clone the repository.
2.  Open in **Android Studio**.
3.  Sync Gradle project.
4.  Run on an Android device (Physical device recommended due to ARM structure of libraries).

## Permissions

- **Internet**: To fetch video info and download files.
- **Storage**: To save downloaded files to user-selected directories.
- **Notifications**: To show download progress and completion status.
