# Third-party notices

FeishuWatch's original work is AGPL-3.0-only. The components below retain their
own licenses. Their presence does not relicense the upstream projects.

## ZXing Core 3.5.3

- Included in the Android APK for QR code generation.
- Copyright ZXing authors and contributors.
- Apache License 2.0; upstream license text, including its bundled notices:
  [LICENSE](docs/licenses/ZXing-LICENSE.txt) and
  [NOTICE](docs/licenses/ZXing-NOTICE.txt).
- Source: https://github.com/zxing/zxing/tree/zxing-3.5.3/core
- Dependency: `com.google.zxing:core:3.5.3` (unmodified).

## Gradle Wrapper 8.9

- Build tooling distributed in the source repository; not an application runtime
  dependency bundled into the APK.
- Copyright Gradle, Inc. and contributors.
- [License](docs/licenses/Gradle-LICENSE.txt) copied from Gradle 8.9;
  the wrapper JAR also retains its embedded `META-INF/LICENSE`.
- Source: https://github.com/gradle/gradle/tree/v8.9.0

JUnit, AndroidX Test and org.json are test dependencies resolved during builds,
not runtime libraries shipped in the app. The Android platform is provided by
the device. The optional Node.js service has no third-party runtime packages.

The APK includes the project license and these third-party notices in its assets.
