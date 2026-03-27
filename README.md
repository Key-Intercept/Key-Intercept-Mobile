## Aliucord Plugin Template
### Getting Started
1. Open the [build.gradle.kts](https://github.com/RazerTexz/aliucord-plugin-template/blob/main/plugins/build.gradle.kts) inside the `plugins` folder and replace all the placeholders.
2. Modify your plugin metadata by opening the [build.gradle.kts](https://github.com/RazerTexz/aliucord-plugin-template/blob/main/plugins/ExamplePlugin/build.gradle.kts) inside the `ExamplePlugin` folder.
3. Build your plugin using:
    - **Windows**: `.\gradlew.bat :plugins:ExamplePlugin:make`
    - **Linux and MacOS**: `./gradlew :plugins:ExamplePlugin:make`

### GitHub Actions Setup
To significantly speed up your builds on GitHub Actions, you need to add a secret key:
1. Go to your repository **Settings**.
2. Navigate to **Secrets and variables** > **Actions**.
3. Click on **New repository secret**.
4. Set the **Name** to `GRADLE_ENCRYPTION_KEY`.
5. Set the **Secret** to any random string (e.g., a generated password or UUID).
6. Click on **Add secret**.
7. Done!

## Plugin Documentation
Read [this](https://github.com/Aliucord/documentation/tree/main/plugin-dev).