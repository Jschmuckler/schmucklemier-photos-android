# schmucklemier-photos-android

## Google Authentication Setup

1. Go to the [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select an existing one
3. Navigate to "APIs & Services" > "Credentials"
4. Click "Create Credentials" and select "OAuth client ID"
5. Select "Android" as the Application type
6. Enter your app's package name
7. Generate a SHA-1 signing certificate fingerprint:
   ```
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```
8. Enter the SHA-1 certificate fingerprint
9. Click "Create"

## Configuration

Create a `local.properties` file in the root directory and add:
```
# SDK directory location
sdk.dir=/path/to/your/android/sdk

# GCP OAuth client ID from Google Cloud Console
gcp.client.id=YOUR_CLIENT_ID.apps.googleusercontent.com
```