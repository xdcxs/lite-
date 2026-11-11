Lite Browser - tiny Android browser (no libraries, system WebView only)
BUILD: open folder in Android Studio -> Build > Build APK, OR upload to GitHub and run the "build" Action (APK appears in Artifacts).
EXTENSIONS: menu (⋮) > Add extension (.zip). Zip must contain manifest.json.
Supported: manifest content_scripts (matches / js / css) + tiny chrome.runtime/storage stub.
Not supported: background pages, popups, full chrome.* APIs, webRequest.
