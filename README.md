# Android Tux Terminal 🐧📱

An Android terminal project that aims to bring a lightweight Linux-style command-line environment to Android.

## Current status

**v0.1.0 — MVP**

- Native Android app written in Kotlin
- Dark terminal interface
- Runs commands through Android's `/system/bin/sh`
- Built-in `help` and `clear` commands
- GitHub Actions debug APK builds
- No root required for the current shell

## Roadmap

The goal is to grow this into a real Android × Linux terminal rather than just a command prompt:

- [ ] Proper PTY terminal engine
- [ ] ANSI colors and cursor control
- [ ] Persistent shell sessions
- [ ] Multiple terminal tabs/sessions
- [ ] Better keyboard / extra-key row
- [ ] File manager integration
- [ ] Linux userland bootstrap
- [ ] Package management
- [ ] Optional isolated Linux environments
- [ ] ARM64-first optimization
- [ ] Settings for fonts, colors, shell and terminal behavior
- [ ] Release APKs through GitHub Releases

## Architecture

Android Tux Terminal is intentionally being built in layers. The first layer is the Android UI and shell process. Later layers can add a real PTY and an isolated Linux userland. This keeps the project usable while the Linux environment is being developed.

The project is inspired by the general idea of Android terminal environments such as Termux, but Android Tux Terminal is its own project and package namespace.

## Build

Open the repository in Android Studio and run the `app` configuration, or let GitHub Actions build the debug APK.

The debug APK is uploaded as an Actions artifact after successful builds.
