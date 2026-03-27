version = "1.0.0" // Plugin version. Increment this to trigger the updater
description = "This is your plugin description"

aliucord {
    changelog = """
        # 1.0.0
        * Initial version
    """.trimIndent()

    // Image or GIF that will be shown at the top of the changelog page
    //changelogMedia = "https://nice.png"

    author("Name", 0L)

    // Uncomment if the plugin is unfinished
    //deploy = false
}