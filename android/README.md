# MS App Store — Android app

This is a separate native Android application module under `/android`. The GitHub Pages website's existing `index.html` and site workflows are kept separate and unchanged.

## Open in Android Studio
1. Clone or download this repository.
2. In Android Studio choose **Open** and select the `android` folder.
3. Use JDK 17 and allow Gradle sync to finish.
4. Run the `app` configuration on an emulator or Android phone.

## Current foundation
- Kotlin + Jetpack Compose native UI.
- Reads approved app records from the existing MS App Store Supabase project.
- Search and Apps/Games filter.
- App details screen.
- APK download through Android DownloadManager with a blue 0–100% progress indicator.
- Hands the downloaded APK to Android's package installer. Android may ask the user to allow installs from this source.

## Next milestones
- Admin sign-in/authorization and admin-only APK publishing.
- Firebase Cloud Messaging setup for all-user push notifications.
- Developer storefront names and approved-developer mapping.
- Verify APK signed-URL access against current Storage RLS policies.
- Add Gradle wrapper files and run a full Android build in an Android SDK environment.

Only the public Supabase publishable key is used. Never add a service-role or secret key to the Android app.
