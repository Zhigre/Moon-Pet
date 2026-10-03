# 👾 Moonlet

A tiny alien pet that lives on the moon in your Android wallpaper.

Moonlet wanders across a real photograph of the lunar surface, gets hungry, gets bored, hunts moon worms, throws tantrums when he misses them, and reacts to how you physically handle your phone. A little floating bubble gives you a menu to look after him, and it only appears when you're on your home screen.

## Where Moonlet came from

I was sitting in a waiting room, bored, scrolling nothing in particular and staring at my phone's home screen. My wallpaper is a photo of Earth rising over the moon, and the empty grey surface at the bottom looked like it was missing something. Then an idea popped into my head: *what if a little creature lived there?*

So I set myself a challenge: **create, build, launch and test the whole thing using only my phone.** No laptop, no desk, no IDE. If I couldn't do it from the waiting room chair, it didn't count.

It started as a Tamagotchi-style toy and kept growing: a live wallpaper instead of a regular app, a real photo instead of a cartoon, a pet that moves in depth, worms that dig escape holes, and a sulking, tantrum-throwing personality.

### How it was built, all on the phone

- **Device:** Pixel 9 Pro XL running GrapheneOS with the KISS launcher
- **Code:** written by vibe coding with [Claude](https://claude.ai) in the mobile app
- **Version control:** Termux (git) pushing to this repo from the phone
- **Builds:** GitHub Actions compiles the APK in the cloud, and I download and install it straight onto the same phone
- **Testing:** the phone's built-in screen recorder, compressed on-device with ffmpeg in Termux, then reviewed by Gemini for animation and pacing notes that went back into the next version

Every release (v1 through v8 so far) went through the same loop: idea, code, push, build, install, watch Moonlet, repeat.

## What Moonlet does

- **Lives in your wallpaper:** a native Android live wallpaper with a real NASA photo backdrop
- **Moves in 3D:** wanders from the foreground all the way back to the horizon, shrinking with distance
- **Faces where he's going:** turns toward the direction he walks, and shows a wagging corgi bum when he walks away
- **Reacts to your phone:** shake it and the ground shakes and he gets dizzy; tilt it and he slides; charging gives him energy; low battery makes him shiver; he sleeps at night
- **Moon worms:** worms pop out of holes, run, and dig an escape hole while Moonlet chases them. Catch them and he eats; miss a few in a row and he throws a tantrum
- **Behaviours:** dancing, digging for gems, napping in craters, rolling in dust, zoomies, waving at Earth, waving at passing UFOs, and sulking if you ignore him
- **Floating bubble menu:** Feed, Pet, Play, Groom, Sleep and Status, shown only on the home screen

## Building it

1. Push to `main` (or run the workflow manually) and GitHub Actions builds `app-debug.apk`
2. Download the `moonpet-apk` artifact, unzip it, and install the APK
3. Open **Moon Pet** and work through the four setup buttons: allow the floating bubble, allow notifications, set as live wallpaper, start the bubble

Builds are debug-signed with a fresh key each time, so uninstall the previous version before installing a new one. That resets Moonlet's stats.

## Credits

- Wallpaper photo: NASA, Earth rising over the moon from the Artemis II mission (image ID `art002e009288`)
- Built by Khovre, with Claude doing the coding and Gemini reviewing the screen recordings
